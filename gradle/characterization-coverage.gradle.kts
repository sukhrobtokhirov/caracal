/**
 * Keeps the Phase 0 coverage floor attached to characterized classes after the
 * module split. Each module supplies `characterizedClasses` before applying this
 * script; a moved or renamed class still fails instead of disappearing from a
 * module-wide average.
 */
val characterizedClasses = extra["characterizedClasses"] as List<*>
val expectedClasses = characterizedClasses.map(Any?::toString)
val characterizationFloor = 85

val assertCharacterizationCoverage = tasks.register("assertCharacterizationCoverage") {
    dependsOn(tasks.named("koverXmlReport"))
    val report = layout.buildDirectory.file("reports/kover/report.xml")
    val expected = expectedClasses
    val floor = characterizationFloor
    inputs.file(report)
    inputs.property("classes", expected)
    inputs.property("floor", floor)
    outputs.upToDateWhen { true }

    doLast {
        val file = report.get().asFile
        if (!file.isFile) throw GradleException("No Kover report at $file; run koverXmlReport first.")

        val document = javax.xml.parsers.DocumentBuilderFactory.newInstance()
            .also { it.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false) }
            .newDocumentBuilder()
            .parse(file)

        val measured = mutableMapOf<String, Pair<Int, Int>>()
        val classes = document.getElementsByTagName("class")
        for (index in 0 until classes.length) {
            val element = classes.item(index) as org.w3c.dom.Element
            val name = element.getAttribute("name").replace('/', '.')
            val counters = element.getElementsByTagName("counter")
            for (counterIndex in 0 until counters.length) {
                val counter = counters.item(counterIndex) as org.w3c.dom.Element
                if (counter.parentNode !== element || counter.getAttribute("type") != "LINE") continue
                measured[name] = counter.getAttribute("missed").toInt() to counter.getAttribute("covered").toInt()
            }
        }

        val missing = expected.filterNot(measured::containsKey)
        val short = expected.mapNotNull { name ->
            val (missedLines, coveredLines) = measured[name] ?: return@mapNotNull null
            val total = missedLines + coveredLines
            val percent = if (total == 0) 0 else coveredLines * 100 / total
            if (percent < floor) "  $name: $percent% ($coveredLines of $total lines)" else null
        }

        if (missing.isNotEmpty() || short.isNotEmpty()) {
            throw GradleException(
                buildString {
                    appendLine("The Phase 0 characterization floor of $floor% line coverage is not met.")
                    if (short.isNotEmpty()) {
                        appendLine("Below the floor:")
                        short.forEach { appendLine(it) }
                    }
                    if (missing.isNotEmpty()) {
                        appendLine("Not in the coverage report at all — moved, renamed, or removed:")
                        missing.forEach { appendLine("  $it") }
                    }
                },
            )
        }
    }
}

tasks.named("check") { dependsOn(assertCharacterizationCoverage) }
