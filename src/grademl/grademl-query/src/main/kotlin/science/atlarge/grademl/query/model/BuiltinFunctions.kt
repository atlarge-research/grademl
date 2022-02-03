package science.atlarge.grademl.query.model

import science.atlarge.grademl.query.language.*

object BuiltinFunctions : Iterable<FunctionDefinition> {

    override fun iterator() = listOf(
        // Common aggregation functions
        COUNT, COUNT_IF, MIN, MAX, SUM, AVG, WEIGHTED_AVG, MIN_OF, MAX_OF,
        // Data reshaping functions
        FIND_OR_DEFAULT, AS_NUMERIC,
        // Helper functions for traversing hierarchical models
        IS_PARENT_OF, IS_CHILD_OF, IS_ANCESTOR_OF, IS_DESCENDANT_OF, PARENT_OF,
        PATH_PREFIX, PATH_SUFFIX,
        // Virtual functions
        AVG_OVER_TIME
    ).iterator()

    object COUNT : ConcreteFunctionDefinition("COUNT", true, true) {
        override fun checkArgumentCount(argCount: Int) {
            require(argCount == 1) { "$functionName requires 1 argument" }
        }

        override fun checkArgumentTypes(argTypes: List<Type>) {
            // Any type is accepted
        }

        override fun resolveType(argTypes: List<Type>): Type {
            return Type.NUMERIC
        }
    }

    object COUNT_IF : ConcreteFunctionDefinition("COUNT_IF", true, true) {
        override fun checkArgumentCount(argCount: Int) {
            require(argCount == 1) { "$functionName requires 1 argument" }
        }

        override fun checkArgumentTypes(argTypes: List<Type>) {
            require(argTypes[0] == Type.BOOLEAN) { "$functionName requires a BOOLEAN argument" }
        }

        override fun resolveType(argTypes: List<Type>): Type {
            return Type.NUMERIC
        }
    }

    object MIN : ConcreteFunctionDefinition("MIN", true, true) {
        override fun checkArgumentCount(argCount: Int) {
            require(argCount == 1) { "$functionName requires 1 argument" }
        }

        override fun checkArgumentTypes(argTypes: List<Type>) {
            // Any type is accepted
        }

        override fun resolveType(argTypes: List<Type>): Type {
            return argTypes[0]
        }
    }

    object MAX : ConcreteFunctionDefinition("MAX", true, true) {
        override fun checkArgumentCount(argCount: Int) {
            require(argCount == 1) { "$functionName requires 1 argument" }
        }

        override fun checkArgumentTypes(argTypes: List<Type>) {
            // Any type is accepted
        }

        override fun resolveType(argTypes: List<Type>): Type {
            return argTypes[0]
        }
    }

    object SUM : ConcreteFunctionDefinition("SUM", true, true) {
        override fun checkArgumentCount(argCount: Int) {
            require(argCount == 1) { "$functionName requires 1 argument" }
        }

        override fun checkArgumentTypes(argTypes: List<Type>) {
            require(argTypes[0] == Type.NUMERIC) { "$functionName requires a NUMERIC argument" }
        }

        override fun resolveType(argTypes: List<Type>): Type {
            return Type.NUMERIC
        }
    }

    object AVG : ConcreteFunctionDefinition("AVG", true, true) {
        override fun checkArgumentCount(argCount: Int) {
            require(argCount == 1) { "$functionName requires 1 argument" }
        }

        override fun checkArgumentTypes(argTypes: List<Type>) {
            require(argTypes[0] == Type.NUMERIC) { "$functionName requires a NUMERIC argument" }
        }

        override fun resolveType(argTypes: List<Type>): Type {
            return Type.NUMERIC
        }
    }

    object WEIGHTED_AVG : ConcreteFunctionDefinition("WEIGHTED_AVG", true, true) {
        override fun checkArgumentCount(argCount: Int) {
            require(argCount == 2) { "$functionName requires 2 arguments (value, weight)" }
        }

