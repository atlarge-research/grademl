package science.atlarge.grademl.query.execution

import science.atlarge.grademl.query.language.AbstractExpression
import science.atlarge.grademl.query.language.Expression

abstract class ExpressionImplementation(
    arguments: List<Expression>,
    originalExpression: Expression
) : AbstractExpression(arguments, originalExpression) {

    abstract fun toPhysicalExpression(arguments: List<PhysicalExpression>): PhysicalExpression

}