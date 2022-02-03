package science.atlarge.grademl.query.execution.data

import science.atlarge.grademl.core.GradeMLJob
import science.atlarge.grademl.query.model.Table

object DefaultTables {

    fun create(gradeMLJob: GradeMLJob): Map<String, Table> {
        return mapOf(
            "metrics" to MetricsTable(gradeMLJob),
            "phases" to PhasesTable(gradeMLJob),
            "attributed_metrics" to AttributedMetricsTable(gradeMLJob)
        )
    }

}