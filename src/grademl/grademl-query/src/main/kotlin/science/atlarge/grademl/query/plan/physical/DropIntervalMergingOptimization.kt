package science.atlarge.grademl.query.plan.physical

object DropIntervalMergingOptimization : OptimizationStrategy, PhysicalQueryPlanRewriter {

    override fun optimize(physicalQueryPlan: PhysicalQueryPlan): PhysicalQueryPlan? {
        return physicalQueryPlan.accept(this)
    }

    override fun visit(intervalMergingPlan: IntervalMergingPlan): PhysicalQueryPlan {
        return optimizeOrReturn(intervalMergingPlan.input)
    }

}