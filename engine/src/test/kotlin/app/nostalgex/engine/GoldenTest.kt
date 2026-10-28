package app.nostalgex.engine

import org.junit.Assert.assertTrue
import org.junit.Test

class GoldenTest {
    private fun assertPasses(r: GoldenSuite.Result) {
        println(r)
        assertTrue(r.toString(), r.failures.isEmpty() && r.passed > 0)
    }

    @Test fun ratingGolden() = assertPasses(GoldenSuite.rating())
    @Test fun filterGolden() = assertPasses(GoldenSuite.filter())
    @Test fun scheduleParityGolden() = assertPasses(GoldenSuite.scheduleParity())
    @Test fun scheduleDstGolden() = assertPasses(GoldenSuite.scheduleDst())
    @Test fun seasonGolden() = assertPasses(GoldenSuite.season())
    @Test fun filterVectors() = assertPasses(EngineChecks.filterVectors())
    @Test fun scheduleVectors() = assertPasses(EngineChecks.scheduleVectors())
    @Test fun json() = assertPasses(EngineChecks.jsonRoundTrip())
    @Test fun mediaBrowserScan() = assertPasses(ServerChecks.mediaBrowserScan())
    @Test fun plexScan() = assertPasses(ServerChecks.plexScan())
    @Test fun serverHelpers() = assertPasses(ServerChecks.helpers())
}
