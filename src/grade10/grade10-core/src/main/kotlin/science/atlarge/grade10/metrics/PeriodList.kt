package science.atlarge.grade10.metrics

import science.atlarge.grade10.util.*

class PeriodList private constructor(periods: List<LongRange>,
                                     sorted: Boolean = false) {
    companion object {
        private fun sortAndMergeTimeRanges(ranges: List<LongRange>): List<LongRange> {
            if (ranges.isEmpty())
                return emptyList()

            val sortedRanges = ranges.sortedBy { it.start }
            val mergedRanges = arrayListOf<LongRange>()

            var i = 0
            while (i < sortedRanges.size) {
                val startRegion = sortedRanges[i].start
                var endRegion = sortedRanges[i].endInclusive
                i++

                while (i < sortedRanges.size && sortedRanges[i].start <= endRegion + 1) {
                    endRegion = Math.max(endRegion, sortedRanges[i].endInclusive)
                    i++
                }

                if (startRegion <= endRegion)
                    mergedRanges.add(startRegion..endRegion)
            }

            return mergedRanges
        }
    }

    val periods: List<LongRange> = if (sorted) periods else sortAndMergeTimeRanges(periods)

    constructor(periods: List<LongRange>) : this(periods, false)

    operator fun minus(other: PeriodList): PeriodList {
        if (other.periods.isEmpty())
            return this

        val newRanges = arrayListOf<LongRange>()

        var currentOtherPeriod = other.periods[0]
        var nextOtherPeriodIdx = 1
        for (period in periods) {
            var newRangeStart = period.start
            while (newRangeStart <= period.endInclusive) {
                // Find the next period to subtract that ends after the currently considered time
                while (currentOtherPeriod.endInclusive < newRangeStart && nextOtherPeriodIdx < other.periods.size) {
                    currentOtherPeriod = other.periods[nextOtherPeriodIdx]
                    nextOtherPeriodIdx++
                }

                if (currentOtherPeriod.endInclusive < newRangeStart || currentOtherPeriod.start > period.endInclusive) {
                    // No overlap, so include the full range
                    newRanges.add(LongRange(newRangeStart, period.endInclusive))
                    break
                } else if (currentOtherPeriod.start <= newRangeStart) {
                    // Overlap with the start of the new range, so move the start
                    newRangeStart = currentOtherPeriod.endInclusive + 1
                } else {
                    // Overlap with any part of this range after the start, so add the start and repeat
                    newRanges.add(LongRange(newRangeStart, currentOtherPeriod.start - 1))
                    newRangeStart = currentOtherPeriod.endInclusive + 1
                }
            }
        }

        return PeriodList(newRanges, true)
    }

}

class TimePeriodList(
        private val timestampedPeriodList: PeriodList
) : Iterable<TimestampNsRange> {

    val periods: List<TimestampNsRange>
        get() = timestampedPeriodList.periods

    constructor(timestampRange: TimestampNsRange) : this(PeriodList(listOf(timestampRange)))

    constructor(timestampPeriods: List<TimestampNsRange>) : this(PeriodList(timestampPeriods))

    override fun iterator(): Iterator<TimestampNsRange> {
        return periods.iterator()
    }

}

class TimeSlicePeriodList(
        private val timeSlicedPeriodList: PeriodList
) : Iterable<TimesliceRange> {

    val periods: List<TimesliceRange>
        get() = timeSlicedPeriodList.periods

    constructor(timesliceRange: TimesliceRange) : this(PeriodList(listOf(timesliceRange)))

    constructor(timeslicePeriods: List<TimesliceRange>) : this(PeriodList(timeslicePeriods))

    operator fun minus(other: TimeSlicePeriodList): TimeSlicePeriodList {
        return TimeSlicePeriodList(timeSlicedPeriodList - other.timeSlicedPeriodList)
    }

    override fun iterator(): Iterator<TimesliceRange> {
        return periods.iterator()
    }

    companion object {

        fun TimePeriodList.toSlices(T: Time): TimeSlicePeriodList {
            val timeSliceRanges = periods.map { timePeriod ->
                TimesliceRange(
                        start = T.timeSliceForStartTimestamp(timePeriod.first),
                        endInclusive = T.timeSliceForEndTimestamp(timePeriod.last)
                )
            }
            return TimeSlicePeriodList(PeriodList(timeSliceRanges))
        }

    }

}
