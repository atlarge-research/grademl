package science.atlarge.grademl.input.resource_monitor.procfs

import science.atlarge.grademl.core.util.DoubleArrayBuilder
import science.atlarge.grademl.core.util.LongArrayBuilder
import science.atlarge.grademl.core.util.TimestampNsArray
import science.atlarge.grademl.input.resource_monitor.FileParser
import science.atlarge.grademl.input.resource_monitor.util.concatenateArrays
import science.atlarge.grademl.input.resource_monitor.util.readLEB128Int
import science.atlarge.grademl.input.resource_monitor.util.readLEB128Long
import science.atlarge.grademl.input.resource_monitor.util.readLELong
import java.io.Closeable
import java.io.File

object ProcStatParser : FileParser<CpuUtilizationData> {

    private const val FULL_CORE_THRESHOLD = 0.95

    private class ParserState(logFile: File) : Closeable {
        private val stream = logFile.inputStream().buffered()

        private val timestamps = LongArrayBuilder()
        private val totalCoreUtilization = DoubleArrayBuilder()
        private val coresFullyUtilized = LongArrayBuilder()
        private lateinit var coreUtilization: Array<DoubleArrayBuilder>

        private var numCpus: Int = 0
        private var currentTimestamp: Long = 0
        private lateinit var currentCpuMetrics: LongArray

        var uncompressedBytesRead: Long = 0L
            private set

        fun parse(): CpuUtilizationData {
            // Read first message to set initial timestamp and determine the number of CPUs
            readFirstMessage()
            timestamps.append(currentTimestamp)
            // Read and process messages until an exception occurs while reading
            try {
                while (true) {
                    readNextMessage()
                    timestamps.append(currentTimestamp)
                    computeUtilization()
                }
            } catch (e: Exception) {
                // Swallow exception and stop parsing more data
            }

            // Convert the result to the right data structures
            val timestampsArray = timestamps.toArray()
            val coreUtilizationData = coreUtilization.map { it.toArray() }
            return CpuUtilizationData(
                timestampsArray,
                totalCoreUtilization.toArray(),
                coresFullyUtilized.toArray(),
                numCpus,
                coreUtilizationData
            )
        }

        private fun readFirstMessage() {
            // Read initial timestamp
            currentTimestamp = stream.readLELong()
            uncompressedBytesRead += 8
            // Read number of CPUs and create data structures to read CPU metrics and store utilization
            numCpus = stream.readLEB128Int()
            currentCpuMetrics = LongArray(numCpus * 10)
            coreUtilization = Array(numCpus) { DoubleArrayBuilder() }
            // Read initial CPU metric values
            for (i in 0..currentCpuMetrics.lastIndex) {
                currentCpuMetrics[i] = stream.readLEB128Long()
                uncompressedBytesRead += 8
            }
        }

        private fun readNextMessage() {
            // Read delta timestamp
            currentTimestamp = stream.readLELong()
            uncompressedBytesRead += 8
            // Check the number of CPUs
            require(stream.readLEB128Int() == numCpus) {
                "ProcStatParse currently does not support changing the number of CPUs"
            }
            // Read the new CPU metric values
            for (i in 0..currentCpuMetrics.lastIndex) {
                currentCpuMetrics[i] = stream.readLEB128Long()
                uncompressedBytesRead += 8
            }
        }

        private fun computeUtilization() {
            var sumUtilization = 0.0
            var fullCoreCount = 0L
            for (cpuId in 0 until numCpus) {
                val cpuOffset = cpuId * 10
                var totalJiffies = 0L
                for (i in cpuOffset until cpuOffset + 10) {
                    totalJiffies += currentCpuMetrics[i]
                }
                val idleJiffies = currentCpuMetrics[cpuOffset + 3]
                val utilization = (totalJiffies - idleJiffies) / totalJiffies.toDouble()

                coreUtilization[cpuId].append(utilization)

                sumUtilization += utilization
                if (utilization >= FULL_CORE_THRESHOLD) {
                    fullCoreCount++
                }
            }
            totalCoreUtilization.append(sumUtilization)
            coresFullyUtilized.append(fullCoreCount)
        }

