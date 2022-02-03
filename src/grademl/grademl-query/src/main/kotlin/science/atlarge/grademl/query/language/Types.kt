package science.atlarge.grademl.query.language

enum class Type {
    UNDEFINED,
    BOOLEAN,
    NUMERIC,
    STRING
}

interface Typed {
    val type: Type
}