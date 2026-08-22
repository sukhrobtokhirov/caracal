package dev.caracal.app

import dev.caracal.engine.api.KeyMetadata
import dev.caracal.engine.api.KeyType
import dev.caracal.engine.api.MemoryEstimate
import dev.caracal.engine.api.KeyRef
import dev.caracal.engine.api.Ttl
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/**
 * §3.4's prefix grouping, and the rule underneath it.
 *
 * The tree is a *presentation* of keys already returned. It issues nothing, knows
 * nothing about keys that were not scanned, and cannot cause a read — which is what
 * stops a browser that replaced `KEYS` with `SCAN` from issuing a `SCAN` per node
 * and ending up more expensive than what it replaced. That property is structural
 * here: this object takes a list and returns a list, and has nothing to call.
 */
class RedisKeyTreeTest {

    @Test
    fun `keys sharing a prefix group under it`() {
        val rows = RedisKeyTree.rows(keys("user:1:profile", "user:2:profile", "order:9"), expanded = setOf("user"))

        // `order` stays closed and `user` opens onto its two sub-groups, which are
        // themselves closed — expansion is one level at a time, per row.
        assertEquals(
            listOf("user" to 0, "1" to 1, "2" to 1, "order" to 0),
            rows.map { it.label to it.depth },
        )
        assertEquals(2, rows.first().childCount)
    }

    @Test
    fun `a group counts what is on the page, and nothing about the server`() {
        // The count is honest about its own scope: these are keys that came back, not
        // keys that exist. Anything else would need a command the tree must not issue.
        val rows = RedisKeyTree.rows(keys("user:1", "user:2", "user:3"), expanded = emptySet())

        assertEquals(3, rows.single().childCount)
        assertTrue(rows.single().expandable)
    }

    @Test
    fun `a closed group hides its children`() {
        val closed = RedisKeyTree.rows(keys("user:1", "user:2"), expanded = emptySet())
        val open = RedisKeyTree.rows(keys("user:1", "user:2"), expanded = setOf("user"))

        assertEquals(1, closed.size)
        assertFalse(closed.single().expanded)
        assertEquals(3, open.size)
        assertTrue(open.first().expanded)
    }

    @Test
    fun `a key with no delimiter in it is a row rather than a group of one`() {
        val rows = RedisKeyTree.rows(keys("heartbeat"), expanded = emptySet())

        assertEquals(1, rows.size)
        assertFalse(rows.single().expandable)
        assertEquals("heartbeat", rows.single().label)
        assertNotNull(rows.single().key)
    }

    @Test
    fun `a leaf carries the key itself, not just the label it is drawn with`() {
        // §3.4's rule: a command is never reconstructed from a display label. The label
        // here is one segment of a name, so reconstructing from it would fetch the
        // wrong key or none — which is why the bytes travel with the row.
        val rows = RedisKeyTree.rows(keys("user:42:profile"), expanded = setOf("user", pathOf("user", "42")))
        val leaf = rows.last()

        assertEquals("profile", leaf.label)
        assertContentEquals("user:42:profile".toByteArray(), leaf.key?.bytes)
    }

    @Test
    fun `a group row is not a key, so nothing can be run against it`() {
        val group = RedisKeyTree.rows(keys("user:1"), expanded = emptySet()).single()

        assertTrue(group.expandable)
        assertNull(group.key)
        assertNull(group.metadata)
    }

    @Test
    fun `groups keep the order the keys were scanned in`() {
        // SCAN's order is Redis's and means nothing, but re-sorting it would make the
        // list move between refreshes of the same page.
        val rows = RedisKeyTree.rows(keys("zeta:1", "alpha:1", "middle:1"), expanded = emptySet())

        assertEquals(listOf("zeta", "alpha", "middle"), rows.map { it.label })
    }

    @Test
    fun `groups come before the keys that stop at the same level`() {
        val rows = RedisKeyTree.rows(keys("user:1", "user"), expanded = emptySet())

        assertEquals(listOf("user", "user"), rows.map { it.label })
        assertTrue(rows.first().expandable, "the grouping should lead")
        assertNull(rows.first().key)
        assertNotNull(rows.last().key)
    }

