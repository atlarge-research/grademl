package science.atlarge.grademl.core.attribution

import science.atlarge.grademl.core.models.ExecutionPhase
import science.atlarge.grademl.core.models.Metric
import science.atlarge.grademl.core.models.MetricData
import science.atlarge.grademl.core.models.sum
import science.atlarge.grademl.core.util.DoubleArrayBuilder
import science.atlarge.grademl.core.util.LongArrayBuilder
import science.atlarge.grademl.core.util.TimestampNs

class ResourceAttributionStep(
    private val leafPhases: Set<ExecutionPhase>,
    private val allPhases: Set<ExecutionPhase>,
    private val metrics: Set<Metric>,
    private val attributionRuleProvider: ResourceAttributionRuleProvider,
    private val resourceDemandEstimates: (Metric) -> ResourceDemandEstimate,
    private val upsampledMetricData: (Metric) -> MetricData,
    private val enableTimeSeriesCompression: Boolean,
    private val enableAttributionResultCaching: Boolean
) {

    private val cachedAttributedUsage = mutableMapOf<ExecutionPhase, MutableMap<Metric, ResourceAttributionResult>>()

    fun attributeMetricToPhase(metric: Metric, phase: ExecutionPhase): ResourceAttributionResult {
        // Return cached outcome if it exists
        cachedAttributedUsage[phase]?.get(metric)?.let { return it }
        // Otherwise, check arguments for validity
        if (metric !in metrics) return NoAttributedData
        if (phase !in allPhases) return NoAttributedData
        // Perform attribution step
        val newAttributedUsage = if (phase in leafPhases) {
            computeAttributedUsageLeaf(metric, phase)
        } else {
            computeAttributedUsageComposite(metric, phase)
        }
        if (enableAttributionResultCaching) {
            cachedAttributedUsage.getOrPut(phase) { mutableMapOf() }[metric] = newAttributedUsage
        }
        return newAttributedUsage
    }

    private fun computeAttributedUsageLeaf(metric: Metric, phase: ExecutionPhase): ResourceAttributionResult {
        // Get attribution rule for phase to determine how to attribute resource usage to the phase
        val attributionRule = attributionRuleProvider.forPhaseAndMetric(phase, metric) ?: ResourceAttributionRule.None
        // Return a flat attributed value of zero if the phase does not use the given resource
        if (attributionRule is ResourceAttributionRule.None) {
            return NoAttributedData
        }
        // Return an empty metric if the phase has no duration
        if (phase.duration == 0L) {
            return AttributedResourceData(
                MetricData(longArrayOf(phase.startTime), doubleArrayOf(), metric.data.maxValue),
                MetricData(longArrayOf(phase.startTime), doubleArrayOf(), metric.data.maxValue)
            )
        }
        // Determine the phase's demand for the resource
        val phaseDemand = when (attributionRule) {
            is ResourceAttributionRule.Exact -> attributionRule.demand
            is ResourceAttributionRule.Variable -> attributionRule.demand
            else -> throw IllegalStateException()
        }
        val isExactDemand = attributionRule is ResourceAttributionRule.Exact
        // Get output of previous steps in the attribution process
        val upsampledMetric = upsampledMetricData(metric)
        val estimatedDemand = resourceDemandEstimates(metric)
        // Perform the resource attribution step
        val (attributedUsage, attributedCapacity) = ResourceAttributionComputation(
            upsampledMetric, estimatedDemand.exactDemandOverTime, estimatedDemand.variableDemandOverTime,
            phaseDemand, isExactDemand, phase.startTime, phase.endTime, enableTimeSeriesCompression
        ).getAttributedUsageAndCapacity()
        return AttributedResourceData(attributedUsage, attributedCapacity)
    }

    private fun computeAttributedUsageComposite(metric: Metric, phase: ExecutionPhase): ResourceAttributionResult {
        // Perform attribution to all sub-phases
        val attributionsToChildren = phase.children.mapNotNull { child ->
            when (val attributedToChild = attributeMetricToPhase(metric, child)) {
                is AttributedResourceData -> attributedToChild
                NoAttributedData -> null
            }
        }
        // If no child uses this resource, skip attribution
        if (attributionsToChildren.isEmpty()) return NoAttributedData

        // Create an empty metric with the start and end time of this phase
        // to make sure the output metric covers the duration of this phase
        val emptyMetric = MetricData(
            longArrayOf(phase.startTime, phase.endTime), doubleArrayOf(0.0), 0.0
        )

        // Sum the metrics of all children
        val allMetricData =
            attributionsToChildren.map { it.metricData.slice(phase.startTime, phase.endTime) } + emptyMetric
        val allCapacityData =
            attributionsToChildren.map { it.availableCapacity.slice(phase.startTime, phase.endTime) } + emptyMetric
        val newMetricData = allMetricData.sum(attributionsToChildren.maxOf { it.metricData.maxValue })
        val newCapacityData = allCapacityData.sum(attributionsToChildren.maxOf { it.availableCapacity.maxValue })

        // Return the final attributed value
        return AttributedResourceData(newMetricData, newCapacityData)
    }

}

