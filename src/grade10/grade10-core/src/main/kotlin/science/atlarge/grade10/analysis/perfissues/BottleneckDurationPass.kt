package science.atlarge.grade10.analysis.perfissues

import science.atlarge.grade10.analysis.bottlenecks.BottleneckIdentificationResult
import science.atlarge.grade10.analysis.bottlenecks.BottleneckSource
import science.atlarge.grade10.model.execution.Phase
import science.atlarge.grade10.model.execution.PhaseType
import science.atlarge.grade10.model.resources.Metric
import science.atlarge.grade10.model.resources.MetricType
import science.atlarge.grade10.simulation.PhaseDurationMap
import science.atlarge.grade10.simulation.PhaseExecutionSimulator
import science.atlarge.grade10.util.FractionalTimesliceCount

class BottleneckDurationPass(
        private val includeMetricBottlenecks: Boolean = false
) : HierarchicalPerformanceIssueIdentificationPass<BottleneckDurationPhaseResult>() {

    override val passName: String
        get() = "Bottleneck Duration"
    override val description: String
        get() = "Bottleneck Duration"

    private lateinit var bottleneckIdentificationResult: BottleneckIdentificationResult
    private lateinit var phaseExecutionSimulator: PhaseExecutionSimulator

    override fun preprocessInput(input: PerformanceIssueIdentificationPassInput) {
        bottleneckIdentificationResult = input.bottleneckIdentificationResult
        phaseExecutionSimulator = input.phaseExecutionSimulator
    }

    override fun analyzeLeafPhase(leafPhase: Phase): BottleneckDurationPhaseResult {
        val phaseDurationMap = phaseExecutionSimulator.newPhaseDurationMap(leafPhase)
        val metricTypeBottlenecks = bottleneckIdentificationResult.metricTypeBottlenecks[leafPhase]
        val simulatedDuration = leafPhase.timesliceDuration.toDouble() - metricTypeBottlenecks.timeNotBottlenecked
        phaseDurationMap[leafPhase] = simulatedDuration

        val results = hashMapOf<BottleneckSpecification, FractionalTimesliceCount>()
        val cachedDurationMaps = hashMapOf<BottleneckSpecification, PhaseDurationMap>()

        // Compute impact of bottlenecks on different metric types
        metricTypeBottlenecks.metricTypes.forEach { metricType ->
            val newPhaseDurationMap = phaseDurationMap.copy()
            val simulatedDurationWithoutBottleneck = simulatedDuration -
                    metricTypeBottlenecks.timeUniquelyBottleneckedOnMetricType(metricType)
            newPhaseDurationMap[leafPhase] = simulatedDurationWithoutBottleneck

            val spec = BottleneckSpecification(BottleneckSource.MetricTypeBottleneck(metricType), leafPhase.type)
            results[spec] = simulatedDurationWithoutBottleneck
            cachedDurationMaps[spec] = newPhaseDurationMap
        }

        // Compute impact of bottlenecks on different metrics, if requested
        if (includeMetricBottlenecks) {
            val metricBottlenecks = bottleneckIdentificationResult.metricBottlenecks[leafPhase]
            metricBottlenecks.metrics.forEach { metric ->
                val newPhaseDurationMap = phaseDurationMap.copy()
                val simulatedDurationWithoutBottleneck = simulatedDuration -
                        metricBottlenecks.timeUniquelyBottleneckedOnMetric(metric)
                newPhaseDurationMap[leafPhase] = simulatedDurationWithoutBottleneck

                val spec = BottleneckSpecification(BottleneckSource.MetricBottleneck(metric), leafPhase.type)
                results[spec] = simulatedDurationWithoutBottleneck
                cachedDurationMaps[spec] = newPhaseDurationMap
            }
        }

        return BottleneckDurationPhaseResult(leafPhase, simulatedDuration, results, phaseDurationMap,
                cachedDurationMaps)
    }

    override fun combineSubphaseResults(
            compositePhase: Phase,
            subphaseResults: Map<Phase, BottleneckDurationPhaseResult>
    ): BottleneckDurationPhaseResult {
        // Construct the phase duration map for simulation without addressing any bottlenecks
        val phaseDurationMap = phaseExecutionSimulator.newPhaseDurationMap(compositePhase)
        for (subphaseResult in subphaseResults.values) {
            phaseDurationMap.copyRangeFrom(subphaseResult.cachedDurations)
        }
        val simulatedDuration = phaseExecutionSimulator.simulate(compositePhase, phaseDurationMap).totalDuration

        // Construct the phase duration maps for various bottleneck sources
        val cachedDurationMaps = hashMapOf<BottleneckSpecification, PhaseDurationMap>()
        for (subphaseResult in subphaseResults.values) {
            for ((spec, map) in subphaseResult.cachedDurationsWithoutBottleneckSource) {
                val cachedMap = cachedDurationMaps.getOrPut(spec) { phaseDurationMap.copy() }
                cachedMap.copyRangeFrom(map)
            }
        }

        // Simulate different scenarios to compute the impact of solving bottlenecks
        val simulatedDurationWithoutBottleneck = cachedDurationMaps.mapValues { (_, durations) ->
            phaseExecutionSimulator.simulate(compositePhase, durations).totalDuration
        }.toMutableMap()

        return BottleneckDurationPhaseResult(compositePhase, simulatedDuration, simulatedDurationWithoutBottleneck,
                phaseDurationMap, cachedDurationMaps)
    }

    override fun extractPerformanceIssues(phase: Phase, results: BottleneckDurationPhaseResult): Iterable<PerformanceIssue> {
        val duration = results.simulatedDuration
        return results.simulatedDurationWithoutBottleneckSource.map { (spec, durationWithoutBottleneck) ->
            BottleneckDurationPerformanceIssue(phase, spec, duration, durationWithoutBottleneck)
        }
    }

}

