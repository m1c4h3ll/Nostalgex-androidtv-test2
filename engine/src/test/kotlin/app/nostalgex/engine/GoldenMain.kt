package app.nostalgex.engine

import kotlin.system.exitProcess

/** Runs the golden suite without JUnit: `kotlin -cp ... app.nostalgex.engine.GoldenMainKt`. */
fun main() {
    val results = GoldenSuite.all() + EngineChecks.all() + ServerChecks.all()
    results.forEach { println(it) }
    if (results.any { it.failures.isNotEmpty() }) exitProcess(1)
}
