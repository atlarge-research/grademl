package science.atlarge.grademl.input.resource_monitor

import science.atlarge.grademl.core.input.InputSource
import science.atlarge.grademl.core.models.*
import science.atlarge.grademl.input.resource_monitor.procfs.CpuUtilizationData
import science.atlarge.grademl.input.resource_monitor.procfs.DiskUtilizationData
import science.atlarge.grademl.input.resource_monitor.procfs.NetworkUtilizationData
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.system.exitProcess

object ResourceMonitor : InputSource {

    override fun parseJobData(
        jobDataDirectories: Iterable<Path>,
        unifiedExecutionModel: ExecutionModel,
        unifiedResourceModel: ResourceModel,
        jobEnvironment: Environment
    ): Boolean {
        // Find Resource Monitor metric directories
        val resourceMonitorMetricDirectories = jobDataDirectories
            .map { it.resolve("metrics").resolve("resource-monitor") }
            .filter { it.toFile().isDirectory }
        if (resourceMonitorMetricDirectories.isEmpty()) return false

        // Parse Resource Monitor metrics
        val resourceMonitorMetrics = ResourceMonitorParser.parseFromDirectories(resourceMonitorMetricDirectories)

        // Add a top-level resource for the cluster
        val clusterResource = unifiedResourceModel.addResource("cluster")
        // Add a resource for each machine
        val machineResources = resourceMonitorMetrics.hostnames.associateWith { hostname ->
            unifiedResourceModel.addResource(
                name = "machine",
                tags = mapOf("hostname" to hostname),
                metadata = mapOf(CommonMetadata.MACHINE_ID to hostname),
                parent = clusterResource
            )
        }
        // Add resource-specific metrics to the resource model
        addCpuUtilizationToResourceModel(
            resourceMonitorMetrics.cpuUtilizationData,
            unifiedResourceModel,
            machineResources
        )
        addNetworkUtilizationToResourceModel(
            resourceMonitorMetrics.networkUtilizationData,
            unifiedResourceModel,
            machineResources
        )
        addDiskUtilizationToResourceModel(
            resourceMonitorMetrics.diskUtilizationData,
            unifiedResourceModel,
            machineResources
        )

        return true
    }

    private fun addCpuUtilizationToResourceModel(
        cpuUtilizationData: Map<String, CpuUtilizationData>,
        resourceModel: ResourceModel,
        machineResources: Map<String, Resource>
    ) {
        for ((hostname, cpuUtilization) in cpuUtilizationData) {
            // Create metric for total CPU utilization and add it as a resource
            val cpuResource = resourceModel.addResource(
                name = "cpu",
                parent = machineResources[hostname]!!
            )
            cpuResource.addMetric(
                name = "utilization",
                data = MetricData(
                    cpuUtilization.timestamps,
                    cpuUtilization.totalCoreUtilization,
                    cpuUtilization.numCpuCores.toDouble()
                )
            )
            cpuResource.addMetric(
                name = "cores-fully-utilized",
                data = MetricData(
                    cpuUtilization.timestamps,
                    DoubleArray(cpuUtilization.coresFullyUtilized.size) {
                        cpuUtilization.coresFullyUtilized[it].toDouble()
                    },
                    cpuUtilization.numCpuCores.toDouble()
                )
            )
            // Create metrics for each individual CPU core and add them as resources
            for (coreId in 0 until cpuUtilization.numCpuCores) {
                val coreResource = resourceModel.addResource(
                    name = "core",
                    tags = mapOf("id" to coreId.toString()),
                    parent = cpuResource
                )
                coreResource.addMetric(
                    name = "utilization",
                    data = MetricData(
                        cpuUtilization.timestamps,
                        cpuUtilization.coreUtilization[coreId],
                        1.0
                    )
                )
            }
        }
    }

    private fun addNetworkUtilizationToResourceModel(
        networkUtilizationData: Map<String, NetworkUtilizationData>,
        resourceModel: ResourceModel,
        machineResources: Map<String, Resource>
    ) {
        // Enumerate over each network interface in the monitored cluster
        for ((hostname, networkUtilization) in networkUtilizationData) {
            for (ifaceIndex in 0 until networkUtilization.interfaceIds.size) {
                // Create a resource for the network interface
                val ifaceResource = resourceModel.addResource(
                    name = "network",
                    tags = mapOf("iface" to networkUtilization.interfaceIds[ifaceIndex]),
                    parent = machineResources[hostname]!!
                )
                // Add metrics for incoming and outgoing traffic
                ifaceResource.addMetric(
                    name = "bytes-received",
                    data = MetricData(
                        timestamps = networkUtilization.timestamps,
                        values = networkUtilization.bytesReceived[ifaceIndex],
                        maxValue = 1e9
                    )
                )
                ifaceResource.addMetric(
                    name = "bytes-sent", MetricData(
                        timestamps = networkUtilization.timestamps,
                        values = networkUtilization.bytesSent[ifaceIndex],
                        maxValue = 1e9
                    )
                )
            }
        }
    }

