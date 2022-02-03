package science.atlarge.grade10.monitor

import science.atlarge.grade10.monitor.nvml.NvidiaGpuParser
import science.atlarge.grade10.monitor.nvml.NvidiaGpusUtilizationData
import science.atlarge.grade10.monitor.procfs.*
import java.io.File
import java.nio.file.Path

class MonitorOutputParser private constructor(monitorOutputDirectory: Path) {

    private val procfsFiles = monitorOutputDirectory.toFile().walk()
            .filter(File::isFile)
            .filter { it.name.startsWith("proc-") }
            .toList()
    private val nvidiaFiles = monitorOutputDirectory.toFile().walk()
            .filter(File::isFile)
            .filter { it.name.startsWith("nvidia-") }
            .toList()

    fun parse(): MonitorOutput {
        val cpuData = parseProcStat()
        val netData = parseProcNetDev()
        val diskData = parseProcDiskstats()
        val gpuData = if (nvidiaFiles.isNotEmpty()) {
            parseNvidiaGpu()
        } else {
            null
        }

        val hostnames = cpuData.keys
        require(
                netData.keys.size == hostnames.size &&
                diskData.keys.size == hostnames.size &&
                hostnames.containsAll(netData.keys) &&
                hostnames.containsAll(diskData.keys)
        ) {
            "CPU, network, and disk data must be available for all monitored hosts"
        }

        val machineData = hostnames.map { hostname ->
            MachineUtilizationData(hostname, cpuData.getValue(hostname), netData.getValue(hostname),
                    diskData.getValue(hostname), gpuData?.get(hostname))
        }

        return MonitorOutput(machineData)
    }

    private fun parseProcStat(): Map<String, CpuUtilizationData> {
        val parser = ProcStatParser()
        return procfsFiles.asSequence()
                .filter { it.name.startsWith("proc-stat-") }
                .map { it.name.removePrefix("proc-stat-") to parser.parse(it) }
                .toMap()
    }

    private fun parseProcNetDev(): Map<String, NetworkUtilizationData> {
        val parser = ProcNetDevParser()
        return procfsFiles.asSequence()
                .filter { it.name.startsWith("proc-net-dev-") }
                .map { it.name.removePrefix("proc-net-dev-") to parser.parse(it) }
                .toMap()
    }

    private fun parseProcDiskstats(): Map<String, DisksUtilizationData> {
        val parser = ProcDiskstatsParser()
        return procfsFiles.asSequence()
                .filter { it.name.startsWith("proc-diskstats-") }
                .map { it.name.removePrefix("proc-diskstats-") to parser.parse(it) }
                .toMap()
    }

    private fun parseNvidiaGpu(): Map<String, NvidiaGpusUtilizationData> {
        return nvidiaFiles.asSequence()
                .map { it.name.removePrefix("nvidia-") to NvidiaGpuParser.parse(it) }
                .toMap()
    }

    companion object {

        fun parseDirectory(monitorOutputDirectory: Path): MonitorOutput {
            return MonitorOutputParser(monitorOutputDirectory).parse()
        }

    }

}