        override fun close() {
            stream.close()
        }
    }

    override fun parse(hostname: String, metricFiles: Iterable<File>): CpuUtilizationData {
        // Parse each metric file individually
        val utilizationDataStructures = metricFiles.map { metricFile ->
            val parser = ParserState(metricFile)
            val parseResult = parser.parse()
//            println("[DEBUG] Read ${parser.uncompressedBytesRead} uncompressed bytes from \"${metricFile.path}\"")
            parseResult
        }.filter { it.timestamps.size > 1 }
        require(utilizationDataStructures.isNotEmpty()) { "No metric data found for CPU utilization" }
        // Shortcut: return if there was only one file
        if (utilizationDataStructures.size == 1) return utilizationDataStructures[0]
        // Otherwise, merge all data structures into one
        return mergeUtilizationData(utilizationDataStructures)
    }

    private fun mergeUtilizationData(utilizationDataStructures: List<CpuUtilizationData>): CpuUtilizationData {
        // Check that none of the metrics overlap
        val sortedUtilizationData = utilizationDataStructures.sortedBy { it.timestamps.first() }
        for (i in 0 until sortedUtilizationData.size - 1) {
            require(sortedUtilizationData[i].timestamps.last() < sortedUtilizationData[i + 1].timestamps.first()) {
                "Overlapping resource metrics are not supported"
            }
        }

        // Check that the number of cores and presence of core utilization data is consistent
        val numCpuCores = sortedUtilizationData[0].numCpuCores
        require(sortedUtilizationData.all { it.numCpuCores == numCpuCores }) {
            "Cannot merge CPU metrics with different number of cores"
        }
        val hasCoreUtilization = sortedUtilizationData[0].coreUtilization.isNotEmpty()
        require(sortedUtilizationData.all { it.coreUtilization.isNotEmpty() == hasCoreUtilization }) {
            "Cannot merge CPU metrics where only some include core utilization data"
        }

        // Perform the "merge" through concatenation
        val timestamps = concatenateArrays(sortedUtilizationData.map { it.timestamps })
        val totalCoreUtilization = concatenateArrays(
            sortedUtilizationData.map { it.totalCoreUtilization },
            separator = doubleArrayOf(0.0)
        )
        val coresFullyUtilized = concatenateArrays(
            sortedUtilizationData.map { it.coresFullyUtilized },
            separator = longArrayOf(0L)
        )
        val coreUtilization = if (hasCoreUtilization) {
            (0 until numCpuCores).map { i ->
                concatenateArrays(
                    sortedUtilizationData.map { it.coreUtilization[i] },
                    separator = doubleArrayOf(0.0)
                )
            }
        } else {
            emptyList()
        }

        return CpuUtilizationData(timestamps, totalCoreUtilization, coresFullyUtilized, numCpuCores, coreUtilization)
    }

}

class CpuUtilizationData(
    val timestamps: TimestampNsArray,
    val totalCoreUtilization: DoubleArray,
    val coresFullyUtilized: LongArray,
    val numCpuCores: Int,
    val coreUtilization: List<DoubleArray>
) {

    init {
        require(totalCoreUtilization.size == timestamps.size - 1) {
            "Sizes of totalCoreUtilization array and timestamps array must be consistent"
        }
        require(coresFullyUtilized.size == timestamps.size - 1) {
            "Sizes of totalCoreUtilization array and timestamps array must be consistent"
        }
        require(coreUtilization.isEmpty() || coreUtilization.size == numCpuCores) {
            "Core utilization data must be available for all or none of the cores"
        }
        for (core in coreUtilization) {
            require(core.size == timestamps.size - 1) {
                "Sizes of coreUtilization arrays and timestamps array must be consistent"
            }
        }
    }

}
