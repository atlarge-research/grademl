package science.atlarge.grademl.query.execution

import science.atlarge.grademl.query.language.Expression
import science.atlarge.grademl.query.model.Column
import science.atlarge.grademl.query.model.Table

interface FilterableTable : Table {

    val filterableColumns: List<Column>

    fun filteredBy(condition: Expression): FilterableTable

}