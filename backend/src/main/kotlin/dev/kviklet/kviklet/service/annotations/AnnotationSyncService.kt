package dev.kviklet.kviklet.service.annotations

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import dev.kviklet.kviklet.db.ConfigurationAdapter
import dev.kviklet.kviklet.db.TableAnnotationAdapter
import dev.kviklet.kviklet.security.NoPolicy
import dev.kviklet.kviklet.security.Permission
import dev.kviklet.kviklet.security.Policy
import dev.kviklet.kviklet.service.dto.AnnotationSource
import dev.kviklet.kviklet.service.dto.TableAnnotation
import dev.kviklet.kviklet.service.dto.utcTimeNow
import org.slf4j.LoggerFactory
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.web.client.RestTemplate
import java.net.URI
import java.time.LocalDateTime

/**
 * One annotation as published by the remote source.
 *
 * Unknown properties are ignored so the source can add fields without breaking Kviklet.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class RemoteTableAnnotation(
    val id: String?,
    val table: String?,
    val tablePattern: String?,
    val connectionSelector: String?,
    val message: String?,
    val runbookUrl: String?,
    val requiresAcknowledgement: Boolean?,
) {
    /** The source may name the field `table` or `tablePattern`; both mean the same thing. */
    fun pattern(): String? = (tablePattern ?: table)?.trim()?.takeIf { it.isNotEmpty() }
}

data class AnnotationSyncStatus(
    val configured: Boolean,
    val lastSyncAt: LocalDateTime?,
    val lastSyncError: String?,
    val syncedCount: Int,
) {
    /**
     * True when a sync source is configured but has never produced a list. Until then Kviklet knows
     * nothing about which tables are annotated, and an empty warning list must not be read as "this
     * statement is safe".
     */
    val neverSynced: Boolean get() = configured && lastSyncAt == null
}

/**
 * Keeps [AnnotationSource.SYNCED] annotations in step with the remote source.
 *
 * The fetched list is persisted rather than cached in memory, so a restart or an outage of the
 * remote source leaves the previously synced annotations in force. A failed sync therefore degrades
 * to "slightly stale" instead of "silently unprotected".
 */
@Service
class AnnotationSyncService(
    private val restTemplate: RestTemplate,
    private val configurationAdapter: ConfigurationAdapter,
    private val tableAnnotationAdapter: TableAnnotationAdapter,
) {

    private val logger = LoggerFactory.getLogger(javaClass)

    @NoPolicy
    @Scheduled(initialDelayString = "PT30S", fixedDelayString = "PT5M")
    fun syncScheduled() {
        try {
            sync()
        } catch (e: Exception) {
            // Already recorded for the settings page by sync(); logged here so an operator sees it.
            logger.warn("Scheduled table annotation sync failed", e)
        }
    }

    /**
     * Returns nothing on purpose: the `@Policy` interceptor post-checks a method's return value and
     * requires it to be a [dev.kviklet.kviklet.security.SecuredDomainObject], which a status object
     * is not. A void method is invisible to that check, so the caller reads [status] separately.
     *
     * A failure is reported through [AnnotationSyncStatus.lastSyncError] rather than thrown: the
     * admin asked to sync, and "the source is unreachable, here is the error, the previously synced
     * annotations are still in force" is a more useful answer than a generic 500.
     */
    @Policy(Permission.CONFIGURATION_EDIT)
    fun syncNow() {
        try {
            sync()
        } catch (e: Exception) {
            logger.warn("Manual table annotation sync failed", e)
        }
    }

    @NoPolicy
    fun status(): AnnotationSyncStatus = AnnotationSyncStatus(
        configured = !syncUrl().isNullOrBlank(),
        lastSyncAt = (configurationAdapter.getConfiguration(LAST_SYNC_AT_KEY) as String?)
            ?.let { runCatching { LocalDateTime.parse(it) }.getOrNull() },
        lastSyncError = (configurationAdapter.getConfiguration(LAST_SYNC_ERROR_KEY) as String?)
            ?.takeIf { it.isNotBlank() },
        syncedCount = tableAnnotationAdapter.findAll().count { it.source == AnnotationSource.SYNCED },
    )

    private fun sync() {
        val url = syncUrl()
        if (url.isNullOrBlank()) return

        try {
            val fetched = fetch(url)
            tableAnnotationAdapter.replaceSynced(fetched)
            configurationAdapter.setConfiguration(LAST_SYNC_AT_KEY, utcTimeNow().toString())
            configurationAdapter.setConfiguration(LAST_SYNC_ERROR_KEY, "")
            logger.info("Synced ${fetched.size} table annotation(s) from $url")
        } catch (e: Exception) {
            // The previously persisted rows stay in force; only the error is recorded so the
            // settings page can show that what Kviklet holds may be out of date.
            configurationAdapter.setConfiguration(LAST_SYNC_ERROR_KEY, e.message ?: e.javaClass.simpleName)
            throw e
        }
    }

    private fun fetch(url: String): List<TableAnnotation> {
        val headers = HttpHeaders().apply {
            configurationAdapter.getConfiguration(AUTH_HEADER_KEY)?.let { auth ->
                (auth as? String)?.takeIf { it.isNotBlank() }?.let { set(HttpHeaders.AUTHORIZATION, it) }
            }
        }
        // Parsed URI, not a String: RestTemplate would otherwise treat the URL as a URI template
        // and mangle any percent-encoded query parameters.
        val response = restTemplate.exchange(
            URI.create(url),
            HttpMethod.GET,
            HttpEntity<Void>(headers),
            Array<RemoteTableAnnotation>::class.java,
        )

        val now = utcTimeNow()
        return response.body.orEmpty().mapNotNull { remote ->
            val pattern = remote.pattern() ?: return@mapNotNull null
            TableAnnotation(
                tablePattern = pattern,
                connectionSelector = remote.connectionSelector?.trim()?.takeIf { it.isNotEmpty() } ?: "*",
                message = remote.message?.takeIf { it.isNotBlank() } ?: "$pattern is annotated.",
                runbookUrl = remote.runbookUrl?.takeIf { it.isNotBlank() },
                requiresAcknowledgement = remote.requiresAcknowledgement ?: true,
                source = AnnotationSource.SYNCED,
                // Fall back to the pattern so a source that publishes no ids still upserts
                // stably instead of duplicating rows on every sync.
                externalId = remote.id?.takeIf { it.isNotBlank() } ?: "$pattern@${remote.connectionSelector ?: "*"}",
                updatedAt = now,
            )
        }
    }

    private fun syncUrl(): String? = configurationAdapter.getConfiguration(SYNC_URL_KEY) as String?

    companion object {
        private const val SYNC_URL_KEY = "tableAnnotationSyncUrl"
        private const val AUTH_HEADER_KEY = "tableAnnotationSyncAuthHeader"
        private const val LAST_SYNC_AT_KEY = "tableAnnotationLastSyncAt"
        private const val LAST_SYNC_ERROR_KEY = "tableAnnotationLastSyncError"
    }
}
