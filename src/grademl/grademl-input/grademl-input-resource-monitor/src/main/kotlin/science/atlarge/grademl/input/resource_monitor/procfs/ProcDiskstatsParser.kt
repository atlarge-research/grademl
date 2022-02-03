package science.atlarge.grademl.input.resource_monitor.procfs

import science.atlarge.grademl.core.util.DoubleArrayBuilder
import science.atlarge.grademl.core.util.LongArrayBuilder
import science.atlarge.grademl.core.util.TimestampNsArray
import science.atlarge.grademl.input.resource_monitor.FileParser
import science.atlarge.grademl.input.resource_monitor.util.*
import java.io.File
import java.io.IOException

object ProcDiskstatsParser : FileParser<DiskUtilizationData> {

    private var uncompressedBytesRead: Long = 0L

    private fun parse(logFile: File): DiskUtilizationData {
        uncompressedBytesRead = 0L
        return logFile.inputStream().buffered().use { inStream ->
            // Read first message to determine number and names of disks
            val initialTimestamp = inStream.readLELong()
            uncompressedBytesRead += 8
            require(inStream.read() == 0) { "Expecting monitoring info to start with a DISK_LIST message" }
            val numDisks = inStream.readLEB128Int()
            val diskNames = (0 until numDisks).map { inStream.readString() }

            val timestamps = LongArrayBuilder()
            timestamps.append(initialTimestamp)
            val bytesReadMetric = (0 until numDisks).map { DoubleArrayBuilder() }
            val bytesWrittenMetric = (0 until numDisks).map { DoubleArrayBuilder() }
            val readTimeFractionMetric = (0 until numDisks).map { DoubleArrayBuilder() }
            val writeTimeFractionMetric = (0 until numDisks).map { DoubleArrayBuilder() }
            val totalTimeSpentFractionMetric = (0 until numDisks).map { DoubleArrayBuilder() }
            var lastTimestamp = 0L
            while (true) {
                val timestamp = inStream.tryReadLELong() ?: break
                uncompressedBytesRead += 8
                timestamps.append(timestamp)
                try {
                    require(inStream.read() == 1) { "Repeated DISK_LIST messages are currently not supported" }
                    inStream.readLEB128Int() // Skip number of disks

                    for (i in 0 until numDisks) {
                        /*val readsCompleted =*/ inStream.readLEB128Long()
                        val sectorsRead = inStream.readLEB128Long()
                        val readTimeMs = inStream.readLEB128Long()
                        /*val writesCompleted =*/ inStream.readLEB128Long()
                        val sectorsWritten = inStream.readLEB128Long()
                        val writeTimeMs = inStream.readLEB128Long()
                        val totalTimeMs = inStream.readLEB128Long()
                        uncompressedBytesRead += 56

                        bytesReadMetric[i].append(sectorsRead.toDouble() * 512 * 1_000_000_000L / (timestamp - lastTimestamp))
                        readTimeFractionMetric[i].append(readTimeMs.toDouble() * 1_000_000L / (timestamp - lastTimestamp))
                        bytesWrittenMetric[i].append(sectorsWritten.toDouble() * 512 * 1_000_000_000L / (timestamp - lastTimestamp))
                        writeTimeFractionMetric[i].append(writeTimeMs.toDouble() * 1_000_000L / (timestamp - lastTimestamp))
                        totalTimeSpentFractionMetric[i].append(
                            minOf(1.0, totalTimeMs.toDouble() * 1_000_000L / (timestamp - lastTimestamp))
                        )
                    }

                    lastTimestamp = timestamp
                } catch (e: IOException) {
                    timestamps.dropLast()
                    // Metric data should have one less element than the number of timestamps
                    arrayOf(
                        bytesReadMetric,
                        readTimeFractionMetric,
                        bytesWrittenMetric,
                        writeTimeFractionMetric,
                        totalTimeSpentFractionMetric
                    ).forEach { m ->
                        m.filter { it.size >= timestamps.size }.forEach { it.dropLast() }
                    }
                    break
                }
            }

            DiskUtilizationData(
                timestamps = timestamps.toArray(),
                deviceIds = diskNames,
                bytesRead = bytesReadMetric.map { it.toArray() },
                bytesWritten = bytesWrittenMetric.map { it.toArray() },
                readTimeFraction = readTimeFractionMetric.map { it.toArray() },
                writeTimeFraction = writeTimeFractionMetric.map { it.toArray() },
                totalTimeSpentFraction = totalTimeSpentFractionMetric.map { metric ->
                    val arr = metric.toArray()
                    if (arr.any { it > 0.0 }) arr else null
                }
            )
        }
    }

