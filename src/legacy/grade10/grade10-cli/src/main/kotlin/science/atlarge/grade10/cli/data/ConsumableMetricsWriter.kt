package science.atlarge.grade10.cli.data

import science.atlarge.grade10.Grade10JobResult
import science.atlarge.grade10.cli.util.MetricList
import science.atlarge.grade10.model.resources.Metric
import science.atlarge.grade10.util.TimesliceId
import java.io.File

object ConsumableMetricsWriter {

    const val FILENAME = "consumable-metrics.tsv"

    fun output(
            outFile: File,
            jobResult: Grade10JobResult,
            fromTime: TimesliceId,
            toTimeInclusive: TimesliceId,
            metrics: Iterable<Metric.Consumable>,
            metricList: MetricList
    ) {
        outFile.bufferedWriter().use { writer ->
            writer.appendln("metric\tstart.time.slice\tend.time.slice.inclusive\tobserved.usage")
            if (fromTime > toTimeInclusive) {
                return
            }

            val metricsById = metrics.map { metricList.metricToIdentifier(it) to it }.sortedBy { it.first }
            for ((metricId, metric) in metricsById) {
                fun line(ts: TimesliceId, te: TimesliceId, u: Double) {
                    writer.apply {
                        append(metricId)
                        append('\t')
                        append(jobResult.absoluteTimesliceToRelative(ts).toString())
                        append('\t')
                        append(jobResult.absoluteTimesliceToRelative(te).toString())
                        append('\t')
                        appendln(u.toString())
                    }
                }

                val iter = metric.observedUsage.observationIteratorForTimeslices(fromTime, toTimeInclusive)
                while (iter.hasNext()) {
                    iter.nextObservationPeriod()
                    line(maxOf(iter.periodStartTimeslice, fromTime), minOf(iter.periodEndTimeslice, toTimeInclusive),
                            iter.observation)
                }
            }
        }
    }

}