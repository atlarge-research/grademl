package science.atlarge.grademl.input.resource_monitor.procfs

import science.atlarge.grademl.core.util.DoubleArrayBuilder
import science.atlarge.grademl.core.util.LongArrayBuilder
import science.atlarge.grademl.core.util.TimestampNsArray
import science.atlarge.grademl.input.resource_monitor.FileParser
import science.atlarge.grademl.input.resource_monitor.util.*
import java.io.File

object ProcNetDevParser : FileParser<NetworkUtilizationData> {

    private var uncompressedBytesRead: Long = 0L

    private fun parse(logFile: File): NetworkUtilizationData {
        uncompressedBytesRead = 0L
        return logFile.inputStream().buffered().use { inStream ->
            // Read first message to determine number and names of interfaces
            val initialTimestamp = inStream.readLELong()
            uncompressedBytesRead += 8
            require(inStream.read() == 0) { "Expecting monitoring info to start with an IFACE_LIST message" }
            val numInterfaces = inStream.readLEB128Int()
            val interfaceNames = (0 until numInterfaces).map { inStream.readString() }

            val timestamps = LongArrayBuilder()
            timestamps.append(initialTimestamp)
            val receivedUtilization = (0 until numInterfaces).map { DoubleArrayBuilder() }
            val sentUtilization = (0 until numInterfaces).map { DoubleArrayBuilder() }
            var lastTimestamp = 0L
            while (true) {
                val timestamp = inStream.tryReadLELong() ?: break
                uncompressedBytesRead += 8
                timestamps.append(timestamp)
                try {
                    require(inStream.read() == 1) { "Repeated IFACE_LIST messages are currently not supported" }
                    inStream.readLEB128Int() // Skip number of interfaces

                    for (i in 0 until numInterfaces) {
                        val bytesReceived = inStream.readLEB128Long()
                        /*val packetsReceived =*/ inStream.readLEB128Long()
                        val bytesSent = inStream.readLEB128Long()
                        /*val packetsSent =*/ inStream.readLEB128Long()
                        uncompressedBytesRead += 32
                        receivedUtilization[i].append(bytesReceived.toDouble() * 1_000_000_000L / (timestamp - lastTimestamp))
                        sentUtilization[i].append(bytesSent.toDouble() * 1_000_000_000L / (timestamp - lastTimestamp))
                    }

                    lastTimestamp = timestamp
                } catch (e: Exception) {
                    timestamps.dropLast()
                    // Metric data should have one less element than the number of timestamps
                    receivedUtilization
                        .filter { it.size >= timestamps.size }
                        .forEach { it.dropLast() }
                    sentUtilization
                        .filter { it.size >= timestamps.size }
                        .forEach { it.dropLast() }
                    break
                }
            }

            NetworkUtilizationData(
                timestamps = timestamps.toArray(),
                interfaceIds = interfaceNames,
                bytesReceived = receivedUtilization.map { it.toArray() },
                bytesSent = sentUtilization.map { it.toArray() }
            )
        }
    }

    override fun parse(hostname: String, metricFiles: Iterable<File>): NetworkUtilizationData {
        // Parse each metric file individually
        val utilizationDataStructures = metricFiles.map {
            val parseResult = parse(it)
//            println("[DEBUG] Read $uncompressedBytesRead uncompressed bytes from \"${it.path}\"")
            parseResult
        }.filter { it.timestamps.size > 1 }
        require(utilizationDataStructures.isNotEmpty()) { "No metric data found for network utilization" }
        // Shortcut: return if there was only one file
        if (utilizationDataStructures.size == 1) return utilizationDataStructures[0]
        // Otherwise, merge all data structures into one
        return mergeUtilizationData(utilizationDataStructures)
    }

    private fun mergeUtilizationData(utilizationDataStructures: List<NetworkUtilizationData>): NetworkUtilizationData {
        // Check that none of the metrics overlap
        val sortedUtilizationData = utilizationDataStructures.sortedBy { it.timestamps.first() }
        for (i in 0 until sortedUtilizationData.size - 1) {
            require(sortedUtilizationData[i].timestamps.last() < sortedUtilizationData[i + 1].timestamps.first()) {
                "Overlapping resource metrics are not supported"
            }
        }

        // Check that the number and names of interfaces are identical
        val numInterfaces = sortedUtilizationData[0].interfaceIds.size
        require(sortedUtilizationData.all { it.interfaceIds.size == numInterfaces }) {
            "Cannot merge network metrics with different number of interfaces"
        }
        val interfaceIds = sortedUtilizationData[0].interfaceIds
        require(sortedUtilizationData.all { diskData ->
            (0 until numInterfaces).all { diskData.interfaceIds[it] == interfaceIds[it] }
        }) {
            "Cannot merge network metrics with different interfaces or interfaces in a different order"
        }

        // Perform the "merge" through concatenation
        val timestamps = concatenateArrays(sortedUtilizationData.map { it.timestamps })
        val bytesReceived = (0 until numInterfaces).map { i ->
            concatenateArrays(
                sortedUtilizationData.map { it.bytesReceived[i] },
                separator = doubleArrayOf(0.0)
            )
        }
        val bytesSent = (0 until numInterfaces).map { i ->
            concatenateArrays(
                sortedUtilizationData.map { it.bytesSent[i] },
                separator = doubleArrayOf(0.0)
            )
        }

        return NetworkUtilizationData(
            timestamps = timestamps,
            interfaceIds = interfaceIds,
            bytesReceived = bytesReceived,
            bytesSent = bytesSent
        )
    }

}

class NetworkUtilizationData(
    val timestamps: TimestampNsArray,
    val interfaceIds: List<String>,
    val bytesReceived: List<DoubleArray>,
    val bytesSent: List<DoubleArray>
) {

    init {
        val numInterfaces = interfaceIds.size
        require(bytesReceived.size == numInterfaces && bytesSent.size == numInterfaces) {
            "Sizes of network metric lists must all be identical (i.e., equal to the number of interfaces)"
        }
        require(
            bytesReceived.all { it.size == timestamps.size - 1 } &&
                    bytesSent.all { it.size == timestamps.size - 1 }
        ) {
            "Sizes of metrics arrays and timestamps array must be consistent"
        }
    }

}
