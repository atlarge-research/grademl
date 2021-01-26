package science.atlarge.grade10.monitor.nvml

import science.atlarge.grade10.monitor.util.*
import science.atlarge.grade10.util.TimestampNsArray
import java.io.File
import java.io.IOException

object NvidiaGpuParser {

    fun parse(logFile: File): NvidiaGpusUtilizationData {
        return logFile.inputStream().buffered().use { inStream ->
            // Read first message to determine number and names of disks
            val initialTimestamp = inStream.readLELong()
            require(inStream.read() == 0) { "Expecting monitoring info to start with a DEVICE_LIST message" }
            val numGpus = inStream.readLEB128Int()
            val gpuNames = Array(numGpus) { inStream.readString() }

            val timestamps = LongArrayBuilder()
            timestamps.append(initialTimestamp)
            val gpuUtilizationMetric = Array(numGpus) { DoubleArrayBuilder() }
            val memoryUtilizationMetric = Array(numGpus) { DoubleArrayBuilder() }
            val txThroughputMetric = Array(numGpus) { DoubleArrayBuilder() }
            val rxThroughputMetric = Array(numGpus) { DoubleArrayBuilder() }
            var skipNext = true // TODO: Figure out why (only) the first measurement in each dataset seems to have a <1ms delta
            while (true) {
                val timestamp = inStream.tryReadLELong() ?: break
                if (!skipNext) timestamps.append(timestamp)
                try {
                    require(inStream.read() == 1) { "Repeated DEVICE_LIST messages are currently not supported" }
                    inStream.readLEB128Int() // Skip number of GPUs

                    for (i in 0 until numGpus) {
                        val gpuPercent = inStream.read()
                        val memoryPercent = inStream.read()
                        val txThroughputKBps = inStream.readLEB128Int()
                        val rxThroughputKBps = inStream.readLEB128Int()

                        if (!skipNext) {
                            gpuUtilizationMetric[i].append(gpuPercent / 100.0)
                            memoryUtilizationMetric[i].append(memoryPercent / 100.0)
                            txThroughputMetric[i].append(txThroughputKBps * 1024.0)
                            rxThroughputMetric[i].append(rxThroughputKBps * 1024.0)
                        }
                    }
                } catch (e: IOException) {
                    timestamps.dropLast()
                    // Metric data should have one less element than the number of timestamps
                    arrayOf(
                            gpuUtilizationMetric,
                            memoryUtilizationMetric,
                            txThroughputMetric,
                            rxThroughputMetric
                    ).forEach { m ->
                        m.filter { it.size >= timestamps.size }.forEach { it.dropLast() }
                    }
                    break
                }
                skipNext = false
            }

            val timestampArray = timestamps.toArray()
            val gpus = gpuNames.mapIndexed { i, gpuName ->
                NvidiaGpuUtilizationData(i,
                        gpuName,
                        timestampArray,
                        gpuUtilizationMetric[i].toArray(),
                        memoryUtilizationMetric[i].toArray(),
                        txThroughputMetric[i].toArray(),
                        rxThroughputMetric[i].toArray())
            }

            NvidiaGpusUtilizationData(gpus)
        }
    }

}

class NvidiaGpusUtilizationData(gpuData: Iterable<NvidiaGpuUtilizationData>) {

    val gpus = gpuData.associateBy { it.deviceId }

}

class NvidiaGpuUtilizationData(
        val deviceId: Int,
        val deviceName: String,
        val timestamps: TimestampNsArray,
        val gpuFraction: DoubleArray,
        val memoryFraction: DoubleArray,
        val txThroughput: DoubleArray,
        val rxThroughput: DoubleArray
)
