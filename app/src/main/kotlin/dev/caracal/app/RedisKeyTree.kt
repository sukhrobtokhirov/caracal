package dev.caracal.app

import dev.caracal.core.redis.KeyMetadata
import dev.caracal.core.redis.RedisKey
import dev.caracal.core.redis.RedisText

/**
 * One line of the key browser: either a key, or a group several keys share a prefix
 * under.
 *
 * [path] is the group's identity and is used for nothing else. It is built from the
 * segments above this row, so it is stable while a page is on screen and is what the
 * expanded set is keyed on — and it is emphatically *not* a key name. [key] is the
 * only thing a command is ever built from; see [RedisKeyTree] for why that matters.
 */
data class KeyRow(
    val path: String,
    val label: String,
    val depth: Int,
    /** The key this row is, or `null` when the row is a grouping. */
    val key: RedisKey? = null,
    val metadata: KeyMetadata? = null,
    val expandable: Boolean = false,
    val expanded: Boolean = false,
    /** How many keys are under this grouping, including ones nested deeper. */
    val childCount: Int = 0,
)

/**
 * Groups the keys already on screen by a delimiter, so `user:42:profile` reads as
 * `user` → `42` → `profile`.
 *
 * The rule §3.4 states and this enforces: **the tree is a presentation of keys that
 * have already been scanned, and nothing more.** It issues no commands, it knows
 * nothing about keys that were not returned, and expanding a group cannot cause a
 * read. A tree that fetched its own children would be a `SCAN` per node — which is
 * how a browser that replaced `KEYS` with `SCAN` ends up issuing more work than
 * `KEYS` did.
 *
 * That also means the counts are honest about what they count. A group saying "12"
 * means twelve keys *on this page*, not twelve keys on the server, because the
 * server was never asked.
 *
 * A key whose name is not UTF-8 cannot be split into segments, so it is never
 * grouped: it sits at the root under its own display form. Splitting bytes on a
 * delimiter and reassembling them is exactly the operation that would produce a
 * label that no longer names a key.
 */
object RedisKeyTree {

    /**
     * The visible rows for [keys].
     *
     * When [delimiter] is empty or [grouped] is false, this is the flat list — which
     * is the right view for a keyspace that does not use a separator convention, and
     * for the moment someone wants to see exactly what came back.
     */
    fun rows(
        keys: List<KeyMetadata>,
        expanded: Set<String>,
        delimiter: String = ":",
        grouped: Boolean = true,
    ): List<KeyRow> {
        if (!grouped || delimiter.isEmpty()) return keys.map { it.leafRow(depth = 0, path = it.key.pathKey()) }

        val root = Node()
        keys.forEach { metadata ->
            val segments = metadata.key.text?.split(delimiter)?.takeIf { it.size > 1 }
            if (segments == null) {
                // Ungroupable: a name that is not text, or one with no delimiter in it.
                // Either way it is a leaf at the root rather than a group of one.
                root.leaves += metadata
            } else {
                root.add(segments, metadata)
            }
        }

        return buildList {
            root.flatten(prefix = "", depth = 0, expanded = expanded, delimiter = delimiter, into = this)
        }
    }

    /** Every group path in [rows], for an expand-all that opens what is there. */
    fun groupPaths(keys: List<KeyMetadata>, delimiter: String = ":"): Set<String> =
        rows(keys, expanded = emptySet(), delimiter = delimiter)
            .filter { it.expandable }
            .map { it.path }
            .toSet()
            .let { shallow ->
                // One level at a time would need the caller to call this repeatedly, so
                // the whole set is built by opening as it goes.
                var open = shallow
                repeat(MAX_DEPTH) {
                    val next = rows(keys, expanded = open, delimiter = delimiter)
                        .filter { it.expandable }
                        .map { it.path }
                        .toSet()
                    if (next == open) return@let open
                    open = next
                }
                open
            }

    private class Node {
        val children = LinkedHashMap<String, Node>()

        /** Keys that end at this node, in the order they were scanned. */
        val leaves = mutableListOf<KeyMetadata>()

        var total = 0

        fun add(segments: List<String>, metadata: KeyMetadata) {
            total++
            if (segments.size == 1) {
                leaves += metadata
                return
            }
            children.getOrPut(segments.first()) { Node() }.add(segments.drop(1), metadata)
        }

        fun flatten(
            prefix: String,
            depth: Int,
            expanded: Set<String>,
            delimiter: String,
            into: MutableList<KeyRow>,
        ) {
            // Groups first, then the keys that stop here. A group is a heading and a
            // leaf is a row under it, and interleaving them makes both harder to scan.
            children.forEach { (segment, child) ->
                val path = if (prefix.isEmpty()) segment else prefix + PATH_SEPARATOR + segment
                val open = path in expanded
                into += KeyRow(
                    path = path,
                    label = segment,
                    depth = depth,
                    expandable = true,
                    expanded = open,
                    childCount = child.total,
                )
                if (open) child.flatten(path, depth + 1, expanded, delimiter, into)
            }
            leaves.forEach { metadata ->
                into += metadata.leafRow(
                    depth = depth,
                    path = prefix + PATH_SEPARATOR + metadata.key.pathKey(),
                    delimiter = delimiter,
                )
            }
        }
    }

    /**
     * A row's identity within the tree.
     *
     * Lossy for a name that is not text: two binary keys sharing a clipped preview
     * share a path. That is acceptable here and only here — the path decides which
     * rows are open, [KeyRow.key] is what any command is built from, and the worst a
     * collision can do is expand the wrong row.
     */
    private fun RedisKey.pathKey(): String = display.shown()

    private fun KeyMetadata.leafRow(depth: Int, path: String, delimiter: String = "") = KeyRow(
        path = path,
        // The last segment when the key was grouped, the whole name when it was not.
        // Either way [key] carries the bytes: §3.4's rule is that a command is never
        // reconstructed from a display label, and this label cannot be turned back into
        // one even by mistake.
        label = key.text
            ?.takeIf { delimiter.isNotEmpty() }
            ?.substringAfterLast(delimiter)
            ?.takeIf { it.isNotEmpty() }
            ?: key.display.shown(),
        depth = depth,
        key = key,
        metadata = this,
    )

    /** How a value shows itself in a list: its text, or its bytes said to be bytes. */
    private fun RedisText.shown(): String = when (this) {
        is RedisText.Utf8 -> value
        is RedisText.Binary -> "0x$hex"
    }

    /**
     * What joins the segments of a [KeyRow.path].
     *
     * Not the user's delimiter, which would make `a:b` under `a` and `a` under `a:b`
     * the same path. Spelled as an escape rather than written literally because a
     * control character typed into a source file is invisible in every editor and
     * diff that would need to show it.
     */
    private const val PATH_SEPARATOR = "\u0000"

    /** Deep enough for any key convention; a guard against a pathological name. */
    private const val MAX_DEPTH = 12
}
