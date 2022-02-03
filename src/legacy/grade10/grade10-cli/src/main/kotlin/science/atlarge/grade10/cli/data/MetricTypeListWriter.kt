package science.atlarge.grade10.cli.data

import science.atlarge.grade10.cli.util.MetricList
import science.atlarge.grade10.model.resources.MetricClass
import science.atlarge.grade10.model.resources.MetricType
import java.io.File

object MetricTypeListWriter {

    const val FILENAME = "metric-type-list.tsv"

    fun output(outFile: File, metricTypes: Iterable<MetricType>, metricList: MetricList) {
        outFile.bufferedWriter().use { writer ->
            writer.appendln("metric.type.id\tmetric.type.path\tmetric.class")
            val metricTypesById = metricTypes.map { metricList.metricTypeToIdentifier(it) to it }.sortedBy { it.first }
            for ((metricTypeId, metricType) in metricTypesById) {
                writer.apply {
                    append(metricTypeId)
                    append('\t')
                    append(metricType.path.toString())
                    append('\t')
                    appendln(when (metricType.metricClass) {
                        MetricClass.CONSUMABLE -> 'C'
                        MetricClass.BLOCKING -> 'B'
                    })
                }
            }
        }
    }

}