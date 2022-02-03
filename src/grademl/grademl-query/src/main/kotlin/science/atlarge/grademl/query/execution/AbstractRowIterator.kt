package science.atlarge.grademl.query.execution

import science.atlarge.grademl.query.model.RowIterator
import science.atlarge.grademl.query.model.TableSchema

abstract class AbstractRowIterator(
    override val schema: TableSchema
) : RowIterator {

    protected var isCurrentRowValid = false
    protected var isCurrentRowPushedBack = false

    protected abstract fun internalLoadNext(): Boolean

    override fun loadNext(): Boolean {
        if (isCurrentRowPushedBack) {
            isCurrentRowValid = true
            isCurrentRowPushedBack = false
            return true
        }
        isCurrentRowValid = internalLoadNext()
        return isCurrentRowValid
    }

    override fun pushBack(): Boolean {
        if (!isCurrentRowValid || isCurrentRowPushedBack) return false
        isCurrentRowValid = false
        isCurrentRowPushedBack = true
        return true
    }
}