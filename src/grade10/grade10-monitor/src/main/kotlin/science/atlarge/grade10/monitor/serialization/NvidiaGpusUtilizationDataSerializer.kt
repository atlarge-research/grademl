package science.atlarge.grade10.monitor.serialization

import com.esotericsoftware.kryo.Kryo
import com.esotericsoftware.kryo.Serializer
import com.esotericsoftware.kryo.io.Input
import com.esotericsoftware.kryo.io.Output
import science.atlarge.grade10.monitor.nvml.NvidiaGpuUtilizationData
import science.atlarge.grade10.monitor.nvml.NvidiaGpusUtilizationData

class NvidiaGpusUtilizationDataSerializer : Serializer<NvidiaGpusUtilizationData>(true, true) {

    override fun write(kryo: Kryo, output: Output, gpusUtilizationData: NvidiaGpusUtilizationData) {
        gpusUtilizationData.apply {
            output.writeVarInt(gpus.size, true)
            gpus.values.forEach {
                kryo.writeObject(output, it)
            }
        }
    }

    override fun read(kryo: Kryo, input: Input, type: Class<NvidiaGpusUtilizationData>): NvidiaGpusUtilizationData {
        val numGpus = input.readVarInt(true)
        val gpus = ArrayList<NvidiaGpuUtilizationData>(numGpus)
        repeat(numGpus) {
            gpus.add(kryo.readObject(input, NvidiaGpuUtilizationData::class.java))
        }
        return NvidiaGpusUtilizationData(gpus)
    }

}