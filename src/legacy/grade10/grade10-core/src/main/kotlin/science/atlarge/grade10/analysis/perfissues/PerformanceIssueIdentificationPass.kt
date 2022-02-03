package science.atlarge.grade10.analysis.perfissues

import science.atlarge.grade10.analysis.attribution.ResourceAttributionResult
import science.atlarge.grade10.analysis.bottlenecks.BottleneckIdentificationResult
import science.atlarge.grade10.model.execution.ExecutionModel
import science.atlarge.grade10.model.execution.Phase
import science.atlarge.grade10.model.execution.PhaseType
import science.atlarge.grade10.model.resources.Metric
import science.atlarge.grade10.model.resources.MetricType
import science.atlarge.grade10.model.resources.ResourceModel
import science.atlarge.grade10.simulation.PhaseExecutionSimulationResult
import science.atlarge.grade10.simulation.PhaseExecutionSimulator
import science.atlarge.grade10.util.FractionalTimesliceCount

interface PerformanceIssueIdentificationPass {

    val passName: String

    val description: String

    fun executePass(input: PerformanceIssueIdentificationPassInput): List<PerformanceIssue>

}

abstract class HierarchicalPerformanceIssueIdentificationPass<T> : PerformanceIssueIdentificationPass {

    abstract fun analyzeLeafPhase(leafPhase: Phase): T

    abstract fun combineSubphaseResults(compositePhase: Phase, subphaseResults: Map<Phase, T>): T

    abstract fun extractPerformanceIssues(phase: Phase, results: T): Iterable<PerformanceIssue>

    protected open fun preprocessInput(input: PerformanceIssueIdentificationPassInput) {}

    override fun executePass(input: PerformanceIssueIdentificationPassInput): List<PerformanceIssue> {
        preprocessInput(input)

        val performanceIssues = mutableListOf<PerformanceIssue>()
        fun analyzePhase(phase: Phase): T {
            val result = if (phase.isLeaf) {
                analyzeLeafPhase(phase)
            } else {
                // TODO: Recurse
                val subphaseResults = phase.subphases.values.associate { subphase ->
                    subphase to analyzePhase(subphase)
                }
                combineSubphaseResults(phase, subphaseResults)
            }
            performanceIssues.addAll(extractPerformanceIssues(phase, result))
            return result
        }
        analyzePhase(input.executionModel.rootPhase)
        return performanceIssues
    }

}

class PerformanceIssueIdentificationPassInput(
        val executionModel: ExecutionModel,
        val resourceModel: ResourceModel,
        val resourceAttributionResult: ResourceAttributionResult,
        val bottleneckIdentificationResult: BottleneckIdentificationResult,
        val phaseExecutionSimulator: PhaseExecutionSimulator,
        val simulatedPhaseExecutionResult: PhaseExecutionSimulationResult
)

interface PerformanceIssue {

    val affectedPhases: Set<Phase>?
        get() = null

    val affectedPhaseTypes: Set<PhaseType>?
        get() = null

    val affectedMetrics: Set<Metric>?
        get() = null

    val affectedMetricTypes: Set<MetricType>?
        get() = null

    val estimatedImpact: FractionalTimesliceCount

    val relativeRealImpact: Double

    val relativeSimulatedImpact: Double

    fun shouldDisplayAtPhase(phase: Phase): Boolean {
        val phases = affectedPhases
        if (phases != null && phase in phases) {
            return true
        }

        val phaseTypes = affectedPhaseTypes
        return phaseTypes != null && phase.type in phaseTypes
    }

    fun toDisplayString(): String

}
