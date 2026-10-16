package dev.kviklet.kviklet

import dev.kviklet.kviklet.service.StatementAnalyzer
import dev.kviklet.kviklet.service.TableAnalysis
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

class StatementAnalyzerTest {

    private val analyzer = StatementAnalyzer()

    private fun writes(statement: String): List<String> {
        val analysis = analyzer.analyze(statement).shouldBeInstanceOf<TableAnalysis.Parsed>()
        return analysis.writeTables.map { it.toString() }
    }

    private fun reads(statement: String): List<String> {
        val analysis = analyzer.analyze(statement).shouldBeInstanceOf<TableAnalysis.Parsed>()
        return analysis.readTables.map { it.toString() }
    }

    @Test
    fun `a select writes nothing`() {
        writes("SELECT * FROM subscriber").shouldBeEmptyList()
        reads("SELECT * FROM subscriber") shouldContainExactlyInAnyOrder listOf("subscriber")
    }

    @Test
    fun `dml reports its target as a write`() {
        writes("UPDATE subscriber SET active = false") shouldContainExactlyInAnyOrder listOf("subscriber")
        writes("DELETE FROM subscriber WHERE id = 1") shouldContainExactlyInAnyOrder listOf("subscriber")
        writes("INSERT INTO subscriber (id) VALUES (1)") shouldContainExactlyInAnyOrder listOf("subscriber")
        writes("TRUNCATE TABLE subscriber") shouldContainExactlyInAnyOrder listOf("subscriber")
    }

    @Test
    fun `ddl reports its target as a write`() {
        writes("ALTER TABLE subscriber ADD COLUMN x int") shouldContainExactlyInAnyOrder listOf("subscriber")
        writes("DROP TABLE subscriber") shouldContainExactlyInAnyOrder listOf("subscriber")
        writes("CREATE TABLE subscriber (id int)") shouldContainExactlyInAnyOrder listOf("subscriber")
    }

    @Test
    fun `a table only read by an update is not a write`() {
        val statement = "UPDATE subscriber SET active = false WHERE plan_id IN (SELECT id FROM plan)"
        writes(statement) shouldContainExactlyInAnyOrder listOf("subscriber")
        reads(statement) shouldContainExactlyInAnyOrder listOf("subscriber", "plan")
    }

    @Test
    fun `schema qualified names keep the schema and still match the bare name`() {
        val analysis = analyzer.analyze("UPDATE public.subscriber SET active = false")
            .shouldBeInstanceOf<TableAnalysis.Parsed>()
        val reference = analysis.writeTables.single()

        reference.schema shouldBe "public"
        reference.name shouldBe "subscriber"
        reference.matchCandidates() shouldContainExactlyInAnyOrder listOf("subscriber", "public.subscriber")
    }

    @Test
    fun `quoting and casing are normalised across dialects`() {
        writes("""UPDATE "Public"."Subscriber" SET active = false""") shouldContainExactlyInAnyOrder
            listOf("public.subscriber")
        writes("UPDATE `Subscriber` SET active = false") shouldContainExactlyInAnyOrder listOf("subscriber")
        writes("UPDATE SUBSCRIBER SET active = false") shouldContainExactlyInAnyOrder listOf("subscriber")
    }

    @Test
    fun `a catalog qualifier ahead of the schema is dropped`() {
        val reference = analyzer.analyze("UPDATE mydb.public.subscriber SET active = false")
            .shouldBeInstanceOf<TableAnalysis.Parsed>()
            .writeTables.single()

        reference.schema shouldBe "public"
        reference.name shouldBe "subscriber"
    }

    @Test
    fun `a cte name is not reported as a table`() {
        val statement = """
            WITH subscriber AS (SELECT id FROM customer)
            SELECT * FROM subscriber
        """.trimIndent()

        reads(statement) shouldContainExactlyInAnyOrder listOf("customer")
    }

    @Test
    fun `every statement in a script is analysed`() {
        val statement = "UPDATE subscriber SET active = false; DELETE FROM plan WHERE id = 2;"
        writes(statement) shouldContainExactlyInAnyOrder listOf("subscriber", "plan")
    }

    @Test
    fun `unparseable sql is reported as such rather than as touching nothing`() {
        val analysis = analyzer.analyze("UPDATE WHERE WHERE ((")
        analysis.shouldBeInstanceOf<TableAnalysis.Unparseable>()
    }

    @Test
    fun `an empty statement parses to nothing`() {
        val analysis = analyzer.analyze("   ").shouldBeInstanceOf<TableAnalysis.Parsed>()
        analysis.writeTables.shouldBeEmptyCollection()
        analysis.readTables.shouldBeEmptyCollection()
    }
}

private fun List<String>.shouldBeEmptyList() {
    if (isNotEmpty()) throw AssertionError("expected no tables but got $this")
}

private fun Set<Any>.shouldBeEmptyCollection() {
    if (isNotEmpty()) throw AssertionError("expected no tables but got $this")
}
