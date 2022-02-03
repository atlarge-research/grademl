package science.atlarge.grade10.examples.tensorflow

import science.atlarge.grade10.Grade10Platform
import science.atlarge.grade10.Grade10PlatformJob
import science.atlarge.grade10.analysis.attribution.*
import science.atlarge.grade10.model.PhaseToResourceMapping
import science.atlarge.grade10.model.PhaseToResourceMappingEntry
import science.atlarge.grade10.model.execution.*
import science.atlarge.grade10.model.resources.MetricType
import science.atlarge.grade10.model.resources.ResourceModel
import science.atlarge.grade10.model.resources.mergeResourceModels
import science.atlarge.grade10.monitor.MonitorOutput
import science.atlarge.grade10.monitor.MonitorOutputParser
import science.atlarge.grade10.records.EventRecord
import science.atlarge.grade10.records.RecordStore
import science.atlarge.grade10.util.Time
import java.io.File
import java.nio.file.Path as JPath

class TensorFlowJob(
        override val inputDirectories: List<JPath>,
        override val outputDirectory: JPath
) : Grade10PlatformJob {

    override val platform: Grade10Platform
        get() = TensorFlowPlatform

    private val records = RecordStore()

    override fun parseInput(T: Time): Pair<ExecutionModel, ResourceModel> {
        val (executionModel, tensorFlowResourceModel) = parseTensorFlowLogs(T)
        val perfMonitorResourceModel = parseResourceMonitorOutput(T)
        val resourceModel = mergeResourceModels(listOf(tensorFlowResourceModel, perfMonitorResourceModel))
        return executionModel to resourceModel
    }

    private fun parseTensorFlowLogs(T: Time): Pair<ExecutionModel, ResourceModel> {
        val tensorFlowLogsDir = inputDirectories.find { it.resolve("tensorflow-logs").toFile().exists() }!!
                .resolve("tensorflow-logs")
        // Parse TensorFlow logs for records
        val inputData = tensorFlowLogsDir
                .toFile()
                .walk()
                .filter(File::isFile)
                .map(File::inputStream)
                .toList()
        val logParser = TensorFlowLogFileParser()
        inputData.forEach { inputFile -> logParser.extractAll(inputFile) { records.add(it) } }

        // TODO: Fix logging of training sub-steps
        records.removeIf {
            if (it is EventRecord) {
                val phase = it.tags["phase"]
                phase == "TrainLocal" || phase == "TrainReduce"
            } else false
        }

        // Extract PhaseRecords from pairs of EventRecords
        PhaseRecordDerivationRule(TensorFlowExecutionModel.specification).modify(records)

        // Derive the start and end time of phases from their children
        ParentPhaseRecordDerivationRule(TensorFlowExecutionModel.specification.rootPhaseType).modify(records)

        // Build the execution model
        val executionModel = jobExecutionModelFromRecords(records, TensorFlowExecutionModel.specification, T)

        // Build the TensorFlow resource model
        val resourceModel = TensorFlowResourceModel.fromRecords(records, T)

        return Pair(executionModel, resourceModel)
    }

    private fun parseResourceMonitorOutput(T: Time): ResourceModel {
        val resourceMonitorDir = inputDirectories.find { it.resolve("resource-monitor").toFile().exists() }!!
                .resolve("resource-monitor")
        val monitorDataCache = resourceMonitorDir.parent.resolve("resource-monitor.bin.gz")
        // Check if the perf monitor output is cached, otherwise parse and cache it
        val cachedMonitorData = if (monitorDataCache.toFile().exists()) {
            MonitorOutput.readFromFile(monitorDataCache.toFile())
        } else {
            null
        }
        val monitorData = cachedMonitorData ?: MonitorOutputParser.parseDirectory(resourceMonitorDir)
        if (cachedMonitorData == null) {
            monitorData.writeToFile(monitorDataCache.toFile())
        }
        return monitorData.toResourceModel(T, includePerCoreUtilization = false,
                interfaceSelectionPredicate = { iface, _ -> iface.startsWith("ib") },
                interfaceIdAndHostnameToCapacity = { _, _ ->
                    // TODO: DO NOT HARDCODE THESE RESOURCE UTILIZATION LIMITS
                    56e9/8 // 56Gbps => 7 GBps
                })
    }

    override fun resourceAttributionSettings(
            executionModel: ExecutionModel,
            resourceModel: ResourceModel
    ): ResourceAttributionSettings {
        return ResourceAttributionSettings(
                createPhaseToResourceMapping(executionModel, resourceModel),
                TensorFlowResourceAttributionRuleProvider,
                PhaseAwareResourceSamplingStep,
                ResourceAttributionCacheSetting.USE_OR_REFRESH_CACHE
        )
    }

    private fun createPhaseToResourceMapping(
            executionModel: ExecutionModel,
            resourceModel: ResourceModel
    ): PhaseToResourceMapping {
        val mappingEntries = arrayListOf<PhaseToResourceMappingEntry>()

        mappingEntries.add(PhaseToResourceMappingEntry(
                executionModel.rootPhase,
                resources = listOf(resourceModel.rootResource)
        ))

        return PhaseToResourceMapping(executionModel, resourceModel, mappingEntries)
    }
}

private object TensorFlowResourceAttributionRuleProvider : ResourceAttributionRuleProvider() {

    override fun getRuleForBlockingResource(
            phaseType: PhaseType,
            metricType: MetricType
    ): BlockingResourceAttributionRule {
        return BlockingResourceAttributionRule.Full
    }

    override fun getRuleForConsumableResource(
            phaseType: PhaseType,
            metricType: MetricType
    ): ConsumableResourceAttributionRule {
        return ConsumableResourceAttributionRule.Variable(1.0)
    }
}