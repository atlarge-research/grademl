package science.atlarge.grademl.query.plan.physical

interface PhysicalQueryPlanVisitor<out T> {

    fun visit(filterPlan: FilterPlan): T
    fun visit(intervalMergingPlan: IntervalMergingPlan): T
    fun visit(linearTableScanPlan: LinearTableScanPlan): T
    fun visit(projectPlan: ProjectPlan): T
    fun visit(sortedAggregatePlan: SortedAggregatePlan): T
    fun visit(sortedTemporalAggregatePlan: SortedTemporalAggregatePlan): T
    fun visit(sortedTemporalJoinPlan: SortedTemporalJoinPlan): T
    fun visit(sortPlan: SortPlan): T

}