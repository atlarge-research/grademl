package science.atlarge.grade10.cli.data

import science.atlarge.grade10.Grade10JobResult
import science.atlarge.grade10.cli.util.MetricList
import science.atlarge.grade10.model.execution.Phase
import science.atlarge.grade10.model.resources.Metric
import java.io.File

object ConsumableResourceUsageWriter {

    const val FILENAME = "consumable-resource-usage.tsv"

    fun output(
            outFile: File,
            jobResult: Grade10JobResult,
            phase: Phase,
            metrics: Iterable<Metric.Consumable>,
            metricList: MetricList
    ) {
        outFile.bufferedWriter().use { writer ->
            writer.appendln("metric\ttime.slice\tusage\tcapacity")
            val metricsById = metrics.associateBy { metricList.metricToIdentifier(it) }
            for ((metricId, metric) in metricsById.entries.sortedBy { it.key }) {
                val iter = jobResult.resourceAttributionResult.resourceAttribution[phase].iterator(metric)
                while (iter.hasNext()) {
                    iter.computeNext()
                    writer.apply {
                        append(metricId)
                        append('\t')
                        append(jobResult.absoluteTimesliceToRelative(iter.timeslice).toString())
                        append('\t')
                        append(iter.attributedUsage.toString())
                        append('\t')
                        appendln(iter.availableCapacity.toString())
                    }
                }
            }
        }
    }

}