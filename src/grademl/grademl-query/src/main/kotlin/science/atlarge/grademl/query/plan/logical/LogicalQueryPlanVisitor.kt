package science.atlarge.grademl.query.plan.logical

interface LogicalQueryPlanVisitor {

    fun visit(aggregatePlan: AggregatePlan)
    fun visit(filterPlan: FilterPlan)
    fun visit(projectPlan: ProjectPlan)
    fun visit(scanTablePlan: ScanTablePlan)
    fun visit(sortPlan: SortPlan)
    fun visit(temporalJoinPlan: TemporalJoinPlan)

}