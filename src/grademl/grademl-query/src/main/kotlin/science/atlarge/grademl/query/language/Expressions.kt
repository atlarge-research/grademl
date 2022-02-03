package science.atlarge.grademl.query.language

sealed class Expression : ASTNode, Typed {
    override var type = Type.UNDEFINED
    abstract fun clone(): Expression

    abstract val isDeterministic: Boolean
    abstract fun isEquivalent(other: Expression): Boolean
    fun isDeterministicallyEquivalent(other: Expression): Boolean {
        return isDeterministic && other.isDeterministic && isEquivalent(other)
    }
}

data class NamedExpression(val expr: Expression, val name: String) {
    override fun toString() = "$expr AS $name"
}

data class BooleanLiteral(val value: Boolean) : Expression() {
    override fun accept(visitor: ASTVisitor) {
        visitor.visit(this)
    }

    override fun clone() = BooleanLiteral(value).also { it.type = type }

    override val isDeterministic: Boolean
        get() = true

    override fun isEquivalent(other: Expression): Boolean {
        if (other !is BooleanLiteral) return false
        return value == other.value
    }

    override fun toString() = if (value) "TRUE" else "FALSE"

    companion object {
        val TRUE = BooleanLiteral(true)
        val FALSE = BooleanLiteral(false)
    }
}

data class NumericLiteral(val value: Double) : Expression() {
    override fun accept(visitor: ASTVisitor) {
        visitor.visit(this)
    }

    override fun clone() = NumericLiteral(value).also { it.type = type }

    override val isDeterministic: Boolean
        get() = true

    override fun isEquivalent(other: Expression): Boolean {
        if (other !is NumericLiteral) return false
        return value == other.value
    }

    override fun toString() = value.toString()
}

data class StringLiteral(val value: String) : Expression() {
    override fun accept(visitor: ASTVisitor) {
        visitor.visit(this)
    }

    override fun clone() = StringLiteral(value).also { it.type = type }

    override val isDeterministic: Boolean
        get() = true

    override fun isEquivalent(other: Expression): Boolean {
        if (other !is StringLiteral) return false
        return value == other.value
    }

    override fun toString() = "\"$value\""
}

data class ColumnLiteral(val columnPath: String) : Expression() {
    var columnIndex = -1

    override fun accept(visitor: ASTVisitor) {
        visitor.visit(this)
    }

    override fun clone() = ColumnLiteral(columnPath).also {
        it.type = type
        it.columnIndex = columnIndex
    }

    override val isDeterministic: Boolean
        get() = true

    override fun isEquivalent(other: Expression): Boolean {
        if (other !is ColumnLiteral) return false
        return columnPath == other.columnPath
    }

    override fun toString() = columnPath
}

data class UnaryExpression(val expr: Expression, val op: UnaryOp) : Expression() {
    override fun accept(visitor: ASTVisitor) {
        visitor.visit(this)
    }

    override fun clone() = UnaryExpression(expr.clone(), op).also { it.type = type }
    fun copy(newExpr: Expression = expr) = UnaryExpression(newExpr, op).also { it.type = type }

    override val isDeterministic: Boolean
        get() = expr.isDeterministic

    override fun isEquivalent(other: Expression): Boolean {
        if (other !is UnaryExpression) return false
        return op == other.op && expr.isEquivalent(other.expr)
    }

    override fun toString() = "__op_$op($expr)"
}

enum class UnaryOp {
    NOT
}

data class BinaryExpression(val lhs: Expression, val rhs: Expression, val op: BinaryOp) : Expression() {
    override fun accept(visitor: ASTVisitor) {
        visitor.visit(this)
    }

    override fun clone() = BinaryExpression(lhs.clone(), rhs.clone(), op).also { it.type = type }
    fun copy(newLhs: Expression = lhs, newRhs: Expression = rhs) =
        BinaryExpression(newLhs, newRhs, op).also { it.type = type }

    override val isDeterministic: Boolean
        get() = lhs.isDeterministic && rhs.isDeterministic

    override fun isEquivalent(other: Expression): Boolean {
        if (other !is BinaryExpression) return false
        return op == other.op && lhs.isEquivalent(other.lhs) && rhs.isEquivalent(other.rhs)
    }

    override fun toString() = "__op_$op($lhs, $rhs)"
}

enum class BinaryOp {
    ADD,
    SUBTRACT,
    MULTIPLY,
    DIVIDE,
    AND,
    OR,
    EQUAL,
    NOT_EQUAL,
    APPROX_EQUAL,
    NOT_APPROX_EQUAL,
    GREATER,
    GREATER_EQUAL,
    SMALLER,
    SMALLER_EQUAL
}

data class FunctionCallExpression(val functionName: String, val arguments: List<Expression>) : Expression() {
    private var _functionDefinition: FunctionDefinition? = null
    var functionDefinition: FunctionDefinition
        get() = _functionDefinition!!
        set(value) {
            _functionDefinition = value
        }

    override fun accept(visitor: ASTVisitor) {
        visitor.visit(this)
    }

    override fun clone() = FunctionCallExpression(functionName, arguments.map { it.clone() }).also {
        it.type = type
        it._functionDefinition = _functionDefinition
    }

    fun copy(newArguments: List<Expression> = arguments) = FunctionCallExpression(functionName, newArguments).also {
        it.type = type
        it._functionDefinition = _functionDefinition
    }

    override val isDeterministic: Boolean
        get() = functionDefinition.isDeterministic && arguments.all { it.isDeterministic }

    override fun isEquivalent(other: Expression): Boolean {
        if (other !is FunctionCallExpression) return false
        if (functionName != other.functionName) return false
        if (arguments.size != other.arguments.size) return false
        return arguments.indices.all { arguments[it].isEquivalent(other.arguments[it]) }
    }

    override fun toString() = "$functionName(${arguments.joinToString()})"
}

abstract class AbstractExpression(
    val arguments: List<Expression>,
    val originalExpression: Expression
) : Expression() {
    override fun accept(visitor: ASTVisitor) {
        visitor.visit(this)
    }

    abstract override fun clone(): AbstractExpression

    override fun toString() = "__optimized($originalExpression)"
}
