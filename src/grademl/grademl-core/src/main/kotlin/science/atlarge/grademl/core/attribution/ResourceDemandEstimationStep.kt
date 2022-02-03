package science.atlarge.grademl.core.attribution

import science.atlarge.grademl.core.models.ExecutionPhase
import science.atlarge.grademl.core.models.Metric
import science.atlarge.grademl.core.models.MetricData
import science.atlarge.grademl.core.util.DoubleArrayBuilder
import science.atlarge.grademl.core.util.LongArrayBuilder
import science.atlarge.grademl.core.util.TimestampNs

class ResourceDemandEstimationStep(
    private val metrics: Set<Metric>,
    private val phases: Set<ExecutionPhase>,
    private val attributionRuleProvider: ResourceAttributionRuleProvider,
    private val enableTimeSeriesCompression: Boolean
) {

    private val cachedDemandEstimates = mutableMapOf<Metric, ResourceDemandEstimate>()

    fun estimatedDemandForMetric(metric: Metric): ResourceDemandEstimate? {
        if (metric in cachedDemandEstimates) return cachedDemandEstimates[metric]
        if (metric !in metrics) return null
        val newEstimate = estimateDemand(metric) ?: return null
        cachedDemandEstimates[metric] = newEstimate
        return newEstimate
    }

    private fun estimateDemand(metric: Metric): ResourceDemandEstimate? {
        // No demand estimate possible for metrics without data points
        if (metric.data.timestamps.size < 2) return null
        // Determine the demand per phase
        val (exactDemandPerPhase, variableDemandPerPhase) = getDemandPerPhaseFor(metric)
        // Determine the total demand over time
        val startTime = metric.data.timestamps.first()
        val endTime = metric.data.timestamps.last()
        val exactDemandOverTime = computeDemandOverTime(exactDemandPerPhase, startTime, endTime)
        val variableDemandOverTime = computeDemandOverTime(variableDemandPerPhase, startTime, endTime)
        return ResourceDemandEstimate(metric, exactDemandOverTime, variableDemandOverTime)
    }

    private fun getDemandPerPhaseFor(metric: Metric): Pair<Map<ExecutionPhase, Double>, Map<ExecutionPhase, Double>> {
        val attributionRules = phases.map { it to attributionRuleProvider.forPhaseAndMetric(it, metric) }
        val exactDemandPerPhase = attributionRules.mapNotNull { (phase, rule) ->
            when (rule) {
                is ResourceAttributionRule.Exact -> phase to rule.demand
                else -> null
            }
        }.toMap()
        val variableDemandPerPhase = attributionRules.mapNotNull { (phase, rule) ->
            when (rule) {
                is ResourceAttributionRule.Variable -> phase to rule.demand
                else -> null
            }
        }.toMap()
        return exactDemandPerPhase to variableDemandPerPhase
    }

    private fun computeDemandOverTime(
        demandPerPhase: Map<ExecutionPhase, Double>,
        startTime: TimestampNs,
        endTime: TimestampNs
    ): MetricData {
        // Map the start and end of each phase to changes in demand, i.e. change points, sorted by timestamp
        val changePoints = demandPerPhase.flatMap { (phase, demand) ->
            listOf(phase.startTime to demand, phase.endTime to -demand)
        }.sortedBy { it.first }

        // Build a "metric" representing the total demand at any given point in time
        var currentDemand = 0.0
        var nextChangePoint = 0
        // Process any change points before the given start time
        while (nextChangePoint < changePoints.size && changePoints[nextChangePoint].first <= startTime) {
            currentDemand += changePoints[nextChangePoint].second
            nextChangePoint++
        }
        // Create arrays for the metric data and set the start time
        val timestamps = LongArrayBuilder()
        val demandValues = DoubleArrayBuilder()
        var lastTimestamp = startTime
        timestamps.append(lastTimestamp)
        // Iterate through change points, process them, and emit data points for the demand metric
        while (nextChangePoint < changePoints.size && changePoints[nextChangePoint].first < endTime) {
            // Process all change points with the same timestamp in one go to compute the new demand
            val newTime = changePoints[nextChangePoint].first
            var newDemand = currentDemand
            while (nextChangePoint < changePoints.size && changePoints[nextChangePoint].first == newTime) {
                newDemand += changePoints[nextChangePoint].second
                nextChangePoint++
            }
            // If the demand has changed, add a data point
            if (!enableTimeSeriesCompression || newDemand != currentDemand) {
                timestamps.append(newTime)
                demandValues.append(currentDemand)
                lastTimestamp = newTime
                currentDemand = newDemand
            }
        }
        // Add a final data point if needed
        if (lastTimestamp < endTime) {
            timestamps.append(endTime)
            demandValues.append(currentDemand)
        }

        return MetricData(timestamps.toArray(), demandValues.toArray(), Double.POSITIVE_INFINITY)
    }

}

data class ResourceDemandEstimate(
    val metric: Metric,
    val exactDemandOverTime: MetricData,
    val variableDemandOverTime: MetricData
)