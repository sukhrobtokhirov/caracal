package dev.dbide.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.dbide.app.InfoState
import dev.dbide.app.RedisFormat
import dev.dbide.app.RedisInfoViewModel
import dev.dbide.core.redis.ServerInfo

/**
 * The `INFO` dashboard.
 *
 * Every card here is optional and the dashboard is built from the ones that have an
 * answer, which is §3.8's requirement and not a convenience. `INFO` is a different
 * document on every Redis version, under every ACL, and in every deployment mode: a
 * user without the `stats` section has no `keyspace_hits`, a replica has a `role` a
 * primary does not, and a restricted user has none of it. A dashboard that drew a card
 * per known field would fill a production screen with zeroes, and a zero here is a
 * claim — "no cache hits" reads as a broken cache on precisely the screen someone opens
 * when they suspect the cache is broken.
 *
 * So: a missing field is a missing card, a refused `INFO` is a banner over a working
 * browser, and the hit rate is not computed at all until there has been a lookup.
 */
@Composable
fun RedisInfoDashboard(model: RedisInfoViewModel, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxSize().semantics { contentDescription = "redis-info" }) {
        PaneHeader(title = "Server") {
            if (model.loading) {
                CircularProgressIndicator(
                    strokeWidth = 1.5.dp,
                    modifier = Modifier.size(10.dp).semantics { contentDescription = "info-busy" },
                )
            }
            ToolButton(
                text = "Refresh",
                onClick = model::refresh,
                description = "info-refresh",
                enabled = !model.loading,
            )
        }
        Hairline()

        when (val state = model.state) {
            InfoState.Idle -> EmptyState(
                title = "Nothing read yet",
                detail = "Open a Redis connection to see its INFO summary.",
                description = "info-idle",
            )

            InfoState.Loading -> EmptyState(
                title = "Reading INFO…",
                detail = "One command, once. This dashboard does not poll.",
                description = "info-loading",
            )

            is InfoState.Failed -> Box(
                modifier = Modifier.fillMaxSize().padding(Space.xl),
                contentAlignment = Alignment.TopCenter,
            ) {
                ErrorBanner(failure = state.failure, modifier = Modifier.fillMaxWidth())
            }

            is InfoState.Ready -> Summary(state.info)
        }
    }
}

@Composable
private fun Summary(info: ServerInfo) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(Space.xl),
        verticalArrangement = Arrangement.spacedBy(Space.lg),
    ) {
        if (info.restricted) {
            RestrictedBanner()
        }

        val cards = info.cards()
        if (cards.isEmpty() && info.databases.isEmpty()) {
            Text(
                text = if (info.restricted) {
                    "This user may not run INFO. Key browsing and the console are unaffected."
                } else {
                    "The server answered INFO with nothing this build recognizes."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.semantics { contentDescription = "info-empty" },
            )
            return@Column
        }

        // Three to a row, laid out by hand rather than with a flow: the cards are a
        // fixed, small set, and a wrapping layout would only be a way to get a
        // different arrangement on every window width.
        cards.chunked(CARDS_PER_ROW).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(Space.lg)) {
                row.forEach { card -> InfoCard(card, modifier = Modifier.weight(1f)) }
                // The last row keeps the column widths of the ones above it rather
                // than stretching two cards across the pane.
                repeat(CARDS_PER_ROW - row.size) { Box(modifier = Modifier.weight(1f)) }
            }
        }

        if (info.databases.isNotEmpty()) Keyspace(info)
    }
}

/** The keyspace section: how many keys each database holds, and how many expire. */
@Composable
private fun Keyspace(info: ServerInfo) {
    Column(verticalArrangement = Arrangement.spacedBy(Space.md)) {
        Text(
            text = "Keyspace",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        info.databases.forEach { database ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(Space.xl),
                modifier = Modifier.semantics { contentDescription = "info-db-${database.index}" },
            ) {
                Text(
                    text = "db${database.index}",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = "${RedisFormat.count(database.keys)} keys",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "${RedisFormat.count(database.expires)} with a TTL",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        // Databases with no keys are not listed by Redis at all, which is worth saying
        // once rather than leaving someone to wonder where db3 went.
        Text(
            text = "Redis lists only databases that hold keys.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
        )
    }
}

@Composable
private fun RestrictedBanner() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.tertiaryContainer)
            .padding(Space.lg)
            .semantics { contentDescription = "info-restricted" },
        horizontalArrangement = Arrangement.spacedBy(Space.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "This user is not allowed to run INFO, so the summary is partial or " +
                "empty. Browsing keys and running commands are unaffected.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onTertiaryContainer,
        )
    }
}

/** One number, and the word for what it is. */
private data class Card(val label: String, val value: String, val detail: String? = null)

@Composable
private fun InfoCard(card: Card, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(MaterialTheme.shapes.medium)
            .border(Sizes.hairline, Dbide.colors.hairline, MaterialTheme.shapes.medium)
            .padding(Space.lg)
            .semantics { contentDescription = "info-card-${card.label.slug()}" },
        verticalArrangement = Arrangement.spacedBy(Space.xs),
    ) {
        Text(
            text = card.label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = card.value,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        card.detail?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * The cards this summary can actually fill in.
 *
 * Built with `?.let`, so a field the server did not report produces no card at all
 * rather than a card saying nothing.
 */
private fun ServerInfo.cards(): List<Card> = buildList {
    version?.let { add(Card("Version", it, mode?.let { mode -> "$mode mode" })) }
    role?.let {
        add(
            Card(
                label = "Role",
                value = it,
                detail = connectedReplicas?.let { count ->
                    "$count ${if (count == 1L) "replica" else "replicas"}"
                },
            ),
        )
    }
    uptime?.let { add(Card("Uptime", RedisFormat.uptime(it))) }
    connectedClients?.let { add(Card("Clients", RedisFormat.count(it))) }
    usedMemoryBytes?.let {
        add(
            Card(
                label = "Memory used",
                value = RedisFormat.bytes(it),
                detail = maxMemoryBytes?.let { max ->
                    "of ${RedisFormat.bytes(max)}" + maxMemoryPolicy?.let { policy -> " · $policy" }
                        .orEmpty()
                } ?: maxMemoryPolicy?.let { policy -> "no limit · $policy" } ?: "no limit",
            ),
        )
    }
    // The guarded division §3.8 asks for: no lookups means no rate, not a rate of nought.
    hitRate?.let { rate ->
        add(
            Card(
                label = "Hit rate",
                value = "%.1f%%".format(rate * 100),
                detail = "${RedisFormat.count(keyspaceHits ?: 0)} hits · " +
                    "${RedisFormat.count(keyspaceMisses ?: 0)} misses",
            ),
        )
    }
    totalCommands?.let { add(Card("Commands", RedisFormat.count(it), "since start")) }
    opsPerSecond?.let { add(Card("Ops/sec", RedisFormat.count(it), "instantaneous")) }
    totalKeys?.takeIf { databases.isNotEmpty() }?.let {
        add(
            Card(
                label = "Keys",
                value = RedisFormat.count(it),
                detail = "across ${databases.size} " +
                    if (databases.size == 1) "database" else "databases",
            ),
        )
    }
}

/** A card's stable name for the accessibility tree and for tests. */
private fun String.slug(): String = lowercase().replace(NON_SLUG, "-")

private val NON_SLUG = Regex("[^a-z0-9]+")

private const val CARDS_PER_ROW = 3
