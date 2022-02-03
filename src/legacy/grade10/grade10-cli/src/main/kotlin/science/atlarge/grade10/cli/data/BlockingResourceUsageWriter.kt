package science.atlarge.grade10.cli.data

import science.atlarge.grade10.Grade10JobResult
import science.atlarge.grade10.cli.util.MetricList
import science.atlarge.grade10.metrics.TimeSlicePeriodList
import science.atlarge.grade10.model.execution.Phase
import science.atlarge.grade10.model.resources.Metric
import science.atlarge.grade10.util.TimesliceId
import java.io.File

object BlockingResourceUsageWriter {

    const val FILENAME = "blocking-resource-usage.tsv"

    fun output(
            outFile: File,
            jobResult: Grade10JobResult,
            phase: Phase,
            metrics: Iterable<Metric.Blocking>,
            metricList: MetricList
    ) {
        val firstTimeSlice = phase.firstTimeslice
        val lastTimeSlice = phase.lastTimeslice
        outFile.bufferedWriter().use { writer ->
            writer.appendln("metric\tstart.time.slice\tend.time.slice.inclusive\tis.blocked")
            if (phase.timesliceDuration <= 0) {
                return
            }

            for (metric in metrics.sortedBy { it.path }) {
                val metricId = metricList.metricToIdentifier(metric)
                fun line(ts: TimesliceId, te: TimesliceId, active: Boolean) {
                    writer.apply {
                        append(metricId)
                        append('\t')
                        append(jobResult.absoluteTimesliceToRelative(ts).toString())
                        append('\t')
                        append(jobResult.absoluteTimesliceToRelative(te).toString())
                        append('\t')
                        appendln(if (active) '0' else '1')
                    }
                }

                val metricUsed = metric in jobResult.resourceAttributionResult.resourceAttribution[phase]
                        .blockingMetrics
                val phaseActivePeriods = if (metricUsed) {
                    TimeSlicePeriodList(phase.timesliceRange) - metric.blockedTimeSlices
                } else {
                    TimeSlicePeriodList(phase.timesliceRange)
                }

                var nextStart = firstTimeSlice
                for (tsRange in phaseActivePeriods) {
                    if (tsRange.first > nextStart && nextStart <= lastTimeSlice) {
                        val end = minOf(tsRange.first - 1, lastTimeSlice)
                        line(nextStart, end, false)
                        nextStart = end + 1
                    }

                    if (tsRange.first == nextStart && nextStart <= lastTimeSlice){
                        val end = minOf(tsRange.endInclusive, lastTimeSlice)
                        line(nextStart, end, true)
                        nextStart = end + 1
                    }
                }
                if (nextStart <= lastTimeSlice) {
                    line(nextStart, lastTimeSlice, false)
                }
            }
        }
    }

}