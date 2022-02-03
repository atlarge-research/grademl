package science.atlarge.grademl.query.execution.operators

import org.junit.jupiter.api.BeforeAll
import science.atlarge.grademl.query.execution.IndexedSortColumn
import science.atlarge.grademl.query.execution.util.ConcreteRow
import science.atlarge.grademl.query.execution.util.ConcreteRowBuilder
import science.atlarge.grademl.query.execution.util.toConcreteRows
import science.atlarge.grademl.query.execution.util.toTimeSeriesIterator
import science.atlarge.grademl.query.model.Columns
import science.atlarge.grademl.query.model.Table
import science.atlarge.grademl.query.model.TableSchema
import kotlin.test.Test

class SortedTemporalJoinOperatorTests {

    companion object {
        private var smallInput1: Pair<TableSchema, List<List<ConcreteRow>>>? = null
        private var smallInput2: Pair<TableSchema, List<List<ConcreteRow>>>? = null

        @JvmStatic
        @BeforeAll
        fun generateInputData() {
            smallInput1 = ConcreteRowBuilder.build {
                timeSeries(0, 0, 0, 0, 0, 1, 1, 1, 1)
                numericValueColumn("_start_time", 0.0, 1.0, 2.0, 3.0, 4.0, 0.0, 1.5, 3.0, 4.5)
                numericValueColumn("_end_time", 1.0, 2.0, 3.0, 4.0, 5.0, 1.5, 3.0, 4.5, 6.0)
                stringKeyColumn("key1", "a", "a", "a", "a", "a", "b", "b", "b", "b")
                stringKeyColumn("key2", "c", "c", "c", "c", "c", "d", "d", "d", "d")
                booleanValueColumn("value1", true, true, false, false, true, true, false, false, true)
                stringValueColumn("value2", "1/1", "1/2", "1/3", "1/4", "1/5", "2/1", "2/2", "2/3", "2/4")
            }
            smallInput2 = ConcreteRowBuilder.build {
                timeSeries(0, 0, 0, 0, 1, 2, 2, 2, 2)
                numericValueColumn("_start_time", 0.0, 1.0, 3.0, 5.0, 0.0, 0.0, 1.0, 4.5, 5.0)
                numericValueColumn("_end_time", 1.0, 3.0, 5.0, 6.0, 10.0, 1.0, 2.5, 5.0, 5.5)
                booleanValueColumn("v1", true, true, false, false, true, true, false, false, true)
                stringValueColumn("v2", "1/1", "1/2", "1/3", "1/4", "2/1", "3/1", "3/2", "3/3", "3/4")
                stringKeyColumn("k1", "a", "a", "a", "a", "a", "b", "b", "b", "b")
                stringKeyColumn("k2", "c", "c", "c", "c", "d", "d", "d", "d", "d")
            }
        }
    }

    @Test
    fun testSmallCrossJoin() {
        val join = createJoinOperator(
            leftInputData = object : Table {
                override val schema = smallInput1!!.first
                override fun timeSeriesIterator() = smallInput1!!.second.toTimeSeriesIterator(smallInput1!!.first)
            },
            rightInputData = object : Table {
                override val schema = smallInput2!!.first
                override fun timeSeriesIterator() = smallInput2!!.second.toTimeSeriesIterator(smallInput2!!.first)
            },
            leftJoinColumns = emptyList(),
            rightJoinColumns = emptyList()
        )
        val result = join.execute()
        println("=== testSmallCrossJoin result ===")
        result.toConcreteRows().forEachIndexed { tsIndex, ts ->
            println("Time series $tsIndex")
            ts.forEach { row -> println("  $row") }
        }
    }

    @Test
    fun testSmallFirstKeyJoin() {
        val join = createJoinOperator(
            leftInputData = object : Table {
                override val schema = smallInput1!!.first
                override fun timeSeriesIterator() = smallInput1!!.second.toTimeSeriesIterator(smallInput1!!.first)
            },
            rightInputData = object : Table {
                override val schema = smallInput2!!.first
                override fun timeSeriesIterator() = smallInput2!!.second.toTimeSeriesIterator(smallInput2!!.first)
            },
            leftJoinColumns = listOf(IndexedSortColumn(2, true)),
            rightJoinColumns = listOf(IndexedSortColumn(4, true))
        )
        val result = join.execute()
        println("=== testSmallFirstKeyJoin result ===")
        result.toConcreteRows().forEachIndexed { tsIndex, ts ->
            println("Time series $tsIndex")
            ts.forEach { row -> println("  $row") }
        }
    }

    @Test
    fun testSmallSecondKeyJoin() {
        val join = createJoinOperator(
            leftInputData = object : Table {
                override val schema = smallInput1!!.first
                override fun timeSeriesIterator() = smallInput1!!.second.toTimeSeriesIterator(smallInput1!!.first)
            },
            rightInputData = object : Table {
                override val schema = smallInput2!!.first
                override fun timeSeriesIterator() = smallInput2!!.second.toTimeSeriesIterator(smallInput2!!.first)
            },
            leftJoinColumns = listOf(IndexedSortColumn(3, true)),
            rightJoinColumns = listOf(IndexedSortColumn(5, true))
        )
        val result = join.execute()
        println("=== testSmallSecondKeyJoin result ===")
        result.toConcreteRows().forEachIndexed { tsIndex, ts ->
            println("Time series $tsIndex")
            ts.forEach { row -> println("  $row") }
        }
    }

    @Test
    fun testSmallMultiKeyJoin() {
        val join = createJoinOperator(
            leftInputData = object : Table {
                override val schema = smallInput1!!.first
                override fun timeSeriesIterator() = smallInput1!!.second.toTimeSeriesIterator(smallInput1!!.first)
            },
            rightInputData = object : Table {
                override val schema = smallInput2!!.first
                override fun timeSeriesIterator() = smallInput2!!.second.toTimeSeriesIterator(smallInput2!!.first)
            },
            leftJoinColumns = listOf(IndexedSortColumn(2, true), IndexedSortColumn(3, true)),
            rightJoinColumns = listOf(IndexedSortColumn(4, true), IndexedSortColumn(5, true))
        )
        val result = join.execute()
        println("=== testSmallMultiKeyJoin result ===")
        result.toConcreteRows().forEachIndexed { tsIndex, ts ->
            println("Time series $tsIndex")
            ts.forEach { row -> println("  $row") }
        }
    }

    private fun createJoinOperator(
        leftInputData: Table,
        rightInputData: Table,
        leftJoinColumns: List<IndexedSortColumn>,
        rightJoinColumns: List<IndexedSortColumn>
    ): QueryOperator {
        val leftTableScan = LinearTableScanOperator(leftInputData)
        val rightTableScan = LinearTableScanOperator(rightInputData)
        val newSchema = TableSchema(
            Columns.RESERVED_COLUMNS + leftInputData.schema.columns + rightInputData.schema.columns
        )
        val leftOutputColumns = leftInputData.schema.columns.filter { !it.isReserved }
        val rightOutputColumns = rightInputData.schema.columns.filter { !it.isReserved }
        return SortedTemporalJoinOperator(
            leftTableScan,
            rightTableScan,
            newSchema,
            leftJoinColumns,
            rightJoinColumns,
            leftOutputColumns,
            rightOutputColumns
        )
    }

}