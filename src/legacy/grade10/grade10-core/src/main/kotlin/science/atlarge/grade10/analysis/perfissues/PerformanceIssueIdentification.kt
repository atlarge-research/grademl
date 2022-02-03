package science.atlarge.grade10.analysis.perfissues

import science.atlarge.grade10.analysis.attribution.ResourceAttributionResult
import science.atlarge.grade10.analysis.bottlenecks.BottleneckIdentificationResult
import science.atlarge.grade10.model.execution.ExecutionModel
import science.atlarge.grade10.model.execution.Phase
import science.atlarge.grade10.model.resources.ResourceModel
import science.atlarge.grade10.simulation.*
import science.atlarge.grade10.util.collectTreeNodes

object PerformanceIssueIdentification {

    fun execute(
            executionModel: ExecutionModel,
            resourceModel: ResourceModel,
            performanceIssueIdentificationSettings: PerformanceIssueIdentificationSettings,
            resourceAttributionResult: ResourceAttributionResult,
            bottleneckIdentificationResult: BottleneckIdentificationResult
    ): PerformanceIssueIdentificationResult {
        val phaseExecutionSimulator = performanceIssueIdentificationSettings.phaseExecutionSimulator(executionModel)
        val simulatedPhaseExecutionResult = simulateDurationWithoutOverhead(executionModel,
                phaseExecutionSimulator, bottleneckIdentificationResult)
        val input = PerformanceIssueIdentificationPassInput(
                executionModel,
                resourceModel,
                resourceAttributionResult,
                bottleneckIdentificationResult,
                phaseExecutionSimulator,
                simulatedPhaseExecutionResult
        )
        return PerformanceIssueIdentificationResult(
                performanceIssueIdentificationSettings.issueIdentificationPasses.map {
                    it.passName to it.executePass(input).toList()
                }.toMap(),
                simulatedPhaseExecutionResult
        )
    }

    private fun simulateDurationWithoutOverhead(
            executionModel: ExecutionModel,
            phaseExecutionSimulator: PhaseExecutionSimulator,
            bottleneckIdentificationResult: BottleneckIdentificationResult
    ): PhaseExecutionSimulationResult {
        val phaseDurationMap = phaseExecutionSimulator.newPhaseDurationMap(executionModel.rootPhase)
        val leafPhases = collectTreeNodes(executionModel.rootPhase) { it.subphases.values }.filter(Phase::isLeaf)
        for (leafPhase in leafPhases) {
            val metricTypeBottlenecks = bottleneckIdentificationResult.metricTypeBottlenecks[leafPhase]
            val simulatedDuration = leafPhase.timesliceDuration.toDouble() - metricTypeBottlenecks.timeNotBottlenecked
            phaseDurationMap[leafPhase] = simulatedDuration
        }
        return phaseExecutionSimulator.simulate(executionModel.rootPhase, phaseDurationMap)
    }

}


class PerformanceIssueIdentificationSettings(
        val issueIdentificationPasses: List<PerformanceIssueIdentificationPass>,
        val phaseExecutionSimulator: (ExecutionModel) -> PhaseExecutionSimulator =
                { DefaultPhaseExecutionSimulator(it) }
)

class PerformanceIssueIdentificationResult(
        private val issuesByPass: Map<String, List<PerformanceIssue>>,
        val simulatedPhaseExecutionResult: PhaseExecutionSimulationResult
) {

    val issueIdentificationPasses: Set<String>
        get() = issuesByPass.keys

    val results: Sequence<PerformanceIssue>
        get() = issuesByPass.values.asSequence().flatten()

    operator fun get(passName: String): List<PerformanceIssue> = issuesByPass[passName]
            ?: throw IllegalArgumentException("No result found for pass \"$passName\"")

    fun resultsDisplayedAt(phase: Phase): Sequence<PerformanceIssue> {
        return results.filter { it.shouldDisplayAtPhase(phase) }
    }

}
