package science.atlarge.grademl.query.plan.physical

interface PhysicalQueryPlanRewriter : PhysicalQueryPlanVisitor<PhysicalQueryPlan?> {

    override fun visit(filterPlan: FilterPlan): PhysicalQueryPlan? {
        val inputRewritten = filterPlan.input.accept(this) ?: return null
        return PhysicalQueryPlanBuilder.filter(inputRewritten, filterPlan.filterCondition)
    }

    override fun visit(intervalMergingPlan: IntervalMergingPlan): PhysicalQueryPlan? {
        val inputRewritten = intervalMergingPlan.input.accept(this) ?: return null
        return PhysicalQueryPlanBuilder.intervalMerging(inputRewritten)
    }

    override fun visit(linearTableScanPlan: LinearTableScanPlan): PhysicalQueryPlan? {
        return null
    }

    override fun visit(projectPlan: ProjectPlan): PhysicalQueryPlan? {
        val inputRewritten = projectPlan.input.accept(this) ?: return null
        return PhysicalQueryPlanBuilder.project(inputRewritten, projectPlan.namedColumnExpressions)
    }

    override fun visit(sortedAggregatePlan: SortedAggregatePlan): PhysicalQueryPlan? {
        val inputRewritten = sortedAggregatePlan.input.accept(this) ?: return null
        return PhysicalQueryPlanBuilder.sortedAggregate(
            inputRewritten,
            sortedAggregatePlan.groupByColumns,
            sortedAggregatePlan.namedColumnExpressions
        )
    }

    override fun visit(sortedTemporalAggregatePlan: SortedTemporalAggregatePlan): PhysicalQueryPlan? {
        val inputRewritten = sortedTemporalAggregatePlan.input.accept(this) ?: return null
        return PhysicalQueryPlanBuilder.sortedTemporalAggregate(
            inputRewritten,
            sortedTemporalAggregatePlan.groupByColumns,
            sortedTemporalAggregatePlan.namedColumnExpressions
        )
    }

    override fun visit(sortedTemporalJoinPlan: SortedTemporalJoinPlan): PhysicalQueryPlan? {
        val leftRewritten = sortedTemporalJoinPlan.leftInput.accept(this)
        val rightRewritten = sortedTemporalJoinPlan.rightInput.accept(this)
        if (leftRewritten == null && rightRewritten == null) return null
        return PhysicalQueryPlanBuilder.sortedTemporalJoin(
            leftRewritten ?: sortedTemporalJoinPlan.leftInput,
            rightRewritten ?: sortedTemporalJoinPlan.rightInput,
            sortedTemporalJoinPlan.leftJoinColumns,
            sortedTemporalJoinPlan.rightJoinColumns,
            sortedTemporalJoinPlan.leftDropColumns,
            sortedTemporalJoinPlan.rightDropColumns
        )
    }

    override fun visit(sortPlan: SortPlan): PhysicalQueryPlan? {
        val inputRewritten = sortPlan.input.accept(this) ?: return null
        return PhysicalQueryPlanBuilder.sort(inputRewritten, sortPlan.sortByColumns)
    }
}