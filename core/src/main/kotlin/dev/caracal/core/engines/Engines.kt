package dev.caracal.core.engines

import dev.caracal.core.connections.Engine
import dev.caracal.engine.api.DatabaseEngine
import dev.caracal.engine.api.EngineCapabilities
import dev.caracal.engine.api.EngineId
import java.util.ServiceConfigurationError
import java.util.ServiceLoader
import org.slf4j.LoggerFactory

/**
 * The engines this build has, and the one place that knows which they are.
 *
 * It was a `when` and it is now a `ServiceLoader`, which is the change Phase 3 is
 * named after: adding an engine is adding a module with a line in its
 * `META-INF/services/dev.caracal.engine.api.DatabaseEngine`, and no arm anywhere.
 * Nothing above this object had to change for that, which is why it existed as an
 * object rather than as three `when`s spread across the registry, the descriptor
 * mapper and the settings window.
 *
 * The important consequence is upward: **the UI reads capabilities from here and
 * never switches on an engine.** A `when (connection.engine)` in a view is not a
 * shortcut, it is a missing capability — the fix is to add one to
 * [EngineCapabilities] rather than an arm to the view. What the UI is still allowed
 * to key on an engine is artwork: a logo cannot be declared in `:engine-api`,
 * because putting a drawing in the module every other module depends on would drag
 * a UI toolkit in behind it. That lookup lives in the UI with a fallback, so an
 * engine this build has no artwork for still gets a mark rather than a blank.
 */
object Engines {

    private val log = LoggerFactory.getLogger(Engines::class.java)

    /**
     * Every engine on the classpath, in the order they are offered.
     *
     * Lazy because the classpath is not readable from a static initializer in every
     * environment worth supporting, and because a failure here should surface at the
     * first call with a stack trace that names it — not as an
     * `ExceptionInInitializerError` from whichever unrelated line touched this object
     * first.
     */
    val all: List<DatabaseEngine> by lazy { register(discover()) }

    /** The implementation behind a stored connection's engine. */
    fun of(engine: Engine): DatabaseEngine = byId(EngineId(engine.wire))
        ?: throw IllegalStateException("This build has no ${engine.wire} engine on its classpath.")

    /** The implementation with this identifier, or null for one this build does not have. */
    fun byId(id: EngineId): DatabaseEngine? = all.firstOrNull { it.id == id }

    /**
     * Everything the service loader could construct.
     *
     * Iterated one provider at a time and caught individually, because a single
     * engine whose class is missing a dependency must not take the other engines —
     * and with them the whole application — down with it. The one that failed is
     * logged with its class name, which is the only thing anyone can act on.
     */
    private fun discover(): List<DatabaseEngine> {
        val loader = ServiceLoader.load(DatabaseEngine::class.java, Engines::class.java.classLoader)
        val found = mutableListOf<DatabaseEngine>()
        val iterator = loader.iterator()
        while (true) {
            val engine = try {
                if (!iterator.hasNext()) break
                iterator.next()
            } catch (failure: ServiceConfigurationError) {
                log.error("A registered database engine could not be loaded and was skipped.", failure)
                continue
            }
            found += engine
        }
        return found
    }

    /**
     * The engines, ordered and deduplicated.
     *
     * Separate from [discover] so the rules below are testable without a second
     * classpath. Two of them are worth stating:
     *
     * The order is by display name, because the service loader's own order is the
     * order the jars happen to sit in and a connection dialog whose rail reshuffles
     * itself between builds is a dialog nobody can learn. Alphabetical is arbitrary,
     * but it is arbitrary in the same way every time.
     *
     * Two engines claiming one identifier is a packaging mistake — the same engine
     * on the classpath twice, or a fork that forgot to rename itself. The first is
     * kept and the second is refused, rather than letting a saved `postgres`
     * connection dial whichever of them the loader reached first.
     */
    internal fun register(candidates: List<DatabaseEngine>): List<DatabaseEngine> {
        val byId = LinkedHashMap<EngineId, DatabaseEngine>()
        candidates.forEach { engine ->
            val existing = byId[engine.id]
            if (existing != null) {
                log.error(
                    "Two engines claim the identifier '{}': {} is registered and {} was ignored.",
                    engine.id.value,
                    existing::class.java.name,
                    engine::class.java.name,
                )
                return@forEach
            }
            byId[engine.id] = engine
        }
        return byId.values.sortedWith(compareBy({ it.displayName.lowercase() }, { it.id.value }))
    }
}

/** What this engine can do, in the form the UI is allowed to ask. */
val Engine.capabilities: EngineCapabilities get() = Engines.of(this).capabilities

/** Shown to people. `PostgreSQL`, not `postgres`. */
val Engine.displayName: String get() = Engines.of(this).displayName

/** This engine's identifier in the SPI's vocabulary. */
val Engine.id: EngineId get() = Engines.of(this).id
