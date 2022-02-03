package science.atlarge.grademl.query.model

import science.atlarge.grademl.query.language.Type

object Columns {

    val START_TIME = Column("_start_time", Type.NUMERIC, false)
    val END_TIME = Column("_end_time", Type.NUMERIC, false)
    val DURATION = Column("_duration", Type.NUMERIC, false)

    val RESERVED_COLUMNS = listOf(START_TIME, END_TIME, DURATION)
    val RESERVED_COLUMN_NAMES = RESERVED_COLUMNS.map { it.identifier }.toSet()

    const val INDEX_START_TIME = 0
    const val INDEX_END_TIME = 1
    const val INDEX_DURATION = 2
    const val INDEX_NOT_RESERVED = 3

}