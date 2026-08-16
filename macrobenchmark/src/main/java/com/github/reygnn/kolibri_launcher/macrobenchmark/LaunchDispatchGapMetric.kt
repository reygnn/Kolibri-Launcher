package com.github.reygnn.kolibri_launcher.macrobenchmark

import androidx.benchmark.macro.ExperimentalMetricApi
import androidx.benchmark.macro.Metric
import androidx.benchmark.macro.TraceMetric
import androidx.benchmark.traceprocessor.TraceProcessor

/**
 * Custom Macrobenchmark metric: the **TAP->DISPATCH gap** — the latency between
 * the launcher's `app_launch_tap` slice ending and its `app_launch_dispatch`
 * slice starting. That gap *is* the ViewModel+Channel launch hop (the message
 * sits in the Channel until the Main loop drains it), and it is the value that
 * can regress invisibly in a diff: a `suspend` call sneaking into the dispatch
 * path would add a frame here with no catch-clause or signature change to
 * review. `TraceSectionMetric` cannot see it — it measures a single slice's
 * duration, not the gap *between* two slices — so this queries the trace directly
 * via Perfetto SQL, exactly as the manual analysis in APP-START-PERFORMANCE.md did.
 *
 * Emitted per iteration; the framework aggregates to min/median/max + per-run
 * data, and `verifyLaunchBenchmark` gates on the worst-case (max).
 */
@OptIn(ExperimentalMetricApi::class)
class LaunchDispatchGapMetric : TraceMetric() {

    override fun getMeasurements(
        captureInfo: Metric.CaptureInfo,
        traceSession: TraceProcessor.Session,
    ): List<Metric.Measurement> {
        // One tap + one dispatch per iteration, so MIN collapses to that single
        // pair. Returns NULL (→ no measurement) if the pair is absent, e.g. the
        // launch was not captured in this iteration's trace.
        val query = """
            WITH tap AS (SELECT ts + dur AS tend FROM slice WHERE name = 'app_launch_tap'),
                 dis AS (SELECT ts AS dts FROM slice WHERE name = 'app_launch_dispatch')
            SELECT MIN((SELECT MIN(dts) FROM dis WHERE dts >= t.tend) - t.tend) AS gap_ns
            FROM tap t
            WHERE (SELECT MIN(dts) FROM dis WHERE dts >= t.tend) IS NOT NULL
        """.trimIndent()

        val gapNs = traceSession.query(query)
            .firstOrNull()
            ?.nullableLong("gap_ns")
            ?: return emptyList()

        return listOf(
            Metric.Measurement(name = "launchDispatchGapMs", data = gapNs / 1_000_000.0),
        )
    }
}