    override fun parse(hostname: String, metricFiles: Iterable<File>): DiskUtilizationData {
        // Parse each metric file individually
        val utilizationDataStructures = metricFiles.map {
            val parseResult = parse(it)
//            println("[DEBUG] Read $uncompressedBytesRead uncompressed bytes from \"${it.path}\"")
            parseResult
        }.filter { it.timestamps.size > 1 }
        require(utilizationDataStructures.isNotEmpty()) { "No metric data found for disk utilization" }
        // Shortcut: return if there was only one file
        if (utilizationDataStructures.size == 1) return utilizationDataStructures[0]
        // Otherwise, merge all data structures into one
        return mergeUtilizationData(utilizationDataStructures)
    }

    private fun mergeUtilizationData(utilizationDataStructures: List<DiskUtilizationData>): DiskUtilizationData {
        // Check that none of the metrics overlap
        val sortedUtilizationData = utilizationDataStructures.sortedBy { it.timestamps.first() }
        for (i in 0 until sortedUtilizationData.size - 1) {
            require(sortedUtilizationData[i].timestamps.last() < sortedUtilizationData[i + 1].timestamps.first()) {
                "Overlapping resource metrics are not supported"
            }
        }

        // Check that the number and names of devices are identical
        val numDevices = sortedUtilizationData[0].deviceIds.size
        require(sortedUtilizationData.all { it.deviceIds.size == numDevices }) {
            "Cannot merge disk metrics with different number of devices"
        }
        val deviceIds = sortedUtilizationData[0].deviceIds
        require(sortedUtilizationData.all { diskData ->
            (0 until numDevices).all { diskData.deviceIds[it] == deviceIds[it] }
        }) {
            "Cannot merge disk metrics with different devices or devices in a different order"
        }

        // Perform the "merge" through concatenation
        val timestamps = concatenateArrays(sortedUtilizationData.map { it.timestamps })
        val bytesRead = (0 until numDevices).map { i ->
            concatenateArrays(
                sortedUtilizationData.map { it.bytesRead[i] },
                separator = doubleArrayOf(0.0)
            )
        }
        val bytesWritten = (0 until numDevices).map { i ->
            concatenateArrays(
                sortedUtilizationData.map { it.bytesWritten[i] },
                separator = doubleArrayOf(0.0)
            )
        }
        val readTimeFraction = (0 until numDevices).map { i ->
            concatenateArrays(
                sortedUtilizationData.map { it.readTimeFraction[i] },
                separator = doubleArrayOf(0.0)
            )
        }
        val writeTimeFraction = (0 until numDevices).map { i ->
            concatenateArrays(
                sortedUtilizationData.map { it.writeTimeFraction[i] },
                separator = doubleArrayOf(0.0)
            )
        }
        val totalTimeSpentFraction = (0 until numDevices).map { i ->
            concatenateOptionalArrays(
                sortedUtilizationData.map { it.totalTimeSpentFraction[i] },
                expectedArraySizes = sortedUtilizationData.map { it.timestamps.size - 1 },
                separator = doubleArrayOf(0.0)
            )
        }

        return DiskUtilizationData(
            timestamps = timestamps,
            deviceIds = deviceIds,
            bytesRead = bytesRead,
            bytesWritten = bytesWritten,
            readTimeFraction = readTimeFraction,
            writeTimeFraction = writeTimeFraction,
            totalTimeSpentFraction = totalTimeSpentFraction
        )
    }

}

class DiskUtilizationData(
    val timestamps: TimestampNsArray,
    val deviceIds: List<String>,
    val bytesRead: List<DoubleArray>,
    val bytesWritten: List<DoubleArray>,
    val readTimeFraction: List<DoubleArray>,
    val writeTimeFraction: List<DoubleArray>,
    val totalTimeSpentFraction: List<DoubleArray?>
) {

    init {
        val numDevices = deviceIds.size
        require(
            bytesRead.size == numDevices && bytesWritten.size == numDevices &&
                    readTimeFraction.size == numDevices && writeTimeFraction.size == numDevices &&
                    totalTimeSpentFraction.size == numDevices
        ) {
            "Sizes of disk metric lists must all be identical (i.e., equal to the number of devices)"
        }
        require(
            bytesRead.all { it.size == timestamps.size - 1 } &&
                    bytesWritten.all { it.size == timestamps.size - 1 } &&
                    readTimeFraction.all { it.size == timestamps.size - 1 } &&
                    writeTimeFraction.all { it.size == timestamps.size - 1 } &&
                    totalTimeSpentFraction.all { if (it == null) true else it.size == timestamps.size - 1 }
        ) {
            "Sizes of metrics arrays and timestamps array must be consistent"
        }
    }

}
