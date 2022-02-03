package science.atlarge.grademl.core.attribution

import science.atlarge.grademl.core.math.NonNegativeLeastSquares
import science.atlarge.grademl.core.models.ExecutionModel
import science.atlarge.grademl.core.models.ExecutionPhase
import science.atlarge.grademl.core.models.Metric
import science.atlarge.grademl.core.models.ResourceModel
import science.atlarge.grademl.core.util.TimestampNsArray
import java.nio.file.Path

class BestFitAttributionRuleProvider(
    phases: Iterable<ExecutionPhase>,
    metrics: Iterable<Metric>,
    private val scratchDirectory: Path,
    private val overrideRuleProvider: ResourceAttributionRuleProvider?
) : ResourceAttributionRuleProvider {

    private val phases = phases.toSet()
    private val phasesByType = phases.groupBy { it.type }
    private val orderedPhaseTypes = phasesByType.keys.sortedBy { it.path }
    private val metrics = metrics.toSet()

    private val cachedRules = mutableMapOf<Metric, Map<ExecutionPhase, ResourceAttributionRule>>()

    override fun forPhaseAndMetric(phase: ExecutionPhase, metric: Metric): ResourceAttributionRule? {
        // Sanity check the arguments
        if (phase !in phases || metric !in metrics) return ResourceAttributionRule.None
        // Return from cache if available
        val cachedMetric = cachedRules[metric]
        if (cachedMetric != null) {
            return cachedMetric[phase]
        }
        // Otherwise, fit attribution rules to observed resource usage and phase activity
        computeFitForMetric(metric)
        return cachedRules[metric]!![phase]
    }

    private fun computeFitForMetric(metric: Metric) {
        // Get any overriding rules
        val overridingRules = phases.mapNotNull { phase ->
            val overridingRule = overrideRuleProvider?.forPhaseAndMetric(phase, metric)
            if (overridingRule != null) phase to overridingRule else null
        }.toMap()

        // Create a vector summing the overriding variable demand
        val variableDemandVector = createVariableDemandVector(metric.data.timestamps, overridingRules)
        // Create a vector summing the overriding exact demand
        val exactDemandVector = createExactDemandVector(metric.data.timestamps, overridingRules)

        // Create phase activity matrix and metric usage vector in preparation for NNLS fit
        val activityMatrix = createPhaseActivityMatrix(metric.data.timestamps, overridingRules, variableDemandVector)
        val observationVector = createObservationVector(metric.data.values, exactDemandVector)

        // Perform the NNLS fit
        val bestFit = NonNegativeLeastSquares.fit(activityMatrix, observationVector, scratchDirectory)

        // Translate the obtained coefficients to attribution rules
        val rulePerPhaseType = orderedPhaseTypes.mapIndexed { i, phaseType ->
            require(bestFit[i] >= 0.0) { "NNLS should not return negative coefficients" }
            phaseType to ResourceAttributionRule.Variable(bestFit[i])
        }.toMap()

        // Scale overridden variable demand rules according to NNLS output
        val adjustedOverridingRules = overridingRules.mapValues { (_, rule) ->
            if (rule is ResourceAttributionRule.Variable) {
                ResourceAttributionRule.Variable(rule.demand * bestFit.last())
            } else {
                rule
            }
        }

        cachedRules[metric] = phases.associateWith { phase ->
            adjustedOverridingRules[phase] ?: rulePerPhaseType[phase.type]!!
        }
    }

    private fun createVariableDemandVector(
        timestamps: TimestampNsArray,
        phaseRules: Map<ExecutionPhase, ResourceAttributionRule>
    ): DoubleArray {
        return createPhaseActivityVector(
            timestamps,
            phaseRules.mapNotNull { (phase, rule) ->
                if (rule is ResourceAttributionRule.Variable) phase to rule.demand
                else null
            }.toMap()
        )
    }

    private fun createExactDemandVector(
        timestamps: TimestampNsArray,
        phaseRules: Map<ExecutionPhase, ResourceAttributionRule>
    ): DoubleArray {
        return createPhaseActivityVector(
            timestamps,
            phaseRules.mapNotNull { (phase, rule) ->
                if (rule is ResourceAttributionRule.Exact) phase to rule.demand
                else null
            }.toMap()
        )
    }

    private fun createPhaseActivityMatrix(
        timestamps: TimestampNsArray,
        phaseRules: Map<ExecutionPhase, ResourceAttributionRule>,
        variableDemandVector: DoubleArray
    ): Array<DoubleArray> {
        // Create a phase activity vector per phase type
        val phaseActivityVectors = orderedPhaseTypes.map { phaseType ->
            val unboundPhases = phasesByType[phaseType]!! - phaseRules.keys
            createPhaseActivityVector(timestamps, unboundPhases.associateWith { 1.0 })
        }

        // Transpose the vectors to create the activity matrix array
        return Array(timestamps.size - 1) { timePeriodId ->
            val arr = DoubleArray(phaseActivityVectors.size + 1)
            for (i in phaseActivityVectors.indices) {
                arr[i] = phaseActivityVectors[i][timePeriodId]
            }
            arr[arr.lastIndex] = variableDemandVector[timePeriodId]
            arr
        }
    }

    private fun createPhaseActivityVector(
        timestamps: TimestampNsArray,
        phasesAndFactors: Map<ExecutionPhase, Double>
    ): DoubleArray {
        val sumArray = DoubleArray(timestamps.size - 1)
        for ((phase, factor) in phasesAndFactors) {
            val fromIndex = maxOf(
                0,
                timestamps.binarySearch(phase.startTime).let { if (it < 0) it.inv() - 1 else it }
            )
            val toIndex = minOf(
                sumArray.size,
                timestamps.binarySearch(phase.endTime).let { if (it < 0) it.inv() else it }
            )
            for (i in fromIndex until toIndex) {
                sumArray[i] += if (i == fromIndex || i == toIndex - 1) {
                    val periodStart = timestamps[i]
                    val periodEnd = timestamps[i + 1]
                    val overlap = minOf(periodEnd, phase.endTime) - maxOf(periodStart, phase.startTime)
                    factor * overlap.coerceAtLeast(0) / (periodEnd - periodStart)
                } else {
                    factor
                }
            }
        }
        return sumArray
    }

    private fun createObservationVector(
        metricValues: DoubleArray,
        exactDemandVector: DoubleArray
    ): DoubleArray {
        val outArray = DoubleArray(metricValues.size)
        for (i in outArray.indices) outArray[i] = maxOf(0.0, metricValues[i] - exactDemandVector[i])
        return outArray
    }

    companion object {

        fun from(
            executionModel: ExecutionModel,
            resourceModel: ResourceModel,
            outputPath: Path,
            overrideRuleProvider: ResourceAttributionRuleProvider? = null
        ): BestFitAttributionRuleProvider {
            return BestFitAttributionRuleProvider(
                executionModel.rootPhase.descendants.filter { it.children.isEmpty() },
                resourceModel.rootResource.metricsInTree,
                outputPath.resolve(".scratch"),
                overrideRuleProvider
            )
        }

    }

}