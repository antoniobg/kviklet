package dev.kviklet.kviklet.service

import dev.kviklet.kviklet.db.TableAnnotationAdapter
import dev.kviklet.kviklet.security.NoPolicy
import dev.kviklet.kviklet.service.dto.Connection
import dev.kviklet.kviklet.service.dto.DatasourceConnection
import dev.kviklet.kviklet.service.dto.DatasourceType
import dev.kviklet.kviklet.service.dto.TableAnnotation
import org.springframework.stereotype.Service
import org.springframework.util.AntPathMatcher

/**
 * One annotation matched against one table a statement is about to write to.
 */
data class TableWarning(
    val table: String,
    val message: String,
    val runbookUrl: String?,
    val requiresAcknowledgement: Boolean,
)

/**
 * What Kviklet can say about the tables a statement writes to.
 *
 * [Unknown] is deliberately distinct from an empty [Known]: a statement Kviklet could not parse is
 * not a statement known to be harmless, and the UI must say so rather than showing nothing.
 */
sealed class StatementWarnings {
    data class Known(val warnings: List<TableWarning>) : StatementWarnings() {
        val acknowledgementRequired: List<TableWarning> get() = warnings.filter { it.requiresAcknowledgement }
    }

    data class Unknown(val reason: String) : StatementWarnings()

    companion object {
        val none = Known(emptyList())
    }
}

@Service
class TableWarningService(
    private val statementAnalyzer: StatementAnalyzer,
    private val tableAnnotationAdapter: TableAnnotationAdapter,
) {

    // Connection selectors follow the role policy convention, so they use the same default
    // matcher as PolicyGrantedAuthority. Table patterns are matched with "." as the separator
    // so a wildcard stays inside one identifier and cannot span a schema qualifier.
    private val connectionMatcher = AntPathMatcher()
    private val tableMatcher = AntPathMatcher(".")

    /**
     * Warnings for the tables [statement] writes to on [connection].
     *
     * Only writes are considered: a `SELECT` cannot leave a secondary store stale, and warning on
     * reads would bury the warnings that matter. Connection types with no SQL to parse produce no
     * warnings at all rather than an [StatementWarnings.Unknown], since there is nothing to be
     * uncertain about.
     */
    @NoPolicy
    fun warningsFor(connection: Connection, statement: String?): StatementWarnings {
        if (connection !is DatasourceConnection || connection.type == DatasourceType.MONGODB) {
            return StatementWarnings.none
        }
        if (statement.isNullOrBlank()) {
            return StatementWarnings.none
        }

        val annotations = annotationsFor(connection)
        if (annotations.isEmpty()) {
            // Nothing is annotated for this connection, so there is no question to answer and an
            // unparseable statement needs no "unknown" notice.
            return StatementWarnings.none
        }

        return when (val analysis = statementAnalyzer.analyze(statement)) {
            is TableAnalysis.Unparseable -> StatementWarnings.Unknown(analysis.reason)

            is TableAnalysis.Parsed -> StatementWarnings.Known(
                analysis.writeTables.flatMap { table ->
                    annotations.filter { annotation -> table.matches(annotation) }
                        .map { annotation ->
                            TableWarning(
                                table = table.toString(),
                                message = annotation.message,
                                runbookUrl = annotation.runbookUrl,
                                requiresAcknowledgement = annotation.requiresAcknowledgement,
                            )
                        }
                }.distinct(),
            )
        }
    }

    @NoPolicy
    fun annotationsFor(connection: Connection): List<TableAnnotation> = tableAnnotationAdapter.findAll()
        .filter { connectionMatcher.match(it.connectionSelector, connection.id.toString()) }

    private fun TableReference.matches(annotation: TableAnnotation): Boolean {
        val pattern = annotation.tablePattern.trim().lowercase()
        return matchCandidates().any { tableMatcher.match(pattern, it) }
    }
}