private class ResourceAttributionComputation(
    upsampledMetric: MetricData,
    totalExactDemand: MetricData,
    totalVariableDemand: MetricData,
    private val phaseDemand: Double,
    private val isPhaseDemandExact: Boolean,
    private val startTime: TimestampNs,
    private val endTime: TimestampNs,
    private val enableCompression: Boolean
) {

    private val metricIterator = upsampledMetric.iteratorFrom(startTime)
    private val exactDemandIterator = totalExactDemand.iteratorFrom(startTime)
    private val variableDemandIterator = totalVariableDemand.iteratorFrom(startTime)

    private var nextMetricTime = metricIterator.currentEndTime
    private var nextExactTime = exactDemandIterator.currentEndTime
    private var nextVariableTime = variableDemandIterator.currentEndTime

    private val timestamps = LongArrayBuilder()
    private val values = DoubleArrayBuilder()
    private val capacities = DoubleArrayBuilder()
    private val maxValue = if (isPhaseDemandExact) phaseDemand else upsampledMetric.maxValue
    private val maxCapacity = upsampledMetric.maxValue

    fun getAttributedUsageAndCapacity(): Pair<MetricData, MetricData> {
        if (timestamps.size == 0) {
            attributeResource()
        }
        val timestampArray = timestamps.toArray()
        return MetricData(timestampArray, values.toArray(), maxValue) to
                MetricData(timestampArray, capacities.toArray(), maxCapacity)
    }

    private fun attributeResource() {
        // Record the start of the first measurement period
        timestamps.append(startTime)
        // Iterate over change points in demand and metric value until the given end time of the phase
        var lastTime = startTime
        while (lastTime < endTime) {
            // Find the end of the current time period
            val newTime = minOf(endTime, nextMetricTime, nextExactTime, nextVariableTime)
            // Attribute metric usage for the current time period
            val newValue = when {
                phaseDemand == 0.0 -> 0.0
                isPhaseDemandExact -> minOf(
                    metricIterator.currentValue * phaseDemand / exactDemandIterator.currentValue, phaseDemand
                )
                else -> {
                    val leftOver = maxOf(metricIterator.currentValue - exactDemandIterator.currentValue, 0.0)
                    leftOver * phaseDemand / variableDemandIterator.currentValue
                }
            }
            val newCapacity = when {
                phaseDemand == 0.0 -> maxCapacity
                isPhaseDemandExact -> minOf(
                    maxCapacity * phaseDemand / exactDemandIterator.currentValue, phaseDemand
                )
                else -> {
                    val leftOver = maxOf(maxCapacity - exactDemandIterator.currentValue, 0.0)
                    leftOver * phaseDemand / variableDemandIterator.currentValue
                }
            }
            // Create a data point
            emitDataPoint(newTime, newValue, newCapacity)
            // Move to the next time period
            if (metricIterator.currentEndTime == newTime) {
                metricIterator.next()
                nextMetricTime = metricIterator.currentEndTime
            }
            if (exactDemandIterator.currentEndTime == newTime) {
                exactDemandIterator.next()
                nextExactTime = exactDemandIterator.currentEndTime
            }
            if (variableDemandIterator.currentEndTime == newTime) {
                variableDemandIterator.next()
                nextVariableTime = variableDemandIterator.currentEndTime
            }
            lastTime = newTime
        }
    }

    private fun emitDataPoint(timestamp: TimestampNs, value: Double, capacity: Double) {
        if (enableCompression && values.size > 0 && value == values.last() && capacity == capacities.last()) {
            timestamps.replaceLast(timestamp)
        } else {
            timestamps.append(timestamp)
            values.append(value)
            capacities.append(capacity)
        }
    }

}