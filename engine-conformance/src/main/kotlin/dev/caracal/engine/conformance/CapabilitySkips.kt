package dev.caracal.engine.conformance

import java.lang.reflect.Method
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.AfterAllCallback
import org.junit.jupiter.api.extension.ExtensionContext
import org.junit.jupiter.api.extension.TestWatcher
import org.opentest4j.TestAbortedException

/**
 * Marks a conformance case that a declared capability is allowed to skip.
 *
 * A case without it must run for every engine. A case with it must skip only through
 * [requireCapability], and must say which declaration sent it away.
 */
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.FUNCTION)
annotation class CapabilityGated

/**
 * Skips this case because the engine declared it does not apply, and records why.
 *
 * The important word is *skips*. The obvious way to write a capability guard is
 *
 * ```kotlin
 * if (capabilities.family != EngineFamily.SQL) return
 * ```
 *
 * and that line reports a pass. A suite full of them is green for an engine that ran
 * none of it, which is the same shape of nothing [CapabilitySkips] exists to catch:
 * a check that matched no input and said so by succeeding. Aborting instead puts the
 * case in the run's skipped column, where a person counting them can see it.
 *
 * [reason] should name the declaration, not the engine — `family is KEY_VALUE`, not
 * `Redis has no SQL`. The suite is over engines that do not exist yet.
 */
fun requireCapability(satisfied: Boolean, reason: String) {
    if (!satisfied) throw TestAbortedException("$SKIP_MARKER$reason")
}

internal const val SKIP_MARKER = "declared capability: "

/**
 * Holds the suite to the difference between a skip and a pass.
 *
 * `ArchitectureTest` learned this the hard way: a forbidden-import list that matched
 * nothing passed, and went on passing after the imports it was written for had been
 * renamed. A conformance suite has the same failure available to it twice over — a
 * case that returned early instead of skipping, and a case that stopped being
 * discovered at all — and both of them look exactly like success from the outside.
 *
 * So the extension watches every case in the class and, when the class is done,
 * asserts four things that no individual case can assert about itself:
 *
 * 1. Nothing was skipped except through [requireCapability]. A bare `assumeTrue` is
 *    a skip whose reason nobody wrote down.
 * 2. Nothing was skipped that is not marked [CapabilityGated]. A case that is
 *    supposed to hold for every engine may not opt out for one.
 * 3. Every case declared on the suite was actually seen. A method that was renamed,
 *    lost its `@Test`, or was `@Disabled` is a case that quietly stopped running.
 * 4. Something ran. An engine whose declarations skipped the entire suite has
 *    proved nothing, and the run should say so rather than print a green tick.
 */
class CapabilitySkips : TestWatcher, AfterAllCallback {

    private data class Skip(val method: String, val reason: String)

    private class Record {
        val executed: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())
        val aborted: MutableList<Skip> = Collections.synchronizedList(mutableListOf())
        val disabled: MutableList<Skip> = Collections.synchronizedList(mutableListOf())
    }

    private fun record(context: ExtensionContext): Record =
        records.computeIfAbsent(context.requiredTestClass) { Record() }

    override fun testSuccessful(context: ExtensionContext) {
        record(context).executed += context.requiredTestMethod.name
    }

    override fun testFailed(context: ExtensionContext, cause: Throwable) {
        record(context).executed += context.requiredTestMethod.name
    }

    override fun testAborted(context: ExtensionContext, cause: Throwable) {
        record(context).aborted += Skip(context.requiredTestMethod.name, cause.message.orEmpty())
    }

    override fun testDisabled(context: ExtensionContext, reason: java.util.Optional<String>) {
        record(context).disabled += Skip(context.requiredTestMethod.name, reason.orElse("no reason given"))
    }

    override fun afterAll(context: ExtensionContext) {
        val testClass = context.requiredTestClass
        val record = records.remove(testClass) ?: return
        val complaints = mutableListOf<String>()

        record.aborted.filterNot { it.reason.startsWith(SKIP_MARKER) }.forEach {
            complaints += "${it.method} was skipped without naming a capability: ${it.reason}. " +
                "Skip through requireCapability(), so the reason is a declaration and not a hunch."
        }

        val gated = declaredCases(testClass).filter { it.isAnnotationPresent(CapabilityGated::class.java) }
            .map { it.name }
            .toSet()
        record.aborted.filterNot { it.method in gated }.forEach {
            complaints += "${it.method} skipped itself but is not @CapabilityGated. " +
                "A case every engine must pass may not opt one of them out."
        }

        record.disabled.forEach {
            complaints += "${it.method} is disabled (${it.reason}). A conformance case that is " +
                "switched off is a guarantee that is switched off; delete it or fix it."
        }

        val seen = record.executed + record.aborted.map { it.method } + record.disabled.map { it.method }
        val missing = declaredCases(testClass).map { it.name }.filterNot { it in seen }
        if (missing.isNotEmpty()) {
            complaints += "These cases were never reached: ${missing.sorted()}. A conformance case " +
                "that is not discovered is indistinguishable from one that passes."
        }

        if (record.executed.isEmpty()) {
            complaints += "Every case skipped. This engine's declarations excused it from the whole " +
                "suite, which is not a result — check that its EngineCapabilities are honest."
        }

        if (complaints.isNotEmpty()) {
            throw AssertionError(
                complaints.joinToString(
                    separator = "\n  - ",
                    prefix = "${testClass.simpleName} did not run the conformance suite honestly:\n  - ",
                ),
            )
        }
    }

    /** Every `@Test` method the suite declares, including the ones it inherited. */
    private fun declaredCases(testClass: Class<*>): List<Method> {
        val found = LinkedHashMap<String, Method>()
        var current: Class<*>? = testClass
        while (current != null && current != Any::class.java) {
            current.declaredMethods
                .filter { it.isAnnotationPresent(Test::class.java) }
                .forEach { found.putIfAbsent(it.name, it) }
            current = current.superclass
        }
        return found.values.toList()
    }

    private companion object {
        /**
         * Keyed by test class rather than kept in an [ExtensionContext] store, because
         * the watcher is handed a method's context and the summary is a class's fact,
         * and reaching between the two is more moving parts than a map.
         */
        val records = ConcurrentHashMap<Class<*>, Record>()
    }
}
