package science.atlarge.grade10.monitor

import science.atlarge.grade10.model.resources.MetricClass
import science.atlarge.grade10.model.resources.ResourceTypeRepeatability
import science.atlarge.grade10.model.resources.buildResourceModelSpecification

object PerfMonitorResourceModel {

    val MODEL = buildResourceModelSpecification {
        newSubresourceType(ROOT_NAME) {
            description = "Root of the perf-monitor resource model representing a set of monitored machines"

            newSubresourceType(MACHINE_NAME, ResourceTypeRepeatability.Many("hostname")) {
                newMetricType(TOTAL_CPU_UTILIZATION_NAME, MetricClass.CONSUMABLE)

                newSubresourceType(CPU_NAME, ResourceTypeRepeatability.Many("core")) {
                    newMetricType(CPU_CORE_UTILIZATION_NAME, MetricClass.CONSUMABLE)
                }

                newSubresourceType(NETWORK_NAME, ResourceTypeRepeatability.Many("interface")) {
                    newMetricType(NETWORK_BYTES_RECEIVED_NAME, MetricClass.CONSUMABLE)
                    newMetricType(NETWORK_BYTES_TRANSMITTED_NAME, MetricClass.CONSUMABLE)
                }

                newSubresourceType(DISK_NAME, ResourceTypeRepeatability.Many("disk")) {
                    newMetricType(DISK_BYTES_READ, MetricClass.CONSUMABLE)
                    newMetricType(DISK_BYTES_WRITTEN, MetricClass.CONSUMABLE)
                    newMetricType(DISK_UTILIZATION, MetricClass.CONSUMABLE)
//                    newMetricType(DISK_READ_TIME, MetricClass.CONSUMABLE)
//                    newMetricType(DISK_WRITE_TIME, MetricClass.CONSUMABLE)
                }

                newSubresourceType(GPU_NAME, ResourceTypeRepeatability.Many("gpu_id")) {
                    newMetricType(GPU_COMPUTE_FRACTION, MetricClass.CONSUMABLE)
                    newMetricType(GPU_MEMORY_FRACTION, MetricClass.CONSUMABLE)
                    newMetricType(GPU_PCIE_TX_THROUGHPUT, MetricClass.CONSUMABLE)
                    newMetricType(GPU_PCIE_RX_THROUGHPUT, MetricClass.CONSUMABLE)
                }
            }
        }
    }

    const val ROOT_NAME = "perf-monitor"
    const val MACHINE_NAME = "machine"
    const val CPU_NAME = "cpu"
    const val NETWORK_NAME = "network"
    const val DISK_NAME = "disk"
    const val GPU_NAME = "gpu"

    const val TOTAL_CPU_UTILIZATION_NAME = "total-cpu-utilization"
    const val CPU_CORE_UTILIZATION_NAME = "utilization"
    const val NETWORK_BYTES_RECEIVED_NAME = "bytes-received"
    const val NETWORK_BYTES_TRANSMITTED_NAME = "bytes-transmitted"
    const val DISK_BYTES_READ = "bytes-read"
    const val DISK_BYTES_WRITTEN = "bytes-written"
    const val DISK_UTILIZATION = "utilization-fraction"
    const val DISK_READ_TIME = "read-time"
    const val DISK_WRITE_TIME = "write-time"
    const val GPU_COMPUTE_FRACTION = "compute-fraction"
    const val GPU_MEMORY_FRACTION = "memory-fraction"
    const val GPU_PCIE_TX_THROUGHPUT = "pcie-tx-throughput"
    const val GPU_PCIE_RX_THROUGHPUT = "pcie-rx-throughput"

}
