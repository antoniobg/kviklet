package dev.kviklet.kviklet.service

import net.sf.jsqlparser.parser.CCJSqlParserUtil
import net.sf.jsqlparser.schema.Table
import net.sf.jsqlparser.statement.Statement
import net.sf.jsqlparser.statement.alter.Alter
import net.sf.jsqlparser.statement.create.table.CreateTable
import net.sf.jsqlparser.statement.delete.Delete
import net.sf.jsqlparser.statement.drop.Drop
import net.sf.jsqlparser.statement.insert.Insert
import net.sf.jsqlparser.statement.merge.Merge
import net.sf.jsqlparser.statement.select.Select
import net.sf.jsqlparser.statement.truncate.Truncate
import net.sf.jsqlparser.statement.update.Update
import net.sf.jsqlparser.util.TablesNamesFinder
import org.springframework.stereotype.Component

/**
 * A table named by a statement, normalised so it can be matched against an annotation pattern.
 *
 * Identifiers are lower-cased and stripped of quoting, and any catalog/database qualifier ahead of
 * the schema is dropped: `"MyDb"."Public"."Subscriber"` and `subscriber` both end up as the same
 * name, differing only in whether [schema] is known.
 */
data class TableReference(val schema: String?, val name: String) {

    /**
     * The names an annotation pattern may be written against. A statement that qualifies its table
     * still matches a bare `subscriber` pattern, so admins don't have to know whether a given
     * statement spells the schema out.
     */
    fun matchCandidates(): Set<String> = setOfNotNull(name, schema?.let { "$it.$name" })

    override fun toString(): String = schema?.let { "$it.$name" } ?: name
}

sealed class TableAnalysis {
    /**
     * [writeTables] are the tables a statement actually modifies; [readTables] is everything it
     * touches, writes included, so a table read in an `UPDATE ... FROM` subquery appears there too.
     */
    data class Parsed(val readTables: Set<TableReference>, val writeTables: Set<TableReference>) : TableAnalysis()

    data class Unparseable(val reason: String) : TableAnalysis()
}

/**
 * Works out which tables a statement reads and which it writes.
 *
 * Writes are taken from each statement's own target rather than from [TablesNamesFinder], because
 * the finder flattens a statement into one list and cannot tell the target of an `UPDATE` from a
 * table merely read in its `WHERE` clause. That distinction is the whole point here: warning on
 * every `SELECT` that mentions a hot table trains people to ignore the warning.
 */
@Component
class StatementAnalyzer {

    fun analyze(statement: String): TableAnalysis {
        if (statement.isBlank()) {
            return TableAnalysis.Parsed(emptySet(), emptySet())
        }

        val statements = try {
            CCJSqlParserUtil.parseStatements(statement)
        } catch (e: Exception) {
            // Covers both genuinely invalid SQL and vendor syntax JSqlParser does not model. The
            // caller surfaces this as "couldn't determine which tables this touches" rather than
            // treating it as "touches nothing", so an unparseable statement never looks safe.
            return TableAnalysis.Unparseable(e.message ?: "Could not parse the statement")
        }

        val readTables = mutableSetOf<TableReference>()
        val writeTables = mutableSetOf<TableReference>()

        for (parsed in statements) {
            val cteNames = cteNamesOf(parsed)
            writeTables += writeTargetsOf(parsed).map { it.toReference() }
            readTables += tablesOf(parsed)
                .filterNot { it.lowercase() in cteNames }
                .map { parseTableName(it) }
        }

        return TableAnalysis.Parsed(readTables = readTables, writeTables = writeTables)
    }

    private fun tablesOf(statement: Statement): Set<String> = try {
        TablesNamesFinder().getTables(statement)
    } catch (e: Exception) {
        // The finder throws on a few constructs it can parse but not walk. The write target is
        // extracted separately and is the part that gates execution, so a failure here only costs
        // the read-side detail.
        emptySet()
    }

    /**
     * Names bound by a `WITH` clause. These are statement-local and are not real tables, so they
     * must not match an annotation even when a CTE is deliberately named after the table it reads.
     */
    private fun cteNamesOf(statement: Statement): Set<String> {
        val withItems = when (statement) {
            is Select -> statement.withItemsList
            is Update -> statement.withItemsList
            is Insert -> statement.withItemsList
            is Delete -> statement.withItemsList
            else -> null
        }
        return withItems.orEmpty().mapNotNull { it.alias?.name?.unquote()?.lowercase() }.toSet()
    }

    private fun writeTargetsOf(statement: Statement): List<Table> = when (statement) {
        is Update -> listOfNotNull(statement.table)

        is Insert -> listOfNotNull(statement.table)

        is Merge -> listOfNotNull(statement.table)

        is Truncate -> listOfNotNull(statement.table)

        is Alter -> listOfNotNull(statement.table)

        is CreateTable -> listOfNotNull(statement.table)

        is Drop -> listOfNotNull(statement.name)

        // A multi-table delete populates getTables() and leaves getTable() null, and vice versa.
        is Delete -> statement.tables.orEmpty().ifEmpty { listOfNotNull(statement.table) }

        else -> emptyList()
    }
}

private fun Table.toReference(): TableReference = TableReference(
    // nameParts holds catalog/schema/table; anything ahead of the schema is irrelevant to matching.
    schema = schemaName?.unquote()?.lowercase()?.takeIf { it.isNotEmpty() },
    name = name.unquote().lowercase(),
)

/**
 * Splits a qualified name produced by [TablesNamesFinder] into schema and table. Only the last two
 * segments matter: a leading catalog or database qualifier is dropped.
 */
internal fun parseTableName(raw: String): TableReference {
    val parts = raw.split('.').map { it.unquote() }.filter { it.isNotEmpty() }
    return TableReference(
        schema = parts.getOrNull(parts.size - 2)?.lowercase(),
        name = (parts.lastOrNull() ?: raw).lowercase(),
    )
}

/** Strips the quoting of every dialect Kviklet speaks: `"pg"`, `` `mysql` `` and `[mssql]`. */
private fun String.unquote(): String = trim().trim('"', '`', '[', ']')
