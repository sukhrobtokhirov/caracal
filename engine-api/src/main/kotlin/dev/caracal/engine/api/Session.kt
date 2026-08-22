package dev.caracal.engine.api

import kotlin.reflect.KClass
import kotlin.time.Duration
import kotlinx.coroutines.flow.StateFlow

/**
 * A live connection to one server.
 *
 * The interface is deliberately thin, and the thing it does *not* have is the point:
 * there is no god interface here with twenty capability booleans on it, because such
 * an interface forces every engine to answer questions it has no answer to, and
 * every caller to check a boolean before every call. Instead an engine either
 * provides a facet or it does not, and the caller asks.
 *
 * ```kotlin
 * if (session.capabilities.transactions == TransactionSupport.EXPLICIT) {
 *     TransactionToolbar(session.requireFacet<TransactionFacet>())
 * }
 * ```
 *
 * and never
 *
 * ```kotlin
 * if (session.engineId == EngineId("postgres")) { TransactionToolbar(...) }
 * ```
 *
 * The facets the SPI declares today are the ones backed by behaviour that ships:
 * [QueryFacet] and [CatalogFacet]. The spec's catalogue also names transaction,
 * mutation, explain, key-value, command, and metrics facets. Those are not here yet
 * and not because they were forgotten: four of them describe features the product
 * does not have, and the other two — Redis's key browser and console — have exactly
 * one consumer, which is the UI, and their shape is decided by what that UI needs.
 * Inventing it blind, one phase before the flip that would tell us, is the failure
 * mode §12 is about. They arrive in Phase 2, with a caller.
 */
interface DatabaseSession : AutoCloseable {
    val engineId: EngineId

    val capabilities: EngineCapabilities

    val serverVersion: ServerVersion

    val state: StateFlow<SessionState>

    /** One round trip, and how long it took. The proof a connection still works. */
    suspend fun ping(): Duration

    /** This session's implementation of [type], or null if it does not provide one. */
    fun <F : Any> facet(type: KClass<F>): F?
}

inline fun <reified F : Any> DatabaseSession.facet(): F? = facet(F::class)

inline fun <reified F : Any> DatabaseSession.requireFacet(): F =
    facet(F::class) ?: error("${engineId.value} does not provide ${F::class.simpleName}")

/**
 * Where a session is in its life.
 *
 * [Broken] carries a reason that has already been through redaction and is fit to
 * show. A session that has broken is not the same as one that was closed, and the
 * connection list has to say which — one of them is worth retrying.
 */
sealed interface SessionState {
    data object Connecting : SessionState

    data object Ready : SessionState

    data class Broken(val reason: String) : SessionState

    data object Closed : SessionState
}

/**
 * What the server says it is.
 *
 * [raw] is the server's own string and is what gets shown; [major] and [minor] are
 * parsed where they could be, and null where the server would not say. Redis will
 * not answer `INFO` on some restricted ACLs, and a server that refuses to introduce
 * itself is still a working connection.
 */
data class ServerVersion(
    val raw: String?,
    val major: Int? = null,
    val minor: Int? = null,
) {
    companion object {
        val UNKNOWN = ServerVersion(raw = null)

        /**
         * Parses a leading `major.minor` out of a version string.
         *
         * Tolerant on purpose: PostgreSQL says `17.2`, Redis says `7.2.4`, MariaDB
         * says `11.4.3-MariaDB-ubu2404`, and a build nobody has seen yet will say
         * something else again. Anything that does not begin with digits parses to
         * nulls and keeps its [raw].
         */
        fun parse(raw: String?): ServerVersion {
            if (raw.isNullOrBlank()) return UNKNOWN
            val numbers = raw.trimStart().takeWhile { it.isDigit() || it == '.' }.split('.')
            return ServerVersion(
                raw = raw,
                major = numbers.getOrNull(0)?.toIntOrNull(),
                minor = numbers.getOrNull(1)?.toIntOrNull(),
            )
        }
    }
}