        override fun checkArgumentTypes(argTypes: List<Type>) {
            require(argTypes[0] == Type.NUMERIC) { "First argument of $functionName (\"value\") must be NUMERIC" }
            require(argTypes[1] == Type.NUMERIC) { "Second argument of $functionName (\"weight\") must be NUMERIC" }
        }

        override fun resolveType(argTypes: List<Type>): Type {
            return Type.NUMERIC
        }
    }

    object MIN_OF : ConcreteFunctionDefinition("MIN_OF", false, true) {
        override fun checkArgumentCount(argCount: Int) {
            require(argCount >= 2) { "$functionName requires at least 2 arguments" }
        }

        override fun checkArgumentTypes(argTypes: List<Type>) {
            require(argTypes.all { it == argTypes[0] }) { "All arguments of $functionName must have identical types" }
        }

        override fun resolveType(argTypes: List<Type>): Type {
            return argTypes[0]
        }
    }

    object MAX_OF : ConcreteFunctionDefinition("MAX_OF", false, true) {
        override fun checkArgumentCount(argCount: Int) {
            require(argCount >= 2) { "$functionName requires at least 2 arguments" }
        }

        override fun checkArgumentTypes(argTypes: List<Type>) {
            require(argTypes.all { it == argTypes[0] }) { "All arguments of $functionName must have identical types" }
        }

        override fun resolveType(argTypes: List<Type>): Type {
            return argTypes[0]
        }
    }

    object FIND_OR_DEFAULT : ConcreteFunctionDefinition("FIND_OR_DEFAULT", true, true) {
        override fun checkArgumentCount(argCount: Int) {
            require(argCount == 3) { "$functionName requires 3 arguments (condition, value_if_true, default)" }
        }

        override fun checkArgumentTypes(argTypes: List<Type>) {
            require(argTypes[0] == Type.BOOLEAN) {
                "First argument of $functionName (\"condition\") must be BOOLEAN"
            }
            require(argTypes[1] == argTypes[2]) {
                "Second and third argument of $functionName (\"value_if_true\", \"default\") must have identical types"
            }
        }

        override fun resolveType(argTypes: List<Type>): Type {
            return argTypes[1]
        }
    }

    object AS_NUMERIC : ConcreteFunctionDefinition("AS_NUMERIC", false, true) {
        override fun checkArgumentCount(argCount: Int) {
            require(argCount == 1) { "$functionName requires 1 argument (value)" }
        }

        override fun checkArgumentTypes(argTypes: List<Type>) {
            require(argTypes[0] == Type.BOOLEAN) { "First argument of $functionName (\"value\") must be BOOLEAN" }
        }

        override fun resolveType(argTypes: List<Type>): Type {
            return Type.NUMERIC
        }
    }

    object IS_PARENT_OF : ConcreteFunctionDefinition("IS_PARENT_OF", false, true) {
        override fun checkArgumentCount(argCount: Int) {
            require(argCount == 2) { "$functionName requires 2 arguments (parent, path)" }
        }

        override fun checkArgumentTypes(argTypes: List<Type>) {
            require(argTypes[0] == Type.STRING) { "First argument of $functionName (\"parent\") must be STRING" }
            require(argTypes[1] == Type.STRING) { "Second argument of $functionName (\"path\") must be STRING" }
        }

        override fun resolveType(argTypes: List<Type>): Type {
            return Type.BOOLEAN
        }
    }

    object IS_CHILD_OF : ConcreteFunctionDefinition("IS_CHILD_OF", false, true) {
        override fun checkArgumentCount(argCount: Int) {
            require(argCount == 2) { "$functionName requires 2 arguments (child, path)" }
        }

        override fun checkArgumentTypes(argTypes: List<Type>) {
            require(argTypes[0] == Type.STRING) { "First argument of $functionName (\"child\") must be STRING" }
            require(argTypes[1] == Type.STRING) { "Second argument of $functionName (\"path\") must be STRING" }
        }

        override fun resolveType(argTypes: List<Type>): Type {
            return Type.BOOLEAN
        }
    }

