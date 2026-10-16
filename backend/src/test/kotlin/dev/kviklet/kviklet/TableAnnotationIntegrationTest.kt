package dev.kviklet.kviklet

import dev.kviklet.kviklet.db.ConfigurationAdapter
import dev.kviklet.kviklet.db.ConnectionAdapter
import dev.kviklet.kviklet.db.TableAnnotationAdapter
import dev.kviklet.kviklet.db.User
import dev.kviklet.kviklet.helper.ConnectionHelper
import dev.kviklet.kviklet.helper.ExecutionRequestHelper
import dev.kviklet.kviklet.helper.RoleHelper
import dev.kviklet.kviklet.helper.UserHelper
import dev.kviklet.kviklet.service.dto.AnnotationSource
import dev.kviklet.kviklet.service.dto.AuthenticationType
import dev.kviklet.kviklet.service.dto.Connection
import dev.kviklet.kviklet.service.dto.ConnectionId
import dev.kviklet.kviklet.service.dto.DatabaseProtocol
import dev.kviklet.kviklet.service.dto.DatasourceType
import dev.kviklet.kviklet.service.dto.ReviewConfig
import dev.kviklet.kviklet.service.dto.TableAnnotation
import jakarta.servlet.http.Cookie
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.core.io.FileUrlResource
import org.springframework.jdbc.datasource.init.ScriptUtils
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.testcontainers.containers.JdbcDatabaseContainer
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName

