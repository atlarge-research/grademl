package science.atlarge.grademl.query.execution

import science.atlarge.grademl.query.language.ColumnLiteral

data class SortColumn(val column: ColumnLiteral, val ascending: Boolean)

data class IndexedSortColumn(val columnIndex: Int, val ascending: Boolean)