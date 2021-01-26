package science.atlarge.grade10.cli.data

import science.atlarge.grade10.Grade10JobResult
import science.atlarge.grade10.cli.util.MetricList
import science.atlarge.grade10.model.resources.Metric
import science.atlarge.grade10.util.TimesliceId
import java.io.File

object BlockingMetricsWriter {

    const val FILENAME = "blocking-metrics.tsv"

    fun output(
            outFile: File,
            jobResult: Grade10JobResult,
            fromTime: TimesliceId,
            toTimeInclusive: TimesliceId,
            metrics: Iterable<Metric.Blocking>,
            metricList: MetricList
    ) {
        outFile.bufferedWriter().use { writer ->
            writer.appendln("metric\tstart.time.slice\tend.time.slice.inclusive\tis.blocked")
            if (fromTime > toTimeInclusive) {
                return
            }

            val metricsById = metrics.map { metricList.metricToIdentifier(it) to it }.sortedBy { it.first }
            for ((metricId, metric) in metricsById) {
                fun line(ts: TimesliceId, te: TimesliceId, b: Boolean) {
                    writer.apply {
                        append(metricId)
                        append('\t')
                        append(jobResult.absoluteTimesliceToRelative(ts).toString())
                        append('\t')
                        append(jobResult.absoluteTimesliceToRelative(te).toString())
                        append('\t')
                        appendln(if (b) '1' else '0')
                    }
                }

                var nextStart = fromTime
                for (tsRange in metric.blockedTimeSlices) {
                    if (tsRange.first > nextStart && nextStart <= toTimeInclusive) {
                        val end = minOf(tsRange.first - 1, toTimeInclusive)
                        line(nextStart, end, false)
                        nextStart = end + 1
                    }

                    if (tsRange.first == nextStart && nextStart <= toTimeInclusive){
                        val end = minOf(tsRange.endInclusive, toTimeInclusive)
                        line(nextStart, end, true)
                        nextStart = end + 1
                    }
                }
                if (nextStart <= toTimeInclusive) {
                    line(nextStart, toTimeInclusive, false)
                }
            }
        }
    }

}