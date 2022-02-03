package science.atlarge.grademl.core.attribution

import science.atlarge.grademl.core.models.Metric
import science.atlarge.grademl.core.models.MetricData
import science.atlarge.grademl.core.util.DoubleArrayBuilder
import science.atlarge.grademl.core.util.LongArrayBuilder
import science.atlarge.grademl.core.util.TimestampNs

class ResourceUpsamplingStep(
    private val metrics: Set<Metric>,
    private val resourceDemandEstimates: (Metric) -> ResourceDemandEstimate,
    private val enableTimeSeriesCompression: Boolean
) {

    private val cachedUpsampledMetrics = mutableMapOf<Metric, MetricData>()

    fun upsampleMetric(metric: Metric): MetricData? {
        if (metric in cachedUpsampledMetrics) return cachedUpsampledMetrics[metric]
        if (metric !in metrics) return null
        val resourceDemandEstimate = resourceDemandEstimates(metric)
        val newUpsampledMetric = MetricUpsampler(
            metric.data,
            resourceDemandEstimate.exactDemandOverTime,
            resourceDemandEstimate.variableDemandOverTime,
            enableTimeSeriesCompression
        ).getUpsampledMetric()
        cachedUpsampledMetrics[metric] = newUpsampledMetric
        return newUpsampledMetric
    }

}

