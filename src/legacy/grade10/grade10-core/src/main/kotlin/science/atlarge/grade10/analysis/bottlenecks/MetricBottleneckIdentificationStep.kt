package science.atlarge.grade10.analysis.bottlenecks

import science.atlarge.grade10.analysis.PhaseHierarchyAnalysis
import science.atlarge.grade10.analysis.PhaseHierarchyAnalysisResult
import science.atlarge.grade10.analysis.PhaseHierarchyAnalysisRule
import science.atlarge.grade10.analysis.attribution.*
import science.atlarge.grade10.model.execution.ExecutionModel
import science.atlarge.grade10.model.execution.Phase
import science.atlarge.grade10.model.resources.Metric
import science.atlarge.grade10.util.TimesliceCount

object MetricBottleneckIdentificationStep {

    fun execute(
            executionModel: ExecutionModel,
            bottleneckIdentificationSettings: BottleneckIdentificationSettings,
            resourceAttributionResult: ResourceAttributionResult
    ): MetricBottleneckIdentificationStepResult {
        return PhaseHierarchyAnalysis.analyze(
                executionModel,
                MetricBottleneckIdentificationRule(
                        bottleneckIdentificationSettings,
                        resourceAttributionResult
                )
        )
    }

}

typealias MetricBottleneckIdentificationStepResult = PhaseHierarchyAnalysisResult<PhaseMetricBottlenecks>

