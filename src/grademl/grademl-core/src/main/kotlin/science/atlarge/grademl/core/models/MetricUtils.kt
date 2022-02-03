package science.atlarge.grademl.core.models

import science.atlarge.grademl.core.util.LongArrayBuilder
import science.atlarge.grademl.core.util.TimestampNsArray
import java.util.*

fun Iterable<MetricData>.sum(combinedMaxValue: Double) = sumOfMetricData(this.toList(), combinedMaxValue)

fun sumOfMetricData(metricData: List<MetricData>, combinedMaxValue: Double): MetricData {
    require(metricData.isNotEmpty()) { "Cannot sum an empty list of metrics" }
    // Create an array of unique timestamps across all metrics
    val timestamps = mergeTimestamps(metricData.map { it.timestamps })
    // Create a corresponding array to sum values in
    val values = DoubleArray(timestamps.size - 1)
    // For each metric, add its values to the running totals
    for (metric in metricData) {
        val metricIterator = metric.iterator()
        var periodId = timestamps.binarySearch(metric.timestamps.first())
        while (metricIterator.hasNext) {
            metricIterator.next()
            while (timestamps[periodId] < metricIterator.currentEndTime) {
                values[periodId] += metricIterator.currentValue
                periodId++
            }
        }
    }
    return MetricData(timestamps, values, combinedMaxValue)
}

fun mergeTimestamps(timestamps: List<TimestampNsArray>): TimestampNsArray {
    require(timestamps.isNotEmpty()) { "Cannot merge an empty list of timestamp arrays" }
    // Start a builder for the merged timestamp array
    val outTimestamps = LongArrayBuilder(timestamps.sumOf(TimestampNsArray::size))
    // Create a priority queue of timestamp iterators, sorted by next timestamp
    val timeIteratorQueue = PriorityQueue(timestamps.map { PeekingTimestampIterator(it) })
    // Process all timestamps in ascending order, appending any new timestamp to the result list
    while (timeIteratorQueue.isNotEmpty()) {
        val nextIterator = timeIteratorQueue.poll()
        val nextTimestamp = nextIterator.nextLong()
        if (outTimestamps.size == 0 || outTimestamps.last() != nextTimestamp) outTimestamps.append(nextTimestamp)
        if (nextIterator.hasNext()) timeIteratorQueue.offer(nextIterator)
    }
    return outTimestamps.toArray()
}

private class PeekingTimestampIterator(private val timestamps: TimestampNsArray) :
    Comparable<PeekingTimestampIterator> {

    private val size = timestamps.size
    private var index = 0
    val nextValue: Long
        get() = if (index < size) timestamps[index] else Long.MAX_VALUE

    fun hasNext() = index < size

    fun nextLong(): Long {
        val n = nextValue
        index++
        return n
    }

    override fun compareTo(other: PeekingTimestampIterator): Int {
        return this.nextValue.compareTo(other.nextValue)
    }

}