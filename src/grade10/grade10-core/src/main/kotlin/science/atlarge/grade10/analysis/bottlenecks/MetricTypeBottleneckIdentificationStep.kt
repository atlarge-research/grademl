package science.atlarge.grade10.analysis.bottlenecks

import science.atlarge.grade10.analysis.PhaseHierarchyAnalysis
import science.atlarge.grade10.analysis.PhaseHierarchyAnalysisResult
import science.atlarge.grade10.analysis.PhaseHierarchyAnalysisRule
import science.atlarge.grade10.analysis.attribution.BlockingMetricAttributionIterator
import science.atlarge.grade10.analysis.attribution.LeafBlockingMetricAttributionIterator
import science.atlarge.grade10.analysis.attribution.MetricSampleIterator
import science.atlarge.grade10.analysis.attribution.ResourceAttributionResult
import science.atlarge.grade10.model.execution.ExecutionModel
import science.atlarge.grade10.model.execution.Phase
import science.atlarge.grade10.model.resources.MetricType
import science.atlarge.grade10.util.TimesliceCount

object MetricTypeBottleneckIdentificationStep {

    fun execute(
            executionModel: ExecutionModel,
            bottleneckIdentificationSettings: BottleneckIdentificationSettings,
            resourceAttributionResult: ResourceAttributionResult,
            metricBottleneckIdentificationResult: MetricBottleneckIdentificationStepResult
    ): MetricTypeBottleneckIdentificationStepResult {
        return PhaseHierarchyAnalysis.analyze(
                executionModel,
                MetricTypeBottleneckIdentificationRule(
                        bottleneckIdentificationSettings,
                        resourceAttributionResult,
                        metricBottleneckIdentificationResult
                )
        )
    }

}

typealias MetricTypeBottleneckIdentificationStepResult = PhaseHierarchyAnalysisResult<PhaseMetricTypeBottlenecks>

class PhaseMetricTypeBottlenecks(
        val phase: Phase,
        private val metricTypeBottlenecks: Map<MetricType, () -> BottleneckStatusIterator>
) {

    val metricTypes: Set<MetricType>
        get() = metricTypeBottlenecks.keys

    private var cachedTimeNotBottlenecked: TimesliceCount = 0L
    private val cachedBottleneckDurations: MutableMap<MetricType, TimesliceCount> = mutableMapOf()
    private val cachedUniqueBottleneckDurations: MutableMap<MetricType, TimesliceCount> = mutableMapOf()
    private val cachedTotalBottlenecks: BottleneckStatusArray = BottleneckStatusArray(phase.timesliceDuration.toInt())
    private var cacheInitialized = false

    val timeNotBottlenecked: TimesliceCount
        get() {
            initializeCache()
            return cachedTimeNotBottlenecked
        }

    fun timeBottleneckedOnMetricType(metricType: MetricType): TimesliceCount {
        initializeCache()
        return cachedBottleneckDurations[metricType]
                ?: throw IllegalArgumentException("No result found for metric type \"${metricType.path}\"")
    }

    fun timeUniquelyBottleneckedOnMetricType(metricType: MetricType): TimesliceCount {
        initializeCache()
        return cachedUniqueBottleneckDurations[metricType]
                ?: throw IllegalArgumentException("No result found for metric type \"${metricType.path}\"")
    }

    fun bottleneckIterator(): BottleneckStatusIterator {
        initializeCache()
        return object : BottleneckStatusIterator {

            val data = cachedTotalBottlenecks
            var nextIndex = 0

            override fun hasNext(): Boolean = nextIndex < data.size

            override fun nextBottleneckStatus(): BottleneckStatus {
                val value = data[nextIndex]
                nextIndex++
                return value
            }

        }
    }

    fun bottleneckIterator(metricType: MetricType): BottleneckStatusIterator {
        return metricTypeBottlenecks[metricType]?.invoke()
                ?: throw IllegalArgumentException("No result found for metric type \"${metricType.path}\"")
    }

    private fun initializeCache() {
        if (cacheInitialized) {
            return
        }

        synchronized(this) {
            if (!cacheInitialized) {
                addMetricTypesToCache()
                cacheInitialized = true
            }
        }
    }

    private fun addMetricTypesToCache() {
        val metricTypeOrder = metricTypes.toList()
        val bottleneckDurations = LongArray(metricTypeOrder.size)
        val uniqueBottleneckDurations = LongArray(metricTypeOrder.size)
        val iterators = Array(metricTypeOrder.size) { i -> metricTypeBottlenecks[metricTypeOrder[i]]!!.invoke() }
        for (t in 0 until cachedTotalBottlenecks.size) {
            var uniqueBottleneck = -1
            for (m in 0 until metricTypeOrder.size) {
                val nextStatus = iterators[m].nextBottleneckStatus()
                if (nextStatus != BottleneckStatusConstants.NONE) {
                    cachedTotalBottlenecks[t] = maxOf(cachedTotalBottlenecks[t], nextStatus)
                    bottleneckDurations[m]++
                    uniqueBottleneck = if (uniqueBottleneck == -1) m else -2
                }
            }
            if (uniqueBottleneck >= 0) {
                uniqueBottleneckDurations[uniqueBottleneck]++
            } else if (uniqueBottleneck == -1) {
                cachedTimeNotBottlenecked++
            }
        }
        for (m in 0 until metricTypeOrder.size) {
            cachedBottleneckDurations[metricTypeOrder[m]] = bottleneckDurations[m]
            cachedUniqueBottleneckDurations[metricTypeOrder[m]] = uniqueBottleneckDurations[m]
        }
    }

}

