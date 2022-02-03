package science.atlarge.grademl.core.attribution

import science.atlarge.grademl.core.models.*

class ResourceAttribution(
    executionModel: ExecutionModel,
    resourceModel: ResourceModel,
    attributionRuleProvider: ResourceAttributionRuleProvider,
    resourceAttributionSettings: ResourceAttributionSettings = ResourceAttributionSettings()
) {

    val leafPhases = executionModel.rootPhase.descendants.filter { it.children.isEmpty() }.toSet()
    val phases = executionModel.phases
    val metrics = resourceModel.rootResource.metricsInTree

    private val demandEstimationStep = ResourceDemandEstimationStep(
        metrics,
        leafPhases,
        attributionRuleProvider,
        enableTimeSeriesCompression = resourceAttributionSettings.enableTimeSeriesCompression
    )

    private val upsamplingStep = ResourceUpsamplingStep(
        metrics,
        { metric -> demandEstimationStep.estimatedDemandForMetric(metric)!! },
        enableTimeSeriesCompression = resourceAttributionSettings.enableTimeSeriesCompression
    )

    private val attributionStep = ResourceAttributionStep(
        leafPhases,
        phases,
        metrics,
        attributionRuleProvider,
        { metric -> demandEstimationStep.estimatedDemandForMetric(metric)!! },
        { metric -> upsamplingStep.upsampleMetric(metric)!! },
        enableTimeSeriesCompression = resourceAttributionSettings.enableTimeSeriesCompression,
        enableAttributionResultCaching = resourceAttributionSettings.enableAttributionResultCaching
    )

    fun attributeMetricToPhase(metric: Metric, phase: ExecutionPhase): ResourceAttributionResult {
        return attributionStep.attributeMetricToPhase(metric, phase)
    }

    fun upsampleMetric(metric: Metric): MetricData? {
        return upsamplingStep.upsampleMetric(metric)
    }

    fun estimateDemand(metric: Metric): ResourceDemandEstimate? {
        return demandEstimationStep.estimatedDemandForMetric(metric)
    }

}

sealed class ResourceAttributionResult

class AttributedResourceData(
    val metricData: MetricData,
    val availableCapacity: MetricData
) : ResourceAttributionResult()

object NoAttributedData : ResourceAttributionResult()

class ResourceAttributionSettings(
    val enableTimeSeriesCompression: Boolean = true,
    val enableRuleCaching: Boolean = true,
    val enableAttributionResultCaching: Boolean = true
)