private class MetricUpsampler(
    private val observedUsage: MetricData,
    private val exactDemand: MetricData,
    private val variableDemand: MetricData,
    private val enableTimeSeriesCompression: Boolean
) {

    private var currentExactDemand = 0.0
    private var currentVariableDemand = 0.0

    private var exactDemandIndex = 0
    private var variableDemandIndex = 0

    private var nextExactDemandChange = Long.MIN_VALUE
    private var nextVariableDemandChange = Long.MIN_VALUE

    private val timestamps = LongArrayBuilder()
    private val values = DoubleArrayBuilder()

    init {
        // Sanity check the input
        require(observedUsage.values.isNotEmpty()) { "Input metric must have at least one data point" }
        require(exactDemand.timestamps.isNotEmpty()) { "Exact demand must have at least one data points" }
        require(variableDemand.timestamps.isNotEmpty()) { "Variable demand must have at least one data points" }
        require(
            observedUsage.timestamps.first() == exactDemand.timestamps.first() &&
                    observedUsage.timestamps.last() == exactDemand.timestamps.last() &&
                    observedUsage.timestamps.first() == variableDemand.timestamps.first() &&
                    observedUsage.timestamps.last() == variableDemand.timestamps.last()
        ) {
            "Demand time series must cover the same period of time as the input metric"
        }
    }

    fun getUpsampledMetric(): MetricData {
        if (timestamps.size == 0) {
            upsampleMetric()
        }
        return MetricData(timestamps.toArray(), values.toArray(), observedUsage.maxValue)
    }

    private fun upsampleMetric() {
        // Record the start of the first measurement period
        timestamps.append(observedUsage.timestamps.first())
        // Get the initial demand
        updateExactDemand()
        updateVariableDemand()
        // Iterate over metric observation periods, split periods with intermittent changes in demand
        for (periodIndex in observedUsage.values.indices) {
            val periodEnd = observedUsage.timestamps[periodIndex + 1]
            val periodValue = observedUsage.values[periodIndex]
            // Only upsample if there are changes in demand during this measurement period
            if (nextExactDemandChange >= periodEnd && nextVariableDemandChange >= periodEnd) {
                emitDataPoint(periodEnd, periodValue)
            } else {
                upsampleDataPoint(observedUsage.timestamps[periodIndex], periodEnd, periodValue)
            }
            // Corner case: process any changes in demand on the border between measurement periods
            while (nextExactDemandChange == periodEnd) updateExactDemand()
            while (nextVariableDemandChange == periodEnd) updateVariableDemand()
        }
    }

    private fun upsampleDataPoint(startTime: TimestampNs, endTime: TimestampNs, value: Double) {
        // Create arrays of the start/end times of upsampled measurement periods, and of the demands per period
        var endOfTimePeriods = LongArray(8)
        var periodExactDemands = DoubleArray(8)
        var periodVariableDemands = DoubleArray(8)
        var periodCount = 0
        // Iterate over change points and process them
        while (true) {
            // Reallocate buffers if needed
            if (periodCount == periodExactDemands.size) {
                endOfTimePeriods = endOfTimePeriods.copyOf(periodCount * 2)
                periodExactDemands = periodExactDemands.copyOf(periodCount * 2)
                periodVariableDemands = periodVariableDemands.copyOf(periodCount * 2)
            }
            // Add the "old" demand values to the local buffers
            periodExactDemands[periodCount] = currentExactDemand
            periodVariableDemands[periodCount] = currentVariableDemand
            periodCount++
            // Stop if the next change point occurs after the end of this measurement period
            if (nextExactDemandChange >= endTime && nextVariableDemandChange >= endTime) {
                endOfTimePeriods[periodCount - 1] = endTime
                break
            }
            // Otherwise, set the start of the next period and process demand changes
            val nextChangePoint = minOf(nextExactDemandChange, nextVariableDemandChange)
            endOfTimePeriods[periodCount - 1] = nextChangePoint
            while (nextExactDemandChange == nextChangePoint) updateExactDemand()
            while (nextVariableDemandChange == nextChangePoint) updateVariableDemand()
        }
        // Upsample the observed metric value, first based on exact demand
        val maxValue = observedUsage.maxValue
        val periodTimeFraction = DoubleArray(periodCount) { i ->
            val periodLength = endOfTimePeriods[i] - (if (i > 0) endOfTimePeriods[i - 1] else startTime)
            periodLength.toDouble() / (endTime - startTime)
        }
        val periodValues = DoubleArray(periodCount)
        var remainingSample = value
        var remainingExactDemand = (0 until periodCount).sumOf { periodExactDemands[it] * periodTimeFraction[it] }
        // Iterate through periods in decreasing order of exact demand
        for (periodId in (0 until periodCount).sortedByDescending { periodExactDemands[it] }) {
            if (remainingExactDemand <= 0.0) break
            // Assign metric value proportional to exact demand, but no more than the exact demand or resource cap
            val periodValue = minOf(
                (remainingSample / remainingExactDemand) * periodExactDemands[periodId],
                periodExactDemands[periodId],
                maxValue
            )
            periodValues[periodId] = periodValue
            remainingSample = maxOf(remainingSample - periodValue * periodTimeFraction[periodId], 0.0)
            remainingExactDemand -= periodExactDemands[periodId] * periodTimeFraction[periodId]
        }
        // Attribute remaining sample proportional to variable demand
        if (remainingSample > 0.0) {
            var remainingVariableDemand = (0 until periodCount).sumOf {
                periodVariableDemands[it] * periodTimeFraction[it]
            }
            // Iterate through periods in decreasing order of variable demand to remaining capacity ratio
            for (periodId in (0 until periodCount).sortedByDescending {
                periodVariableDemands[it] / (maxValue - periodValues[it])
            }) {
                if (remainingVariableDemand <= 0.0) break
                // Assign metric value proportional to variable demand, but no more than the resource cap
                val periodValue = minOf(
                    (remainingSample / remainingVariableDemand) * periodVariableDemands[periodId],
                    maxValue - periodValues[periodId]
                )
                periodValues[periodId] += periodValue
                remainingSample = maxOf(remainingSample - periodValue * periodTimeFraction[periodId], 0.0)
                remainingVariableDemand -= periodVariableDemands[periodId] * periodTimeFraction[periodId]
            }
        }
        // Finally attribute remaining sample equally over time as background noise
        if (remainingSample > 0.0) {
            var timeRemaining = 1.0
            // Iterate through periods in increasing order of remaining capacity
            for (periodId in (0 until periodCount).sortedByDescending { periodValues[it] }) {
                // Assign metric value equally to all periods, but no more than the resource cap
                val periodValue = minOf(
                    remainingSample / timeRemaining,
                    maxValue - periodValues[periodId]
                )
                periodValues[periodId] += periodValue
                remainingSample = maxOf(remainingSample - periodValue * periodTimeFraction[periodId], 0.0)
                timeRemaining -= periodTimeFraction[periodId]
            }
        }
        // Add data points for each period
        for (i in 0 until periodCount) {
            emitDataPoint(endOfTimePeriods[i], periodValues[i])
        }
    }

    private fun emitDataPoint(timestamp: TimestampNs, value: Double) {
        if (enableTimeSeriesCompression && values.size > 0 && value == values.last()) {
            timestamps.replaceLast(timestamp)
        } else {
            timestamps.append(timestamp)
            values.append(value)
        }
    }

    private fun updateExactDemand() {
        if (exactDemandIndex < exactDemand.values.size) {
            currentExactDemand = exactDemand.values[exactDemandIndex]
            exactDemandIndex++
            nextExactDemandChange = exactDemand.timestamps[exactDemandIndex]
        } else {
            currentExactDemand = 0.0
            nextExactDemandChange = Long.MAX_VALUE
        }
    }

    private fun updateVariableDemand() {
        if (variableDemandIndex < variableDemand.values.size) {
            currentVariableDemand = variableDemand.values[variableDemandIndex]
            variableDemandIndex++
            nextVariableDemandChange = variableDemand.timestamps[variableDemandIndex]
        } else {
            currentVariableDemand = 0.0
            nextVariableDemandChange = Long.MAX_VALUE
        }
    }

}