/**
 * End-to-end cover for the acknowledgement gate: an approved statement that writes to an annotated
 * table must not run until the executor confirms the warning, and the confirmation must land in the
 * audit log.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TableAnnotationIntegrationTest {

    @Autowired private lateinit var mockMvc: MockMvc

    @Autowired private lateinit var userHelper: UserHelper

    @Autowired private lateinit var roleHelper: RoleHelper

    @Autowired private lateinit var connectionHelper: ConnectionHelper

    @Autowired private lateinit var executionRequestHelper: ExecutionRequestHelper

    @Autowired private lateinit var connectionAdapter: ConnectionAdapter

    @Autowired private lateinit var tableAnnotationAdapter: TableAnnotationAdapter

    @Autowired private lateinit var configurationAdapter: ConfigurationAdapter

    private lateinit var testUser: User
    private lateinit var testReviewer: User
    private var connectionCount = 1

    companion object {
        val postgresDb: PostgreSQLContainer<*> = PostgreSQLContainer(DockerImageName.parse("postgres:11.1"))
            .withUsername("root")
            .withPassword("root")
            .withReuse(true)
            .withDatabaseName("test_db")

        init {
            postgresDb.start()
        }
    }

    @BeforeEach
    fun setup() {
        val initScript = this::class.java.classLoader.getResource("psql_init.sql")!!
        ScriptUtils.executeSqlScript(postgresDb.createConnection(""), FileUrlResource(initScript))

        testUser = userHelper.createUser(permissions = listOf("*"))
        testReviewer = userHelper.createUser(permissions = listOf("*"))
    }

    @AfterEach
    fun tearDown() {
        tableAnnotationAdapter.findAll().forEach { tableAnnotationAdapter.delete(it.id!!) }
        executionRequestHelper.deleteAll()
        connectionHelper.deleteAll()
        userHelper.deleteAll()
        roleHelper.deleteAll()
        connectionCount = 1
    }

    @Test
    fun `a write to an annotated table is blocked until it is acknowledged`() {
        val connection = createPostgresConnection(postgresDb)
        annotate("simple_table", "Mirrored into the read cache. Purge it after this runs.")

        val request = approvedRequest(connection, "UPDATE foo.simple_table SET col2 = 'baz' WHERE col1 = 1")
        val userCookie = userHelper.login(email = testUser.email, mockMvc = mockMvc)

        // Without an acknowledgement the statement is refused, and the response names what is
        // outstanding so the client can show it.
        mockMvc.perform(
            post("/execution-requests/${request.getId()}/execute")
                .cookie(userCookie)
                .content("{}")
                .contentType("application/json"),
        )
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.warnings[0].table").value("foo.simple_table"))
            .andExpect(jsonPath("$.warnings[0].message").value(containsCachePurgeHint()))
    }

    @Test
    fun `acknowledging the table lets the statement run and records it in the audit log`() {
        val connection = createPostgresConnection(postgresDb)
        annotate("simple_table", "Mirrored into the read cache. Purge it after this runs.")

        val request = approvedRequest(connection, "UPDATE foo.simple_table SET col2 = 'baz' WHERE col1 = 1")
        val userCookie = userHelper.login(email = testUser.email, mockMvc = mockMvc)

        mockMvc.perform(
            post("/execution-requests/${request.getId()}/execute")
                .cookie(userCookie)
                .content("""{"acknowledgedTables": ["foo.simple_table"]}""")
                .contentType("application/json"),
        )
            .andExpect(status().isOk)

        mockMvc.perform(get("/execution-requests/${request.getId()}").cookie(userCookie))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.events[1].type").value("EXECUTE"))
            .andExpect(jsonPath("$.events[1].acknowledgedTables[0]").value("foo.simple_table"))
    }

    @Test
    fun `a select against an annotated table needs no acknowledgement`() {
        val connection = createPostgresConnection(postgresDb)
        annotate("simple_table", "Mirrored into the read cache.")

        val request = approvedRequest(connection, "SELECT * FROM foo.simple_table")
        val userCookie = userHelper.login(email = testUser.email, mockMvc = mockMvc)

        mockMvc.perform(
            post("/execution-requests/${request.getId()}/execute")
                .cookie(userCookie)
                .content("{}")
                .contentType("application/json"),
        )
            .andExpect(status().isOk)
    }

    @Test
    fun `a write to an unannotated table needs no acknowledgement`() {
        val connection = createPostgresConnection(postgresDb)
        annotate("some_other_table", "Mirrored into the read cache.")

        val request = approvedRequest(connection, "UPDATE foo.simple_table SET col2 = 'baz' WHERE col1 = 1")
        val userCookie = userHelper.login(email = testUser.email, mockMvc = mockMvc)

        mockMvc.perform(
            post("/execution-requests/${request.getId()}/execute")
                .cookie(userCookie)
                .content("{}")
                .contentType("application/json"),
        )
            .andExpect(status().isOk)
    }

    @Test
    fun `an annotation that does not require acknowledgement only warns`() {
        val connection = createPostgresConnection(postgresDb)
        annotate("simple_table", "Heads up, this one is cached.", requiresAcknowledgement = false)

        val request = approvedRequest(connection, "UPDATE foo.simple_table SET col2 = 'baz' WHERE col1 = 1")
        val userCookie = userHelper.login(email = testUser.email, mockMvc = mockMvc)

        mockMvc.perform(
            post("/execution-requests/${request.getId()}/execute")
                .cookie(userCookie)
                .content("{}")
                .contentType("application/json"),
        )
            .andExpect(status().isOk)
    }

    @Test
    fun `an annotation for another connection does not apply`() {
        val connection = createPostgresConnection(postgresDb)
        annotate("simple_table", "Cached elsewhere.", connectionSelector = "some-other-connection")

        val request = approvedRequest(connection, "UPDATE foo.simple_table SET col2 = 'baz' WHERE col1 = 1")
        val userCookie = userHelper.login(email = testUser.email, mockMvc = mockMvc)

        mockMvc.perform(
            post("/execution-requests/${request.getId()}/execute")
                .cookie(userCookie)
                .content("{}")
                .contentType("application/json"),
        )
            .andExpect(status().isOk)
    }

    @Test
    fun `the analyze endpoint reports the warning before the request exists`() {
        val connection = createPostgresConnection(postgresDb)
        annotate("simple_table", "Mirrored into the read cache.")
        val userCookie = userHelper.login(email = testUser.email, mockMvc = mockMvc)

        mockMvc.perform(
            post("/connections/${connection.getId()}/analyze-statement")
                .cookie(userCookie)
                .content("""{"statement": "UPDATE foo.simple_table SET col2 = 'baz'"}""")
                .contentType("application/json"),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.parsed").value(true))
            .andExpect(jsonPath("$.warnings[0].table").value("foo.simple_table"))
    }

    @Test
    fun `the analyze endpoint reports unparseable sql as unknown rather than safe`() {
        val connection = createPostgresConnection(postgresDb)
        annotate("simple_table", "Mirrored into the read cache.")
        val userCookie = userHelper.login(email = testUser.email, mockMvc = mockMvc)

        mockMvc.perform(
            post("/connections/${connection.getId()}/analyze-statement")
                .cookie(userCookie)
                .content("""{"statement": "UPDATE WHERE WHERE (("}""")
                .contentType("application/json"),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.parsed").value(false))
            .andExpect(jsonPath("$.warnings").isEmpty)
    }

    @Test
    fun `the sync endpoint answers with the status instead of failing the policy check`() {
        // The @Policy interceptor post-checks a method's return value and demands a
        // SecuredDomainObject, so a service method that returned the status object itself blew up
        // with a 500 *after* syncing. Sync with no URL configured is a no-op, which is enough to
        // exercise the endpoint's response path.
        val userCookie = userHelper.login(email = testUser.email, mockMvc = mockMvc)

        mockMvc.perform(post("/table-annotations/sync").cookie(userCookie))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.configured").value(false))
    }

    @Test
    fun `a sync against an unreachable source reports the error and keeps the annotations`() {
        // The admin asked to sync, so "here is why it failed, and what you had is still in force"
        // beats a generic 500. Nothing listens on this port.
        configurationAdapter.setConfiguration("tableAnnotationSyncUrl", "http://127.0.0.1:1/annotations")
        annotate("simple_table", "Mirrored into the read cache.")
        val userCookie = userHelper.login(email = testUser.email, mockMvc = mockMvc)

        try {
            mockMvc.perform(post("/table-annotations/sync").cookie(userCookie))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.lastSyncError").isNotEmpty)

            // The manual annotation is untouched, so the warnings keep working.
            mockMvc.perform(get("/table-annotations/").cookie(userCookie))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.annotations[0].tablePattern").value("simple_table"))
        } finally {
            configurationAdapter.setConfiguration("tableAnnotationSyncUrl", "")
            configurationAdapter.setConfiguration("tableAnnotationLastSyncError", "")
        }
    }

    @Test
    fun `the list endpoint returns annotations with their sync status`() {
        annotate("simple_table", "Mirrored into the read cache.")
        val userCookie = userHelper.login(email = testUser.email, mockMvc = mockMvc)

        mockMvc.perform(get("/table-annotations/").cookie(userCookie))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.annotations[0].tablePattern").value("simple_table"))
            .andExpect(jsonPath("$.annotations[0].source").value("MANUAL"))
            .andExpect(jsonPath("$.syncStatus.neverSynced").value(false))
    }

    @Test
    fun `a sync never discards manually managed annotations`() {
        val manual = tableAnnotationAdapter.create(
            TableAnnotation(tablePattern = "manual_table", message = "Managed by hand"),
        )
        tableAnnotationAdapter.replaceSynced(
            listOf(
                TableAnnotation(
                    tablePattern = "synced_table",
                    message = "From the source",
                    source = AnnotationSource.SYNCED,
                    externalId = "ext-1",
                ),
            ),
        )

        // A later sync that no longer lists ext-1 drops the synced row and leaves the manual one.
        tableAnnotationAdapter.replaceSynced(emptyList())

        val remaining = tableAnnotationAdapter.findAll()
        check(remaining.map { it.id } == listOf(manual.id)) {
            "expected only the manual annotation to survive, got $remaining"
        }
    }

    @Test
    fun `a sync upserts by external id instead of duplicating rows`() {
        val first = TableAnnotation(
            tablePattern = "synced_table",
            message = "First message",
            source = AnnotationSource.SYNCED,
            externalId = "ext-1",
        )
        tableAnnotationAdapter.replaceSynced(listOf(first))
        val afterFirst = tableAnnotationAdapter.findAll().single()

        tableAnnotationAdapter.replaceSynced(listOf(first.copy(message = "Second message")))
        val afterSecond = tableAnnotationAdapter.findAll().single()

        check(afterSecond.id == afterFirst.id) { "sync should reuse the row, not recreate it" }
        check(afterSecond.message == "Second message") { "sync should update the message" }
    }

    private fun containsCachePurgeHint() = "Mirrored into the read cache. Purge it after this runs."

    private fun annotate(
        tablePattern: String,
        message: String,
        requiresAcknowledgement: Boolean = true,
        connectionSelector: String = "*",
    ) {
        tableAnnotationAdapter.create(
            TableAnnotation(
                tablePattern = tablePattern,
                connectionSelector = connectionSelector,
                message = message,
                requiresAcknowledgement = requiresAcknowledgement,
            ),
        )
    }

    private fun approvedRequest(connection: Connection, statement: String) =
        executionRequestHelper.createExecutionRequest(
            dbcontainer = postgresDb,
            author = testUser,
            statement = statement,
            connection = connection,
        ).also {
            val reviewerCookie = userHelper.login(email = testReviewer.email, mockMvc = mockMvc)
            approveRequest(it.getId(), "Looks fine", reviewerCookie)
        }

    private fun approveRequest(executionRequestId: String, comment: String, cookie: Cookie) {
        mockMvc.perform(
            post("/execution-requests/$executionRequestId/reviews")
                .cookie(cookie)
                .content("""{"comment": "$comment", "action": "APPROVE"}""")
                .contentType("application/json"),
        )
            .andExpect(status().isOk)
    }

    private fun createPostgresConnection(container: JdbcDatabaseContainer<*>): Connection {
        val connection = connectionAdapter.createDatasourceConnection(
            ConnectionId("ds-conn-annotation-$connectionCount"),
            "Test Connection $connectionCount",
            AuthenticationType.USER_PASSWORD,
            container.databaseName,
            10,
            container.username,
            container.password,
            "A test connection",
            ReviewConfig(numTotalRequired = 1),
            container.getMappedPort(5432),
            container.host,
            DatasourceType.POSTGRESQL,
            DatabaseProtocol.POSTGRESQL,
            additionalJDBCOptions = "",
            dumpsEnabled = false,
            temporaryAccessEnabled = true,
            explainEnabled = false,
            storeResults = false,
            dryRunEnabled = false,
            dryRunRequiresApproval = false,
        )
        connectionCount++
        return connection
    }
}
