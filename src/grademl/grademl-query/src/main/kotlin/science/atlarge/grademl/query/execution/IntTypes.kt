package science.atlarge.grademl.query.execution

import science.atlarge.grademl.query.language.Type

object IntTypes {
    const val TYPE_BOOLEAN = 0
    const val TYPE_NUMERIC = 1
    const val TYPE_STRING = 2

    fun Type.toInt(): Int = when (this) {
        Type.UNDEFINED -> throw IllegalArgumentException("Cannot read UNDEFINED column")
        Type.BOOLEAN -> TYPE_BOOLEAN
        Type.NUMERIC -> TYPE_NUMERIC
        Type.STRING -> TYPE_STRING
    }
}