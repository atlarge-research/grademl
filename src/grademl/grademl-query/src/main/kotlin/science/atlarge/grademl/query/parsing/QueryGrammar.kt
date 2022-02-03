package science.atlarge.grademl.query.parsing

import com.github.h0tk3y.betterParse.combinators.*
import com.github.h0tk3y.betterParse.grammar.Grammar
import com.github.h0tk3y.betterParse.grammar.parser
import com.github.h0tk3y.betterParse.lexer.RegexToken
import com.github.h0tk3y.betterParse.lexer.literalToken
import com.github.h0tk3y.betterParse.lexer.regexToken
import com.github.h0tk3y.betterParse.parser.Parser
import science.atlarge.grademl.query.language.*

object QueryGrammar : Grammar<List<Statement>>() {
    // Tokens

    // Keywords
    private val `true` by caseInsensitiveRegexToken("true")
    private val `false` by caseInsensitiveRegexToken("false")
    private val not by caseInsensitiveRegexToken("not")
    private val and by caseInsensitiveRegexToken("and")
    private val or by caseInsensitiveRegexToken("or")

    private val `as` by caseInsensitiveRegexToken("as")
    private val by by caseInsensitiveRegexToken("by")
    private val cache by caseInsensitiveRegexToken("cache")
    private val create by caseInsensitiveRegexToken("create")
    private val delete by caseInsensitiveRegexToken("delete")
    private val descending by caseInsensitiveRegexToken("descending")
    private val drop by caseInsensitiveRegexToken("drop")
    private val explain by caseInsensitiveRegexToken("explain")
    private val export by caseInsensitiveRegexToken("export")
    private val first by caseInsensitiveRegexToken("first")
    private val from by caseInsensitiveRegexToken("from")
    private val group by caseInsensitiveRegexToken("group")
    private val join by caseInsensitiveRegexToken("join")
    private val last by caseInsensitiveRegexToken("last")
    private val limit by caseInsensitiveRegexToken("limit")
    private val order by caseInsensitiveRegexToken("order")
    private val select by caseInsensitiveRegexToken("select")
    private val statistics by caseInsensitiveRegexToken("statistics")
    private val table by caseInsensitiveRegexToken("table")
    private val temporal by caseInsensitiveRegexToken("temporal")
    private val where by caseInsensitiveRegexToken("where")

