package science.atlarge.grademl.query.plan.physical

interface OptimizationStrategy {

    fun optimize(physicalQueryPlan: PhysicalQueryPlan): PhysicalQueryPlan?

    fun optimizeOrReturn(physicalQueryPlan: PhysicalQueryPlan): PhysicalQueryPlan {
        return optimize(physicalQueryPlan) ?: physicalQueryPlan
    }

}