package dev.caracal.core.sql

import kotlin.test.assertEquals
import org.junit.jupiter.api.Test

class StatementClassifierTest {

    @Test
    fun `plain queries read`() {
        assertRead("select * from invoices")
        assertRead("SELECT 1")
        assertRead("table invoices")
        assertRead("values (1), (2)")
        assertRead("show search_path")
        assertRead("with recent as (select * from invoices limit 10) select * from recent")
    }

    @Test
    fun `modifying commands are writes`() {
        assertWrite("insert into t values (1)")
        assertWrite("update t set a = 1")
        assertWrite("delete from t")
        assertWrite("merge into t using s on t.id = s.id when matched then delete")
        assertWrite("truncate t")
        assertWrite("create table t (a int)")
        assertWrite("alter table t add column b int")
        assertWrite("drop table t")
        assertWrite("grant select on t to alice")
        assertWrite("revoke select on t from alice")
        assertWrite("copy t from '/tmp/x.csv'")
        assertWrite("call refresh_totals()")
        assertWrite("do ${'$'}${'$'} begin perform 1; end ${'$'}${'$'}")
        assertWrite("vacuum analyze t")
        assertWrite("comment on table t is 'x'")
    }

    @Test
    fun `a write hidden in a CTE is still a write`() {
        // Begins with WITH, empties a table. The first keyword is not enough.
        assertWrite("with deleted as (delete from t returning *) select * from deleted")
        assertWrite("with moved as (insert into archive select * from t returning *) select count(*) from moved")
    }

    @Test
    fun `select into creates a table and counts as a write`() {
        assertWrite("select * into archive from invoices")
    }

    @Test
    fun `select for update is a write`() {
        // It takes row locks, and a read-only transaction refuses it — so warning
        // about it beforehand is more useful than letting the server say no.
        assertWrite("select * from t where id = 1 for update")
    }

    @Test
    fun `explain analyze on a query is a read`() {
        // ANALYZE as a command rewrites statistics; here it is how you inspect a slow
        // query, and refusing it would break the workflow the guide asks for.
        assertRead("explain analyze select * from invoices")
        assertRead("explain (analyze, buffers) select 1")
    }

    @Test
    fun `explain analyze on a write is a write`() {
        // It really does run the insert.
        assertWrite("explain analyze insert into t values (1)")
    }

    @Test
    fun `transaction and session control is its own case`() {
        assertEquals(StatementKind.SESSION, StatementClassifier.classify("begin"))
        assertEquals(StatementKind.SESSION, StatementClassifier.classify("commit"))
        assertEquals(StatementKind.SESSION, StatementClassifier.classify("rollback"))
        assertEquals(StatementKind.SESSION, StatementClassifier.classify("savepoint s1"))
        assertEquals(StatementKind.SESSION, StatementClassifier.classify("discard all"))
        assertEquals(StatementKind.SESSION, StatementClassifier.classify("lock table t"))
        assertEquals(StatementKind.SESSION, StatementClassifier.classify("declare c cursor for select 1"))
        assertEquals(
            StatementKind.SESSION,
            StatementClassifier.classify("set session characteristics as transaction read write"),
            "the one statement that can outlive its transaction and disarm the read-only guarantee",
        )
    }

    // Quoted and commented text is not SQL. Counting it would make the classifier
    // refuse ordinary queries, which is how a safety feature gets switched off.

    @Test
    fun `a keyword inside a string is data`() {
        assertRead("select * from t where note = 'delete from everything'")
        assertRead("""select E'drop table t\'' as note""")
    }

    @Test
    fun `a keyword inside a quoted identifier is a name`() {
        assertRead("""select "update", "drop" from audit""")
    }

    @Test
    fun `a keyword inside a comment is a note`() {
        assertRead("select 1 -- delete from t when you get a chance")
        assertRead("select 1 /* drop table t */")
    }

    @Test
    fun `a keyword inside a dollar quoted body is text`() {
        assertRead("select ${'$'}body${'$'} drop table t ${'$'}body${'$'} as sample")
    }

    @Test
    fun `a keyword is matched as a whole word`() {
        assertRead("select * from update_log")
        assertRead("select deleted_at from t")
    }

    @Test
    fun `an unrecognized statement is unknown rather than assumed safe`() {
        assertEquals(StatementKind.UNKNOWN, StatementClassifier.classify("selct * from t"))
        assertEquals(StatementKind.UNKNOWN, StatementClassifier.classify("reindex_helper()"))
        assertEquals(StatementKind.UNKNOWN, StatementClassifier.classify(""))
        assertEquals(StatementKind.UNKNOWN, StatementClassifier.classify("-- nothing here"))
    }

    @Test
    fun `classification ignores case and leading comments`() {
        assertWrite("DELETE FROM T")
        assertWrite("-- clean up\ndelete from t")
        assertRead("/* daily */ SELECT 1")
    }

    private fun assertRead(sql: String) =
        assertEquals(StatementKind.READ, StatementClassifier.classify(sql), sql)

    private fun assertWrite(sql: String) =
        assertEquals(StatementKind.WRITE, StatementClassifier.classify(sql), sql)
}
