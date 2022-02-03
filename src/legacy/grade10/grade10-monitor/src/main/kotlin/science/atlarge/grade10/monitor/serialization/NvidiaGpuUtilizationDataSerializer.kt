package science.atlarge.grade10.monitor.serialization

import com.esotericsoftware.kryo.Kryo
import com.esotericsoftware.kryo.Serializer
import com.esotericsoftware.kryo.io.Input
import com.esotericsoftware.kryo.io.Output
import science.atlarge.grade10.monitor.nvml.NvidiaGpuUtilizationData

class NvidiaGpuUtilizationDataSerializer : Serializer<NvidiaGpuUtilizationData>(true, true) {

    override fun write(kryo: Kryo, output: Output, gpuUtilizationData: NvidiaGpuUtilizationData) {
        gpuUtilizationData.apply {
            output.writeInt(deviceId)
            output.writeString(deviceName)
            output.writeVarInt(timestamps.size, true)
            output.writeDeltaLongs(timestamps)
            output.writeDoubles(gpuFraction)
            output.writeDoubles(memoryFraction)
            output.writeDoubles(txThroughput)
            output.writeDoubles(rxThroughput)
        }
    }

    override fun read(kryo: Kryo, input: Input, type: Class<NvidiaGpuUtilizationData>): NvidiaGpuUtilizationData {
        val deviceId = input.readInt()
        val deviceName = input.readString()
        val timestampCount = input.readVarInt(true)
        val timestamps = input.readDeltaLongs(timestampCount)
        val gpuFraction = input.readDoubles(timestampCount - 1)
        val memoryFraction = input.readDoubles(timestampCount - 1)
        val txThroughput = input.readDoubles(timestampCount - 1)
        val rxThroughput = input.readDoubles(timestampCount - 1)
        return NvidiaGpuUtilizationData(deviceId, deviceName, timestamps,
                gpuFraction, memoryFraction, txThroughput, rxThroughput)
    }

}