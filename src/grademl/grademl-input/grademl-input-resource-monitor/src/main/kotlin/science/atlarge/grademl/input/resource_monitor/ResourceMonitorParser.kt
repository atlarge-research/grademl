package science.atlarge.grademl.input.resource_monitor

import science.atlarge.grademl.input.resource_monitor.procfs.*
import java.nio.file.Path

class ResourceMonitorParser private constructor(
    private val resourceMonitorMetricDirectories: Iterable<Path>
) {

    private val hostnames = mutableSetOf<String>()
    private val cpuUtilizationData = mutableMapOf<String, CpuUtilizationData>()
    private val networkUtilizationData = mutableMapOf<String, NetworkUtilizationData>()
    private val diskUtilizationData = mutableMapOf<String, DiskUtilizationData>()

    private fun parse(): ResourceMonitorMetrics {
        parseHostnames()
        parseMetricFiles("proc-stat", ProcStatParser, cpuUtilizationData)
        parseMetricFiles("proc-net-dev", ProcNetDevParser, networkUtilizationData)
        parseMetricFiles("proc-diskstats", ProcDiskstatsParser, diskUtilizationData)
        return ResourceMonitorMetrics(hostnames, cpuUtilizationData, networkUtilizationData, diskUtilizationData)
    }

    private fun parseHostnames() {
        // Enumerate metric files and extract hostnames from the filenames
        for (directory in resourceMonitorMetricDirectories) {
            directory.toFile()
                .walk()
                .filter { it.isFile && "-" in it.name }
                .map { it.name.split("-").last() }
                .toCollection(hostnames)
        }
    }

    private fun <T> parseMetricFiles(filePrefix: String, parser: FileParser<T>, outMap: MutableMap<String, T>) {
        // Find relevant metric files
        val allMetricFiles = resourceMonitorMetricDirectories.flatMap { dir ->
            dir.toFile()
                .walk()
                .filter { it.isFile && it.name.startsWith(filePrefix) }
        }
        // Group files by hostname
        val metricFilesByHostname = allMetricFiles.groupBy {
            it.name.split("-").last()
        }
        // Parse all metric files to produce one data structure per hostname
        for ((hostname, metricFiles) in metricFilesByHostname) {
            outMap[hostname] = parser.parse(hostname, metricFiles)
        }
    }

    companion object {

        fun parseFromDirectories(resourceMonitorMetricDirectories: Iterable<Path>): ResourceMonitorMetrics {
            return ResourceMonitorParser(resourceMonitorMetricDirectories).parse()
        }

    }

}

data class ResourceMonitorMetrics(
    val hostnames: Set<String>,
    val cpuUtilizationData: Map<String, CpuUtilizationData>,
    val networkUtilizationData: Map<String, NetworkUtilizationData>,
    val diskUtilizationData: Map<String, DiskUtilizationData>
)