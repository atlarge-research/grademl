package science.atlarge.grademl.query.plan.physical

import science.atlarge.grademl.query.analysis.ColumnReplacementPass
import science.atlarge.grademl.query.language.ColumnLiteral
import science.atlarge.grademl.query.language.NamedExpression

object CollapseProjectsOptimization : OptimizationStrategy, PhysicalQueryPlanRewriter {

    override fun optimize(physicalQueryPlan: PhysicalQueryPlan): PhysicalQueryPlan? {
        return physicalQueryPlan.accept(this)
    }

    override fun visit(projectPlan: ProjectPlan): PhysicalQueryPlan? {
        return dropIdentityProjection(projectPlan) ?: mergeNestedProjects(projectPlan) ?: super.visit(projectPlan)
    }

    private fun dropIdentityProjection(projectPlan: ProjectPlan): PhysicalQueryPlan? {
        // Determine if this projection output the exact same columns as its input
        if (projectPlan.namedColumnExpressions.size != projectPlan.input.schema.columns.size) return null
        for (namedExpr in projectPlan.namedColumnExpressions) {
            if (namedExpr.expr !is ColumnLiteral) return null
            if (namedExpr.name != namedExpr.expr.columnPath) return null
        }
        return optimizeOrReturn(projectPlan.input)
    }

    private fun mergeNestedProjects(projectPlan: ProjectPlan): PhysicalQueryPlan? {
        // Find nested projections
        return when (projectPlan.input) {
            is ProjectPlan -> {
                // Collapse two projections by replacing column references in outer projections with inner projections
                val innerProjections = projectPlan.input.namedColumnExpressions.associate { it.name to it.expr }
                val rewrittenProjections = projectPlan.namedColumnExpressions.map { namedExpression ->
                    val rewrittenExpression = ColumnReplacementPass.replaceColumnLiterals(namedExpression.expr) { col ->
                        innerProjections[col.columnPath]!!
                    }
                    NamedExpression(rewrittenExpression, namedExpression.name)
                }
                // Create new projection
                optimizeOrReturn(PhysicalQueryPlanBuilder.project(projectPlan.input.input, rewrittenProjections))
            }
            is SortedAggregatePlan -> {
                // Collapse projection into aggregation if possible
                // TODO: Support merging projection and aggregation
                null
            }
            is SortedTemporalAggregatePlan -> {
                // Collapse projection into aggregation if possible
                // TODO: Support merging projection and aggregation
                null
            }
            else -> null
        }
    }
}