data class BottleneckSpecification(val bottleneckSource: BottleneckSource, val targetPhaseType: PhaseType)

class BottleneckDurationPhaseResult(
        val phase: Phase,
        val simulatedDuration: FractionalTimesliceCount,
        val simulatedDurationWithoutBottleneckSource: Map<BottleneckSpecification, FractionalTimesliceCount>,
        val cachedDurations: PhaseDurationMap,
        val cachedDurationsWithoutBottleneckSource: Map<BottleneckSpecification, PhaseDurationMap>
)

class BottleneckDurationPerformanceIssue(
        val aggregatePhase: Phase,
        val bottleneckSpec: BottleneckSpecification,
        val simulatedDuration: FractionalTimesliceCount,
        val simulatedDurationWithoutBottleneck: FractionalTimesliceCount
) : PerformanceIssue {

    override val affectedPhases: Set<Phase> =
            setOf(aggregatePhase) + aggregatePhase.findPhasesForType(bottleneckSpec.targetPhaseType)
    override val affectedMetrics: Set<Metric>? = when (bottleneckSpec.bottleneckSource) {
        is BottleneckSource.MetricBottleneck -> setOf(bottleneckSpec.bottleneckSource.metric)
        is BottleneckSource.MetricTypeBottleneck -> null
        BottleneckSource.NoBottleneck -> null
    }
    override val affectedMetricTypes: Set<MetricType>? = when (bottleneckSpec.bottleneckSource) {
        is BottleneckSource.MetricBottleneck -> null
        is BottleneckSource.MetricTypeBottleneck -> setOf(bottleneckSpec.bottleneckSource.metricType)
        BottleneckSource.NoBottleneck -> null
    }

    override val estimatedImpact: FractionalTimesliceCount
        get() = simulatedDuration - simulatedDurationWithoutBottleneck

    override val relativeRealImpact: Double
        get() = estimatedImpact / aggregatePhase.timesliceDuration

    override val relativeSimulatedImpact: Double
        get() = estimatedImpact / simulatedDuration

    override fun toDisplayString(): String {
        return if (bottleneckSpec.targetPhaseType !== aggregatePhase.type) {
            "Bottlenecks on source ${bottleneckSpec.bottleneckSource} in phases of type " +
                    "\"${bottleneckSpec.targetPhaseType.path}\" for simulated execution of phase " +
                    "\"${aggregatePhase.path}\""
        } else {
            "Bottlenecks on source ${bottleneckSpec.bottleneckSource} for simulated execution of phase " +
                    "\"${aggregatePhase.path}\""
        }
    }

}
