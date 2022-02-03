package science.atlarge.grademl.core.models

import science.atlarge.grademl.core.util.TimestampNs
import science.atlarge.grademl.core.util.TimestampNsArray

class MetricData(
    val timestamps: TimestampNsArray,
    val values: DoubleArray,
    val maxValue: Double
) {

    init {
        require(timestamps.size == values.size + 1) { "Size of timestamp and value arrays must be consistent" }
    }

    fun slice(startTime: TimestampNs, endTime: TimestampNs): MetricData {
        if (startTime >= timestamps.last()) {
            return MetricData(longArrayOf(startTime), doubleArrayOf(), maxValue)
        } else if (endTime <= timestamps.first()) {
            return MetricData(longArrayOf(endTime), doubleArrayOf(), maxValue)
        }

        var startIdx = timestamps.binarySearch(startTime)
        if (startIdx < 0) startIdx = maxOf(startIdx.inv() - 1, 0)
        var endIdx = timestamps.binarySearch(endTime)
        if (endIdx < 0) endIdx = minOf(endIdx.inv(), timestamps.lastIndex)

        val selectedTimestamps = timestamps.copyOfRange(startIdx, endIdx + 1)
        val selectedValues = values.copyOfRange(startIdx, endIdx)

        return MetricData(selectedTimestamps, selectedValues, maxValue)
    }

    fun iterator(): MetricDataIterator {
        return MetricDataIteratorImpl(timestamps, values)
    }

    fun iteratorFrom(startTime: TimestampNs): MetricDataIterator {
        var startIdx = timestamps.binarySearch(startTime)
        if (startIdx < 0) startIdx = maxOf(startIdx.inv() - 1, -1)
        return MetricDataIteratorImpl(timestamps, values, startIdx)
    }

}

interface MetricDataIterator {
    val currentStartTime: TimestampNs
    val currentEndTime: TimestampNs
    val currentValue: Double
    val hasNext: Boolean

    fun peekNextEndTime(): TimestampNs
    fun peekNextValue(): Double
    fun next()
}

private class MetricDataIteratorImpl(
    private val timestamps: TimestampNsArray,
    private val values: DoubleArray,
    initialIndex: Int = -1
) : MetricDataIterator {

    override var currentStartTime: TimestampNs = Long.MIN_VALUE
        private set
    override var currentEndTime: TimestampNs = timestamps.first()
        private set
    override var currentValue: Double = 0.0
        private set

    private var nextIndex = 0

    override val hasNext: Boolean
        get() = nextIndex < values.size

    init {
        if (initialIndex >= 0) {
            nextIndex = minOf(initialIndex + 1, values.size)
            currentStartTime = timestamps[nextIndex - 1]
            currentEndTime = timestamps[nextIndex]
            currentValue = values[nextIndex - 1]
        }
    }

    override fun peekNextEndTime(): TimestampNs {
        return if (hasNext) timestamps[nextIndex + 1] else Long.MAX_VALUE
    }

    override fun peekNextValue(): Double {
        return if (hasNext) values[nextIndex] else 0.0
    }

    override fun next() {
        if (hasNext) {
            currentStartTime = currentEndTime
            currentEndTime = timestamps[nextIndex + 1]
            currentValue = values[nextIndex]
            nextIndex++
        } else {
            currentStartTime = timestamps.last()
            currentEndTime = Long.MAX_VALUE
            currentValue = 0.0
            nextIndex = values.size
        }
    }

}