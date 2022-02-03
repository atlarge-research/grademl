package science.atlarge.grade10.cli.data

import science.atlarge.grade10.cli.util.MetricList
import science.atlarge.grade10.model.resources.Metric
import java.io.File

object MetricListWriter {

    const val FILENAME = "metric-list.tsv"

    fun output(outFile: File, metrics: Iterable<Metric>, metricList: MetricList) {
        outFile.bufferedWriter().use { writer ->
            writer.appendln("metric.id\tmetric.type.id\tmetric.path\tcapacity")
            val metricsById = metrics.map { metricList.metricToIdentifier(it) to it }.sortedBy { it.first }
            for ((metricId, metric) in metricsById) {
                writer.apply {
                    append(metricId)
                    append('\t')
                    append(metricList.metricTypeToIdentifier(metric.type))
                    append('\t')
                    append(metric.path.toString())
                    append('\t')
                    appendln((metric as? Metric.Consumable)?.capacity?.toString() ?: "N/A")
                }
            }
        }
    }

}