    // Literals
    private val id by regexToken("""[A-Za-z_]\w*""")
    private val positiveInteger by regexToken("""[0-9]+(?![A-Za-z0-9.])""")
    private val number by regexToken("""[+-]?(\d+\.)?\d+([Ee][+-]?\d+)?""")
    private val quotedString by regexToken(""""[^"]*"""")

    // Other tokens
    private val plus by literalToken("+")
    private val minus by literalToken("-")
    private val star by literalToken("*")
    private val slash by literalToken("/")
    private val leftParen by literalToken("(")
    private val rightParen by literalToken(")")
    private val period by literalToken(".")
    private val comma by literalToken(",")
    private val semicolon by literalToken(";")
    private val equal2 by literalToken("==")
    private val equal by literalToken("=")
    private val notEqual by literalToken("!=")
    private val approxEqual by literalToken("~=")
    private val notApproxEqual by literalToken("!~")
    private val greaterEqual by literalToken(">=")
    private val greater by literalToken(">")
    private val smallerEqual by literalToken("<=")
    private val smaller by literalToken("<")
    private val amp2 by literalToken("&&")
    private val pipe2 by literalToken("||")

    // Ignore whitespace
    private val whitespace by regexToken("""\s+""", ignore = true)

    // Grammar

    // Literals
    private val columnLit by (id * optional(-period * id) map { (l, r) ->
        if (r != null) ColumnLiteral("${l.text}.${r.text}") else ColumnLiteral(l.text)
    })

    private val lit by columnLit or
            (positiveInteger or number use { NumericLiteral(text.toDouble()) }) or
            (quotedString use { StringLiteral(text.trim('"')) }) or
            (`true` map { BooleanLiteral.TRUE }) or
            (`false` map { BooleanLiteral.FALSE })

    // Expressions
    private val term: Parser<Expression> by (-leftParen * parser(this::expression) * -rightParen) or
            (-not * parser(this::expression) map { UnaryExpression(it, UnaryOp.NOT) }) or
            (id * -leftParen * separated(parser(this::expression), comma, acceptZero = true)
                    * -rightParen map { (fName, argList) -> FunctionCallExpression(fName.text, argList.terms) }) or
            lit

    private val plusChain by leftAssociative(term, plus or minus) { l, op, r ->
        BinaryExpression(l, r, toBinaryOp(op.text))
    }

    private val timesChain by leftAssociative(plusChain, star or slash) { l, op, r ->
        BinaryExpression(l, r, toBinaryOp(op.text))
    }

    private val comparisonChain by leftAssociative(
        timesChain, equal2 or equal or notEqual or approxEqual or notApproxEqual or
                greaterEqual or greater or smallerEqual or smaller
    ) { l, op, r ->
        BinaryExpression(l, r, toBinaryOp(op.text))
    }

    private val andChain by leftAssociative(comparisonChain, and or amp2) { l, op, r ->
        BinaryExpression(l, r, toBinaryOp(op.text))
    }

    private val expression by leftAssociative(andChain, or or pipe2) { l, op, r ->
        BinaryExpression(l, r, toBinaryOp(op.text))
    }

    private val namedExpression by expression * -`as` * id map { NamedExpression(it.t1, it.t2.text) }

    // Clauses
    private val tableSelect: Parser<TableExpression> by (-leftParen * parser(this::selectStatement) * -rightParen map {
        TableExpression.Query(it)
    }) or (id use { TableExpression.NamedTable(text) })

    private val fromClause by (
            -from * separated(tableSelect * -`as` * id, temporal * join) use {
                FromClause(terms.map { it.t1 }, terms.map { it.t2.text })
            }) or (
            -from * tableSelect map { FromClause(listOf(it), listOf("")) }
            )

    private val whereClause by (-where * expression map { WhereClause(it) })

    private val groupByClause by (-group * -by * separated(columnLit, comma) map { GroupByClause(it.terms) })

    private val selectTerm by (star map { SelectTerm.Wildcard }) or
            (namedExpression map { SelectTerm.Named(it) }) or
            (expression map { SelectTerm.Anonymous(it) })

    private val selectClause by (-select * separated(selectTerm, comma) map { SelectClause(it.terms) })

    private val orderByClause by (-order * -by * separated(columnLit * optional(descending), comma)
            map { columns -> OrderByClause(columns.terms.map { it.t1 }, columns.terms.map { it.t2 == null }) })

    private val limitClause by -limit * ((positiveInteger use { LimitClause(text.toInt()) }) or
            (-first * positiveInteger * optional(-last * positiveInteger) use {
                LimitClause(t1.text.toInt())
            }) or
            (-last * positiveInteger use { LimitClause(null) }))

    // Statements
    private val selectStatement by (fromClause * optional(whereClause) * optional(groupByClause) * selectClause *
            optional(orderByClause) * optional(limitClause)) map { (f, w, g, s, o, l) ->
        SelectStatement(f, w, g, s, o, l)
    }

    private val createTableStatement by -create * -table * id * -equal * selectStatement map { (name, definition) ->
        CreateTableStatement(name.text, definition)
    }

    private val deleteTableStatement by -delete * -table * id use { DeleteTableStatement(text) }

    private val cacheTableStatement by -cache * -table * id use { CacheTableStatement(text) }

    private val dropTableFromCacheStatement by -drop * -table * id * -from * -cache use {
        DropTableFromCacheStatement(text)
    }

    private val explainStatement by -explain * selectStatement map { ExplainStatement(it) }

    private val statisticsStatement by -statistics * selectStatement map { StatisticsStatement(it) }

    private val exportStatement by -export * quotedString * -equal * selectStatement use {
        ExportStatement(t1.text.trim('"'), t2)
    }

    // Putting it all together
    private val topLevelStatement by selectStatement or createTableStatement or deleteTableStatement or
            cacheTableStatement or dropTableFromCacheStatement or explainStatement or statisticsStatement or
            exportStatement

    override val rootParser by oneOrMore((topLevelStatement) * -semicolon)

    private fun toBinaryOp(op: String): BinaryOp {
        return when (op) {
            "+" -> BinaryOp.ADD
            "-" -> BinaryOp.SUBTRACT
            "*" -> BinaryOp.MULTIPLY
            "/" -> BinaryOp.DIVIDE
            "AND" -> BinaryOp.AND
            "OR" -> BinaryOp.OR
            "==" -> BinaryOp.EQUAL
            "=" -> BinaryOp.EQUAL
            "!=" -> BinaryOp.NOT_EQUAL
            "~=" -> BinaryOp.APPROX_EQUAL
            "!~" -> BinaryOp.NOT_APPROX_EQUAL
            ">" -> BinaryOp.GREATER
            ">=" -> BinaryOp.GREATER_EQUAL
            "<" -> BinaryOp.SMALLER
            "<=" -> BinaryOp.SMALLER_EQUAL
            "&&" -> BinaryOp.AND
            "||" -> BinaryOp.OR
            else -> throw IllegalArgumentException()
        }
    }

    private fun caseInsensitiveRegexToken(token: String): RegexToken {
        val lowerCase = token.lowercase()
        val upperCase = token.uppercase()
        val sb = StringBuilder()
        for (i in lowerCase.indices) {
            if (lowerCase[i] == upperCase[i]) sb.append(lowerCase[i])
            else sb.append('[').append(upperCase[i]).append(lowerCase[i]).append(']')
        }
        sb.append("\\b")
        return regexToken(sb.toString())
    }

}