    @Test
    fun `a key whose name is not text is never grouped, and shows itself as bytes`() {
        // Splitting bytes on a delimiter and reassembling them is exactly the operation
        // that produces a label naming no key at all.
        val binary = KeyMetadata(
            key = KeyRef(byteArrayOf(0xFF.toByte(), 0x3A, 0xFE.toByte())),
            type = KeyType.STRING,
            ttl = Ttl.Persistent,
            memory = MemoryEstimate.Bytes(48),
        )

        val rows = RedisKeyTree.rows(listOf(binary), expanded = emptySet())

        assertEquals(1, rows.size)
        assertFalse(rows.single().expandable)
        assertTrue(rows.single().label.startsWith("0x"), rows.single().label)
        assertContentEquals(binary.key.bytes, rows.single().key?.bytes)
    }

    @Test
    fun `grouping can be turned off, and then the list is exactly what came back`() {
        val rows = RedisKeyTree.rows(keys("user:1:a", "user:1:b"), expanded = emptySet(), grouped = false)

        assertEquals(listOf("user:1:a", "user:1:b"), rows.map { it.label })
        assertTrue(rows.all { it.depth == 0 && !it.expandable && it.key != null })
    }

    @Test
    fun `the delimiter is configurable`() {
        val rows = RedisKeyTree.rows(keys("user/1", "user/2"), expanded = setOf("user"), delimiter = "/")

        assertEquals(listOf("user", "1", "2"), rows.map { it.label })
    }

    @Test
    fun `a group path is not built from the delimiter, so two shapes cannot collide`() {
        // `a` under `a:b` and `a:b` under `a` would be the same path if the path were
        // joined with the user's delimiter, and expanding one would expand the other.
        val rows = RedisKeyTree.rows(
            keys("a:b:x", "a:b"),
            expanded = setOf("a", pathOf("a", "b")),
        )

        assertEquals(rows.map { it.path }.size, rows.map { it.path }.toSet().size)
    }

    @Test
    fun `expanding everything opens every level, not just the first`() {
        val keys = keys("user:1:profile", "user:1:settings", "order:9:line:1")
        val rows = RedisKeyTree.rows(keys, expanded = RedisKeyTree.groupPaths(keys))

        assertTrue(rows.any { it.label == "profile" }, "a third-level key was still hidden")
        // `order:9:line:1` is three groups deep before its leaf, so the deepest group
        // only appears if expansion kept going past the levels the first pass found.
        assertTrue(rows.any { it.label == "line" && it.depth == 2 }, "a third-level group was still hidden")
        assertTrue(rows.any { it.label == "1" && it.depth == 3 }, "the deepest key was still hidden")
        assertTrue(rows.filter { it.expandable }.all { it.expanded })
    }

    @Test
    fun `an empty page is an empty tree rather than an empty group`() {
        assertTrue(RedisKeyTree.rows(emptyList(), expanded = emptySet()).isEmpty())
    }

    /** How [RedisKeyTree] joins path segments, spelled out so a test can name one. */
    private fun pathOf(vararg segments: String) = segments.joinToString("\u0000")

    @Test
    fun `two keys sharing a clipped path still get different list identities`() {
        // `path` is built from KeyRef.display, which is clipped at elementBytes, so
        // two keys agreeing on their first four kilobytes produce the same path. The
        // browser deduplicates by the key's real bytes, so both rows reach the list —
        // and a LazyColumn handed the same key twice throws instead of drawing them.
        val shared = "k".repeat(5000)
        val rows = RedisKeyTree.rows(keys(shared + "-one", shared + "-two"), expanded = emptySet())

        assertEquals(2, rows.size)
        assertEquals(rows[0].path, rows[1].path, "the fixture no longer produces a path collision")
        assertNotEquals(rows[0].id, rows[1].id)
        assertEquals(rows.size, rows.map { it.id }.distinct().size)
    }

    @Test
    fun `a grouping keeps its path as its identity`() {
        val rows = RedisKeyTree.rows(keys("user:1", "user:2"), expanded = emptySet())

        val group = rows.single()
        assertEquals(group.path, group.id)
    }

    private fun keys(vararg names: String) = names.map { name ->
        KeyMetadata(
            key = KeyRef(name.toByteArray()),
            type = KeyType.STRING,
            ttl = Ttl.Persistent,
            memory = MemoryEstimate.Bytes(64),
        )
    }
}
