package science.atlarge.grademl.query.model

class TableSchema(val columns: List<Column>) {

    val keyColumns: List<Column>
        get() = columns.filter { it.isKey }
    val valueColumns: List<Column>
        get() = columns.filter { !it.isKey }

    private val columnsByName = columns.associateBy { it.identifier }
    private val columnIndexByName = columns.withIndex().associate { it.value.identifier to it.index }

    init {
        require(columns.map { it.identifier }.toSet().size == columns.size) {
            "TableSchema cannot have duplicate column names: [${
                columns.map { it.identifier }.groupBy { it }.filter { it.value.size > 1 }.keys.joinToString()
            }]"
        }
    }

    fun column(index: Int) = columns[index]

    fun column(name: String): Column? = columnsByName[name]

    fun indexOfColumn(name: String): Int? = columnIndexByName[name]

    fun indexOfColumn(column: Column): Int? = indexOfColumn(column.identifier)

    fun indexOfStartTimeColumn(): Int? {
        return indexOfColumn(Columns.START_TIME)
    }

    fun indexOfEndTimeColumn(): Int? {
        return indexOfColumn(Columns.END_TIME)
    }

    fun indexOfDurationColumn(): Int? {
        return indexOfColumn(Columns.DURATION)
    }

}