    private fun addDiskUtilizationToResourceModel(
        diskUtilizationData: Map<String, DiskUtilizationData>,
        resourceModel: ResourceModel,
        machineResources: Map<String, Resource>
    ) {
        // Enumerate over each disk device in the monitored cluster
        for ((hostname, diskUtilization) in diskUtilizationData) {
            for (deviceIndex in 0 until diskUtilization.deviceIds.size) {
                // Create a resource for the disk device
                val deviceResource = resourceModel.addResource(
                    name = "disk",
                    tags = mapOf("device" to diskUtilization.deviceIds[deviceIndex]),
                    parent = machineResources[hostname]!!
                )
                // Add metrics for bytes read/written, read/write time, etc.
                deviceResource.addMetric(
                    name = "bytes-read",
                    data = MetricData(
                        timestamps = diskUtilization.timestamps,
                        values = diskUtilization.bytesRead[deviceIndex],
                        maxValue = 1e8
                    )
                )
                deviceResource.addMetric(
                    name = "bytes-written",
                    data = MetricData(
                        timestamps = diskUtilization.timestamps,
                        values = diskUtilization.bytesWritten[deviceIndex],
                        maxValue = 1e8
                    )
                )
                deviceResource.addMetric(
                    name = "read-time",
                    data = MetricData(
                        timestamps = diskUtilization.timestamps,
                        values = diskUtilization.readTimeFraction[deviceIndex],
                        maxValue = 1.0
                    )
                )
                deviceResource.addMetric(
                    name = "write-time",
                    data = MetricData(
                        timestamps = diskUtilization.timestamps,
                        values = diskUtilization.writeTimeFraction[deviceIndex],
                        maxValue = 1.0
                    )
                )
                diskUtilization.totalTimeSpentFraction[deviceIndex]?.let { totalTimeSpentFraction ->
                    deviceResource.addMetric(
                        name = "total-utilization",
                        data = MetricData(
                            timestamps = diskUtilization.timestamps,
                            values = totalTimeSpentFraction,
                            maxValue = 1.0
                        )
                    )
                }
            }
        }
    }

}

// Wrapper for testing the metric parser
fun main(args: Array<String>) {
    if (args.isEmpty() || args[0] == "--help") {
        println("Arguments: <jobLogDirectory> [...]")
        exitProcess(if (args.isEmpty()) -1 else 0)
    }

    val resourceModel = ResourceModel()
    val foundResourceMonitorMetrics = ResourceMonitor.parseJobData(
        args.map { Paths.get(it) },
        ExecutionModel(),
        resourceModel,
        Environment()
    )
    require(foundResourceMonitorMetrics) {
        "Cannot find Resource Monitor logs in any of the given jobLogDirectories"
    }
    println("Resource model extracted from Resource Monitor logs:")

    fun printMetric(metric: Metric, indent: String) {
        val metricData = metric.data
        val minTimestamp = metricData.timestamps.first().let {
            "%d.%09d".format(it / 1_000_000_000, it % 1_000_000_000)
        }
        val maxTimestamp = metricData.timestamps.last().let {
            "%d.%09d".format(it / 1_000_000_000, it % 1_000_000_000)
        }
        val valueStats = if (metricData.values.isNotEmpty()) {
            val minValue = metricData.values.minOrNull()
            val avgValue = metricData.values.average()
            val maxValue = metricData.values.maxOrNull()
            "$minValue / $avgValue / $maxValue"
        } else {
            "(none)"
        }


        println("$indent:${metric.name}")
        println("$indent    Timestamps:            [$minTimestamp, $maxTimestamp]")
        println("$indent    Values (min/avg/max):  $valueStats")
        println("$indent    Limit value:           ${metricData.maxValue}")
    }

    fun printResource(resource: Resource, indent: String) {
        println("$indent/${resource.identifier}")
        for (metric in resource.metrics.sortedBy { it.name }) {
            printMetric(metric, "$indent  ")
        }
        for (childResource in resource.children.sortedBy { it.identifier }) {
            printResource(childResource, "$indent  ")
        }
    }
    for (topLevelResource in resourceModel.rootResource.children.sortedBy { it.identifier }) {
        printResource(topLevelResource, "  ")
    }
}