private class MetricTypeBottleneckIdentificationRule(
        private val bottleneckIdentificationSettings: BottleneckIdentificationSettings,
        private val resourceAttributionResult: ResourceAttributionResult,
        private val metricBottleneckIdentificationResult: MetricBottleneckIdentificationStepResult
) : PhaseHierarchyAnalysisRule<PhaseMetricTypeBottlenecks> {

    override fun analyzeLeafPhase(leafPhase: Phase): PhaseMetricTypeBottlenecks {
        val metricBottlenecks = metricBottleneckIdentificationResult[leafPhase]
        val phaseAttribution = resourceAttributionResult.resourceAttribution[leafPhase]

        val consumableMetricsByType = phaseAttribution.consumableMetrics.groupBy { it.type }
        val unusedConsumableMetricsByType = phaseAttribution.unusedConsumableMetrics.groupBy { it.type }
        val consumableMetricTypeBottlenecks = consumableMetricsByType
                .map { (consumableMetricType, metricsForType) ->
                    val unusedMetricsForType = unusedConsumableMetricsByType[consumableMetricType] ?: emptyList()
                    val unusedMetricThresholds = unusedMetricsForType.map {
                        it to bottleneckIdentificationSettings.globalBottleneckThresholdFactor(it)
                    }.toMap()

                    consumableMetricType to {
                        val iterators = metricsForType.map { metricBottlenecks.bottleneckIterator(it) } +
                                unusedMetricsForType.map {
                                    GlobalConsumableResourceBottleneckIterator(
                                            resourceAttributionResult.resourceSampling.sampleIterator(
                                                    it, leafPhase.firstTimeslice, leafPhase.lastTimeslice
                                            ),
                                            unusedMetricThresholds[it]!!
                                    )
                                }
                        AggregateBottleneckStatusIterator(iterators)
                    }
                }
                .toMap()

        val blockingMetricsByType = phaseAttribution.blockingMetrics.groupBy { it.type }
        val unusedBlockingMetricsByType = phaseAttribution.unusedBlockingMetrics.groupBy { it.type }
        val blockingMetricTypeBottlenecks = blockingMetricsByType
                .map { (blockingMetricType, metricsForType) ->
                    val unusedMetricsForType = unusedBlockingMetricsByType[blockingMetricType] ?: emptyList()

                    blockingMetricType to {
                        val iterators = metricsForType.map { metricBottlenecks.bottleneckIterator(it) } +
                                unusedMetricsForType.map {
                                    LeafPhaseBlockingMetricBottleneckIterator(
                                            LeafBlockingMetricAttributionIterator(leafPhase, it)
                                    )
                                }
                        AggregateBottleneckStatusIterator(iterators)
                    }
                }
                .toMap()

        val metricTypeBottlenecks = blockingMetricTypeBottlenecks + consumableMetricTypeBottlenecks
        return PhaseMetricTypeBottlenecks(leafPhase, metricTypeBottlenecks)
    }

    override fun combineSubphaseResults(
            compositePhase: Phase,
            subphaseResults: Map<Phase, PhaseMetricTypeBottlenecks>
    ): PhaseMetricTypeBottlenecks {
        val phaseMetricPairs = subphaseResults.flatMap { (subphase, results) ->
            results.metricTypes.map { subphase to it }
        }
        val phasesPerMetricType = phaseMetricPairs.groupBy({ it.second }, { it.first })
        val iteratorPerMetricType = phasesPerMetricType
                .map { (metricType, subphases) ->
                    val bottleneckIterators = subphases
                            .map {
                                it to { subphaseResults[it]!!.bottleneckIterator(metricType) }
                            }
                            .toMap()
                    metricType to {
                        CompositePhaseBottleneckIterator(compositePhase, bottleneckIterators,
                                bottleneckIdentificationSettings.bottleneckPredicate)
                    }
                }
                .toMap()
        return PhaseMetricTypeBottlenecks(compositePhase, iteratorPerMetricType)
    }

}

private class GlobalConsumableResourceBottleneckIterator(
        private val sampleIterator: MetricSampleIterator,
        globalBottleneckThresholdFactor: Double
) : BottleneckStatusIterator {

    private val threshold = globalBottleneckThresholdFactor * sampleIterator.capacity

    override fun hasNext(): Boolean = sampleIterator.hasNext()

    override fun nextBottleneckStatus(): BottleneckStatus =
            if (sampleIterator.nextSample() >= threshold) {
                BottleneckStatusConstants.GLOBAL
            } else {
                BottleneckStatusConstants.NONE
            }

}

private class AggregateBottleneckStatusIterator(
        private val bottleneckStatusIterators: List<BottleneckStatusIterator>
) : BottleneckStatusIterator {

    override fun hasNext(): Boolean = bottleneckStatusIterators[0].hasNext()

    override fun nextBottleneckStatus(): BottleneckStatus {
        var isGlobal = true
        var hasBottleneck = false
        bottleneckStatusIterators.forEach {
            when (it.nextBottleneckStatus()) {
                BottleneckStatusConstants.NONE -> {
                    isGlobal = false
                }
                BottleneckStatusConstants.LOCAL -> {
                    hasBottleneck = true
                    isGlobal = false
                }
                BottleneckStatusConstants.GLOBAL -> {
                    hasBottleneck = true
                }
            }
        }
        return when {
            !hasBottleneck -> BottleneckStatusConstants.NONE
            !isGlobal -> BottleneckStatusConstants.LOCAL
            else -> BottleneckStatusConstants.GLOBAL
        }
    }

}