class PhaseMetricBottlenecks(
        val phase: Phase,
        private val metricBottlenecks: Map<Metric, () -> BottleneckStatusIterator>
) {

    val metrics: Set<Metric>
        get() = metricBottlenecks.keys

    private var cachedTimeNotBottlenecked: TimesliceCount = 0L
    private val cachedBottleneckDurations: MutableMap<Metric, TimesliceCount> = mutableMapOf()
    private val cachedUniqueBottleneckDurations: MutableMap<Metric, TimesliceCount> = mutableMapOf()
    private val cachedTotalBottlenecks: BottleneckStatusArray = BottleneckStatusArray(phase.timesliceDuration.toInt())
    private var cacheInitialized = false

    val timeNotBottlenecked: TimesliceCount
        get() {
            initializeCache()
            return cachedTimeNotBottlenecked
        }

    fun timeBottleneckedOnMetric(metric: Metric): TimesliceCount {
        initializeCache()
        return cachedBottleneckDurations[metric]
                ?: throw IllegalArgumentException("No result found for metric \"${metric.path}\"")
    }

    fun timeUniquelyBottleneckedOnMetric(metric: Metric): TimesliceCount {
        initializeCache()
        return cachedUniqueBottleneckDurations[metric]
                ?: throw IllegalArgumentException("No result found for metric \"${metric.path}\"")
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

    fun bottleneckIterator(metric: Metric): BottleneckStatusIterator {
        return metricBottlenecks[metric]?.invoke()
                ?: throw IllegalArgumentException("No result found for metric \"${metric.path}\"")
    }

    private fun initializeCache() {
        if (cacheInitialized) {
            return
        }

        synchronized(this) {
            if (!cacheInitialized) {
                addMetricsToCache()
                cacheInitialized = true
            }
        }
    }

    private fun addMetricsToCache() {
        val metricOrder = metrics.toList()
        val bottleneckDurations = LongArray(metricOrder.size)
        val uniqueBottleneckDurations = LongArray(metricOrder.size)
        val iterators = Array(metricOrder.size) { i -> metricBottlenecks[metricOrder[i]]!!.invoke() }
        for (t in 0 until cachedTotalBottlenecks.size) {
            var uniqueBottleneck = -1
            for (m in 0 until metricOrder.size) {
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
        for (m in 0 until metricOrder.size) {
            cachedBottleneckDurations[metricOrder[m]] = bottleneckDurations[m]
            cachedUniqueBottleneckDurations[metricOrder[m]] = uniqueBottleneckDurations[m]
        }
    }

}

private class MetricBottleneckIdentificationRule(
        private val bottleneckIdentificationSettings: BottleneckIdentificationSettings,
        private val resourceAttributionResult: ResourceAttributionResult
) : PhaseHierarchyAnalysisRule<PhaseMetricBottlenecks> {

    override fun analyzeLeafPhase(leafPhase: Phase): PhaseMetricBottlenecks {
        val phaseAttribution = resourceAttributionResult.resourceAttribution[leafPhase]
        val consumableMetricBottlenecks = phaseAttribution.consumableMetrics
                .map { consumableMetric ->
                    consumableMetric to {
                        val activePhaseDetection = resourceAttributionResult.activePhaseDetection
                        val resourceSampling = resourceAttributionResult.resourceSampling
                        LeafPhaseConsumableMetricBottleneckIterator(
                                leafPhase,
                                consumableMetric,
                                activePhaseDetection.activeIterator(leafPhase),
                                phaseAttribution.iterator(consumableMetric),
                                resourceSampling.sampleIterator(consumableMetric, leafPhase.firstTimeslice,
                                        leafPhase.lastTimeslice),
                                bottleneckIdentificationSettings
                        )
                    }
                }
                .toMap()
        val blockingMetricBottlenecks = phaseAttribution.blockingMetrics
                .map { blockingMetric ->
                    blockingMetric to {
                        LeafPhaseBlockingMetricBottleneckIterator(
                                phaseAttribution.iterator(blockingMetric)
                        )
                    }
                }
                .toMap()
        val metricBottlenecks = consumableMetricBottlenecks + blockingMetricBottlenecks
        return PhaseMetricBottlenecks(leafPhase, metricBottlenecks)
    }

    override fun combineSubphaseResults(
            compositePhase: Phase,
            subphaseResults: Map<Phase, PhaseMetricBottlenecks>
    ): PhaseMetricBottlenecks {
        val phaseMetricPairs = subphaseResults.flatMap { (subphase, results) ->
            results.metrics.map { subphase to it }
        }
        val phasesPerMetric = phaseMetricPairs.groupBy({ it.second }, { it.first })
        val iteratorPerMetric = phasesPerMetric
                .map { (metric, subphases) ->
                    val bottleneckIterators = subphases
                            .map {
                                it to { subphaseResults[it]!!.bottleneckIterator(metric) }
                            }
                            .toMap()
                    metric to {
                        CompositePhaseBottleneckIterator(compositePhase, bottleneckIterators,
                                bottleneckIdentificationSettings.bottleneckPredicate)
                    }
                }
                .toMap()
        return PhaseMetricBottlenecks(compositePhase, iteratorPerMetric)
    }

}

internal class LeafPhaseBlockingMetricBottleneckIterator(
        private val blockingMetricAttributionIterator: BlockingMetricAttributionIterator
) : BottleneckStatusIterator {

    override fun hasNext(): Boolean = blockingMetricAttributionIterator.hasNext()

    override fun nextBottleneckStatus(): BottleneckStatus {
        return if (blockingMetricAttributionIterator.nextIsBlocked()) {
            BottleneckStatusConstants.GLOBAL
        } else {
            BottleneckStatusConstants.NONE
        }
    }

}

internal class LeafPhaseConsumableMetricBottleneckIterator(
        phase: Phase,
        metric: Metric.Consumable,
        phaseActiveIterator: PhaseActiveIterator,
        consumableMetricAttributionIterator: ConsumableMetricAttributionIterator,
        metricSampleIterator: MetricSampleIterator,
        bottleneckIdentificationSettings: BottleneckIdentificationSettings
) : BottleneckStatusIterator {

    private val bottlenecks: BottleneckStatusArray = BottleneckStatusArray(phase.timesliceDuration.toInt())
    var nextIndex = 0

    init {
        val localThresholdFactor = bottleneckIdentificationSettings.localBottleneckThresholdFactor(metric, phase)
        val globalThreshold = bottleneckIdentificationSettings.globalBottleneckThresholdFactor(metric) *
                metric.capacity
        for (i in 0 until bottlenecks.size) {
            consumableMetricAttributionIterator.computeNext()
            val attributedUsage = consumableMetricAttributionIterator.attributedUsage
            val availableCapacity = consumableMetricAttributionIterator.availableCapacity
            val metricSample = metricSampleIterator.nextSample()
            val isActive = phaseActiveIterator.nextIsActive()
            bottlenecks[i] = when {
                !isActive -> BottleneckStatusConstants.NONE
                metricSample >= globalThreshold -> BottleneckStatusConstants.GLOBAL
                attributedUsage >= availableCapacity * localThresholdFactor -> BottleneckStatusConstants.LOCAL
                else -> BottleneckStatusConstants.NONE
            }
        }
    }

    override fun hasNext(): Boolean = nextIndex < bottlenecks.size

    override fun nextBottleneckStatus(): BottleneckStatus {
        val value = bottlenecks[nextIndex]
        nextIndex++
        return value
    }

}

internal class CompositePhaseBottleneckIterator(
        private val phase: Phase,
        private val subphaseBottlenecks: Map<Phase, () -> BottleneckStatusIterator>,
        private val bottleneckPredicate: PerPhaseBottleneckPredicate
) : BottleneckStatusIterator {

    private val phaseList = subphaseBottlenecks.keys
            .filter {
                it.firstTimeslice <= phase.lastTimeslice &&
                        it.lastTimeslice >= phase.firstTimeslice &&
                        it.timesliceDuration > 0
            }
            .toTypedArray()
    private val phaseIndexToActiveIndex = IntArray(phaseList.size) { -1 }
    private val activeIndexToPhaseIndex = IntArray(phaseList.size) { -1 }
    private var activePhaseCount = 0

    private val activePhaseIterators = Array<BottleneckStatusIterator?>(phaseList.size) { null }
    private val activePhaseStatuses = BottleneckStatusArray(phaseList.size)

    private val phaseIndexByStartTimeslice = (0 until phaseList.size).sortedBy { phaseList[it].firstTimeslice }
    private var phasesStarted = 0
    private var nextPhaseStartTimeslice: Long

    private val phaseIndexByEndTimeslice = (0 until phaseList.size).sortedBy { phaseList[it].lastTimeslice }
    private var phasesEnded = 0
    private var nextPhaseEndTimeslice: Long

    private var nextTimeslice = phase.firstTimeslice

    private val phaseAndBottleneckStatusIterator = PhaseAndBottleneckStatusIterator(phaseList, activeIndexToPhaseIndex,
            activePhaseStatuses)

    init {
        while (phasesStarted < phaseList.size) {
            val nextPhaseIndex = phaseIndexByStartTimeslice[phasesStarted]
            val nextPhaseStartTime = phaseList[nextPhaseIndex].firstTimeslice
            if (nextPhaseStartTime >= nextTimeslice)
                break

            val iter = subphaseBottlenecks.getValue(phaseList[nextPhaseIndex])()
            val slicesToSkip = (nextTimeslice - nextPhaseStartTime).toInt()
            repeat(slicesToSkip) {
                iter.nextBottleneckStatus()
            }

            phaseIndexToActiveIndex[nextPhaseIndex] = activePhaseCount
            activeIndexToPhaseIndex[activePhaseCount] = nextPhaseIndex
            activePhaseIterators[activePhaseCount] = iter
            activePhaseCount++
            phasesStarted++
        }
        nextPhaseStartTimeslice = if (phasesStarted < phaseList.size) {
            phaseList[phaseIndexByStartTimeslice[phasesStarted]].firstTimeslice
        } else {
            phase.firstTimeslice - 1
        }
        nextPhaseEndTimeslice = if (phasesEnded < phaseList.size) {
            phaseList[phaseIndexByEndTimeslice[phasesEnded]].lastTimeslice
        } else {
            phase.firstTimeslice - 1
        }
    }

    override fun hasNext(): Boolean = nextTimeslice <= phase.lastTimeslice

    override fun nextBottleneckStatus(): BottleneckStatus {
        // Advance one time slice
        val currentTime = nextTimeslice
        nextTimeslice++
        // - Check if any new phases start
        while (nextPhaseStartTimeslice == currentTime) {
            val startingPhaseIndex = phaseIndexByStartTimeslice[phasesStarted]

            phaseIndexToActiveIndex[startingPhaseIndex] = activePhaseCount
            activeIndexToPhaseIndex[activePhaseCount] = startingPhaseIndex
            activePhaseIterators[activePhaseCount] = subphaseBottlenecks[phaseList[startingPhaseIndex]]!!.invoke()
            activePhaseCount++

            phasesStarted++
            nextPhaseStartTimeslice = if (phasesStarted < phaseIndexByStartTimeslice.size) {
                phaseList[phaseIndexByStartTimeslice[phasesStarted]].firstTimeslice
            } else {
                phase.firstTimeslice - 1
            }
        }
        // - Process all active phases
        for (i in 0 until activePhaseCount) {
            activePhaseStatuses[i] = activePhaseIterators[i]!!.nextBottleneckStatus()
        }
        phaseAndBottleneckStatusIterator.reset(activePhaseCount)
        val combinedStatus = bottleneckPredicate.combineSubPhaseBottlenecks(phaseAndBottleneckStatusIterator)
        // - Check if any phases end
        while (nextPhaseEndTimeslice == currentTime) {
            val endingPhaseIndex = phaseIndexByEndTimeslice[phasesEnded]
            val activeIndex = phaseIndexToActiveIndex[endingPhaseIndex]

            if (activePhaseCount == 0) {
                println("Phase: ${phase.path}")
                println("Phase duration: [${phase.firstTimeslice}, ${phase.lastTimeslice}] => ${phase.timesliceDuration}")
                println("Subphase list: " + phaseList.joinToString(prefix = "[\n", postfix = "\n]", separator = ",\n") { p ->
                    "\t${p.path} [${p.firstTimeslice}, ${p.lastTimeslice}] => ${p.timesliceDuration}" })
                println("phaseIndexToActiveIndex: " + phaseIndexToActiveIndex.joinToString(prefix = "[", postfix = "]") { it.toString() })
                println("activeIndexToPhaseIndex: " + activeIndexToPhaseIndex.joinToString(prefix = "[", postfix = "]") { it.toString() })
                println("phaseIndexByStartTimeslice: " + phaseIndexByStartTimeslice.joinToString(prefix = "[", postfix = "]") { it.toString() })
                println("phasesStarted: $phasesStarted")
                println("nextPhaseStartTimeslice: $nextPhaseStartTimeslice")
                println("phaseIndexByEndTimeslice: " + phaseIndexByEndTimeslice.joinToString(prefix = "[", postfix = "]") { it.toString() })
                println("phasesEnded: $phasesEnded")
                println("nextPhaseEndTimeslice: $nextPhaseEndTimeslice")
                println("currentTime: $currentTime")
            }

            phaseIndexToActiveIndex[endingPhaseIndex] = -1
            if (activeIndex != activePhaseCount - 1) {
                val swapPhaseIndex = activeIndexToPhaseIndex[activePhaseCount - 1]
                phaseIndexToActiveIndex[swapPhaseIndex] = activeIndex
                activeIndexToPhaseIndex[activeIndex] = swapPhaseIndex
                activePhaseIterators[activeIndex] = activePhaseIterators[activePhaseCount - 1]
            }
            activePhaseCount--
            activeIndexToPhaseIndex[activePhaseCount] = -1
            activePhaseIterators[activePhaseCount] = null

            phasesEnded++
            nextPhaseEndTimeslice = if (phasesEnded < phaseIndexByEndTimeslice.size) {
                phaseList[phaseIndexByEndTimeslice[phasesEnded]].lastTimeslice
            } else {
                phase.firstTimeslice - 1
            }
        }

        return combinedStatus
    }

}

private class PhaseAndBottleneckStatusIterator(
        private val phases: Array<Phase>,
        private val activePhaseIndices: IntArray,
        private val activePhaseStatuses: BottleneckStatusArray
) : Iterator<PhaseAndBottleneckStatus> {

    private var pointer = 0
    private var activePhaseCount = 0
    private val phaseBottleneckPair = PhaseAndBottleneckStatus()

    fun reset(activePhaseCount: Int) {
        this.pointer = 0
        this.activePhaseCount = activePhaseCount
    }

    override fun hasNext(): Boolean = pointer < activePhaseCount

    override fun next(): PhaseAndBottleneckStatus {
        val index = activePhaseIndices[pointer]
        phaseBottleneckPair.phase = phases[index]
        phaseBottleneckPair.bottleneckStatus = activePhaseStatuses[pointer]
        pointer++
        return phaseBottleneckPair
    }

}