    object IS_ANCESTOR_OF : ConcreteFunctionDefinition("IS_ANCESTOR_OF", false, true) {
        override fun checkArgumentCount(argCount: Int) {
            require(argCount == 2) { "$functionName requires 2 arguments (ancestor, path)" }
        }

        override fun checkArgumentTypes(argTypes: List<Type>) {
            require(argTypes[0] == Type.STRING) { "First argument of $functionName (\"ancestor\") must be STRING" }
            require(argTypes[1] == Type.STRING) { "Second argument of $functionName (\"path\") must be STRING" }
        }

        override fun resolveType(argTypes: List<Type>): Type {
            return Type.BOOLEAN
        }
    }

    object IS_DESCENDANT_OF : ConcreteFunctionDefinition("IS_DESCENDANT_OF", false, true) {
        override fun checkArgumentCount(argCount: Int) {
            require(argCount == 2) { "$functionName requires 2 arguments (descendant, path)" }
        }

        override fun checkArgumentTypes(argTypes: List<Type>) {
            require(argTypes[0] == Type.STRING) { "First argument of $functionName (\"descendant\") must be STRING" }
            require(argTypes[1] == Type.STRING) { "Second argument of $functionName (\"path\") must be STRING" }
        }

        override fun resolveType(argTypes: List<Type>): Type {
            return Type.BOOLEAN
        }
    }

    object PARENT_OF : ConcreteFunctionDefinition("PARENT_OF", false, true) {
        override fun checkArgumentCount(argCount: Int) {
            require(argCount == 1) { "$functionName requires 1 argument (path)" }
        }

        override fun checkArgumentTypes(argTypes: List<Type>) {
            require(argTypes[0] == Type.STRING) { "First argument of $functionName (\"path\") must be STRING" }
        }

        override fun resolveType(argTypes: List<Type>): Type {
            return Type.STRING
        }
    }

    object PATH_PREFIX : ConcreteFunctionDefinition("PATH_PREFIX", false, true) {
        override fun checkArgumentCount(argCount: Int) {
            require(argCount == 2) { "$functionName requires 2 arguments (path, number of components)" }
        }

        override fun checkArgumentTypes(argTypes: List<Type>) {
            require(argTypes[0] == Type.STRING) { "First argument of $functionName (\"path\") must be STRING" }
            require(argTypes[1] == Type.NUMERIC) {
                "First argument of $functionName (\"number of components\") must be NUMERIC"
            }
        }

        override fun resolveType(argTypes: List<Type>): Type {
            return Type.STRING
        }
    }

    object PATH_SUFFIX : ConcreteFunctionDefinition("PATH_SUFFIX", false, true) {
        override fun checkArgumentCount(argCount: Int) {
            require(argCount == 2) { "$functionName requires 2 arguments (path, number of components)" }
        }

        override fun checkArgumentTypes(argTypes: List<Type>) {
            require(argTypes[0] == Type.STRING) { "First argument of $functionName (\"path\") must be STRING" }
            require(argTypes[1] == Type.NUMERIC) {
                "First argument of $functionName (\"number of components\") must be NUMERIC"
            }
        }

        override fun resolveType(argTypes: List<Type>): Type {
            return Type.STRING
        }
    }

    object AVG_OVER_TIME : VirtualFunctionDefinition("AVG_OVER_TIME", true, true) {
        override fun checkArgumentCount(argCount: Int) {
            require(argCount == 1) { "$functionName requires 1 argument (value)" }
        }

        override fun checkArgumentTypes(argTypes: List<Type>) {
            require(argTypes[0] == Type.NUMERIC) { "Argument of $functionName (\"value\") must be NUMERIC" }
        }

        override fun resolveType(argTypes: List<Type>): Type {
            return Type.NUMERIC
        }

        override fun rewrite(arguments: List<Expression>): Expression {
            return FunctionCallExpression(
                "WEIGHTED_AVG",
                listOf(
                    arguments[0],
                    BinaryExpression(
                        ColumnLiteral(Columns.END_TIME.identifier),
                        ColumnLiteral(Columns.START_TIME.identifier),
                        BinaryOp.SUBTRACT
                    )
                )
            )
        }
    }

}
