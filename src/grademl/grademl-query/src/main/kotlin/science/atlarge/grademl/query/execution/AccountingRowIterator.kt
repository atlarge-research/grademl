package science.atlarge.grademl.query.execution

import science.atlarge.grademl.query.model.Row
import science.atlarge.grademl.query.model.RowIterator
import science.atlarge.grademl.query.model.TableSchema

abstract class AccountingRowIterator(
    final override val schema: TableSchema
) : RowIterator, Row {

    final override val currentRow: Row
        get() = this
    private var isCurrentRowValid = false
    private var isCurrentRowPushedBack = false

    fun resetInternal() {
        isCurrentRowValid = false
        isCurrentRowPushedBack = false
    }

    var rowsProduced = 0L
        private set

    fun resetRowsProduced() {
        rowsProduced = 0
    }

    protected abstract fun internalLoadNext(): Boolean

    final override fun loadNext(): Boolean {
        if (isCurrentRowPushedBack) {
            isCurrentRowValid = true
            isCurrentRowPushedBack = false
            return true
        }
        isCurrentRowValid = internalLoadNext()
        if (isCurrentRowValid) rowsProduced++
        return isCurrentRowValid
    }

    final override fun pushBack(): Boolean {
        if (!isCurrentRowValid || isCurrentRowPushedBack) return false
        isCurrentRowValid = false
        isCurrentRowPushedBack = true
        return true
    }
}