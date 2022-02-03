package science.atlarge.grademl.query.language

sealed interface ASTNode {
    fun accept(visitor: ASTVisitor)
}

interface ASTVisitor {
    // Expressions
    fun visit(e: BooleanLiteral)
    fun visit(e: NumericLiteral)
    fun visit(e: StringLiteral)
    fun visit(e: ColumnLiteral)
    fun visit(e: UnaryExpression)
    fun visit(e: BinaryExpression)
    fun visit(e: FunctionCallExpression)
    fun visit(e: AbstractExpression)

    // Statements
    fun visit(s: SelectStatement)
    fun visit(s: CreateTableStatement)
    fun visit(s: DeleteTableStatement)
    fun visit(s: CacheTableStatement)
    fun visit(s: DropTableFromCacheStatement)
    fun visit(s: ExplainStatement)
    fun visit(s: StatisticsStatement)
    fun visit(s: ExportStatement)

    // Clauses
    fun visit(c: FromClause)
    fun visit(c: WhereClause)
    fun visit(c: GroupByClause)
    fun visit(c: SelectClause)
    fun visit(c: OrderByClause)
    fun visit(c: LimitClause)
}

interface ExpressionVisitor : ASTVisitor {
    // Provide default implementations for unsupported statements and clauses
    override fun visit(s: SelectStatement) = throw UnsupportedOperationException()
    override fun visit(s: CreateTableStatement) = throw UnsupportedOperationException()
    override fun visit(s: DeleteTableStatement) = throw UnsupportedOperationException()
    override fun visit(s: CacheTableStatement) = throw UnsupportedOperationException()
    override fun visit(s: DropTableFromCacheStatement) = throw UnsupportedOperationException()
    override fun visit(s: ExplainStatement) = throw UnsupportedOperationException()
    override fun visit(s: StatisticsStatement) = throw UnsupportedOperationException()
    override fun visit(s: ExportStatement) = throw UnsupportedOperationException()

    override fun visit(c: FromClause) = throw UnsupportedOperationException()
    override fun visit(c: WhereClause) = throw UnsupportedOperationException()
    override fun visit(c: GroupByClause) = throw UnsupportedOperationException()
    override fun visit(c: SelectClause) = throw UnsupportedOperationException()
    override fun visit(c: OrderByClause) = throw UnsupportedOperationException()
    override fun visit(c: LimitClause) = throw UnsupportedOperationException()
}