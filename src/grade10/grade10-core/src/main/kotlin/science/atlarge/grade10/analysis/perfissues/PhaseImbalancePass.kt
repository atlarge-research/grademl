package science.atlarge.grade10.analysis.perfissues

import science.atlarge.grade10.analysis.bottlenecks.BottleneckIdentificationResult
import science.atlarge.grade10.model.execution.Phase
import science.atlarge.grade10.model.execution.PhaseType
import science.atlarge.grade10.model.resources.Metric
import science.atlarge.grade10.model.resources.MetricType
import science.atlarge.grade10.simulation.*
import science.atlarge.grade10.util.FractionalTimesliceCount
import science.atlarge.grade10.util.collectTreeNodes

class PhaseImbalancePass(
        private val maxDepth: Int = 1
) : HierarchicalPerformanceIssueIdentificationPass<PhaseImbalancePhaseResult>() {

    override val passName: String
        get() = "Phase Imbalance"
    override val description: String
        get() = "Phase Imbalance"

    private lateinit var bottleneckIdentificationResult: BottleneckIdentificationResult
    private lateinit var phaseExecutionSimulator: PhaseExecutionSimulator

    override fun preprocessInput(input: PerformanceIssueIdentificationPassInput) {
        bottleneckIdentificationResult = input.bottleneckIdentificationResult
        phaseExecutionSimulator = input.phaseExecutionSimulator
    }

    override fun analyzeLeafPhase(leafPhase: Phase): PhaseImbalancePhaseResult {
        val phaseDurationMap = phaseExecutionSimulator.newPhaseDurationMap(leafPhase)
        val simulatedDuration = leafPhase.timesliceDuration - bottleneckIdentificationResult
                .metricTypeBottlenecks[leafPhase].timeNotBottlenecked.toDouble()
        phaseDurationMap[leafPhase] = simulatedDuration
        return PhaseImbalancePhaseResult(simulatedDuration, emptyMap(), phaseDurationMap, emptyMap())
    }

    override fun combineSubphaseResults(
            compositePhase: Phase,
            subphaseResults: Map<Phase, PhaseImbalancePhaseResult>
    ): PhaseImbalancePhaseResult {
        // Create a map of simulated durations per phase
        val phaseDurationMap = phaseExecutionSimulator.newPhaseDurationMap(compositePhase)
        for ((p, r) in subphaseResults) {
            phaseDurationMap.copyRangeFrom(r.cachedPhaseDurations, p)
        }

        // Simulate the duration of the composite phase without addressing imbalance
        val simulatedDuration = phaseExecutionSimulator.simulate(compositePhase, phaseDurationMap).totalDuration

        // Create output variables
        val results = mutableMapOf<PhaseImbalanceSpecification, FractionalTimesliceCount>()
        val resultPhaseDurationOverrides = mutableMapOf<PhaseImbalanceSpecification, PhaseDurationMap>()

        // Propagate results of subphases
        for ((subphase, res) in subphaseResults) {
            val subphaseIsUniqueForType = !subphase.type.repeatability.isRepeatable ||
                    compositePhase.subphasesForType(subphase.type).count() == 1
            for ((spec, overrides) in res.cachedPhaseDurationOverrides) {
                val specIsRelevant = (spec.pivotPhase === subphase && subphaseIsUniqueForType) ||
                        (spec.pivotPhase.path.pathComponents.size - compositePhase.path.pathComponents.size <= maxDepth)
                if (!specIsRelevant) continue

                // Create the phase duration overrides for this subphase
                val newOverrides = phaseDurationMap.copy()
                newOverrides.copyRangeFrom(overrides, subphase)
                // Simulate the impact of resolving the specified imbalance
                val simulatedDurationWithoutImbalance = phaseExecutionSimulator.simulate(compositePhase,
                        newOverrides).totalDuration
                val resultSpec = when {
                    spec.pivotPhase === subphase && subphaseIsUniqueForType ->
                        PhaseImbalanceSpecification(compositePhase, spec.targetPhaseType)
                    else -> spec
                }
                // Add the simulation results
                newOverrides[compositePhase] = simulatedDurationWithoutImbalance
                results[resultSpec] = simulatedDurationWithoutImbalance
                resultPhaseDurationOverrides[resultSpec] = newOverrides
            }
        }
        // Add results for new pivot phase (= the composite phase of this step of the analysis)
        fun collectGroupsOfInterchangeablePhasesOfType(phaseType: PhaseType): List<List<Phase>> {
            require(phaseType.path in compositePhase.type.path)
            require(phaseType !== compositePhase.type)
            var phaseList = listOf(listOf(compositePhase))
            var phaseListType = compositePhase.type
            while (phaseListType !== phaseType) {
                phaseListType = phaseListType.subphaseTypes.values.find { phaseType.path in it.path }!!
                phaseList = if (phaseListType.repeatability.isRepeatable &&
                        !phaseListType.repeatability.areInstancesInterchangeable) {
                    phaseList.flatten().flatMap { p ->
                        p.subphasesForType(phaseListType)
                    }.map { listOf(it) }
                } else {
                    phaseList.map { pList ->
                        pList.flatMap { p ->
                            p.subphasesForType(phaseListType)
                        }
                    }.filter { it.isNotEmpty() }
                }
            }
            return phaseList.filter { it.size > 1 }
        }

        val subTypes = collectTreeNodes(compositePhase.type) { it.subphaseTypes.values }
                .filter { it !== compositePhase.type }
        for (subType in subTypes) {
            // For each subphase type:
            // .. Check if the imbalance of the type has already been computed
            val spec = PhaseImbalanceSpecification(compositePhase, subType)
            if (spec in results) continue

            // .. Find sets of phases that can be balanced
            val phaseSets = collectGroupsOfInterchangeablePhasesOfType(subType)
            if (phaseSets.isEmpty()) continue

            // .. Find the direct child of this composite phase that is (an ancestor of) the target subphase type
            val directSubType = compositePhase.type.subphaseTypes.values.find {
                subType.path in it.path
            }!!

            // .. Construct the phase duration map
            val newPhaseDurationMap = phaseDurationMap.copy()

            var foundOverrides = false
            if (!directSubType.repeatability.areInstancesInterchangeable) {
                // .. Check if existing phase duration overrides can be reused
                for ((sp, r) in subphaseResults) {
                    val overrides = r.cachedPhaseDurationOverrides[PhaseImbalanceSpecification(sp, subType)] ?: continue
                    foundOverrides = true
                    newPhaseDurationMap.copyRangeFrom(overrides)
                }
            }
            if (!foundOverrides) {
                val parentOverridesToClear = hashSetOf<Phase>()
                // .. Balance the identified phase sets
                for (pSet in phaseSets) {
                    val totalDuration = pSet.sumByDouble { phaseDurationMap[it] }
                    val newDuration = totalDuration / pSet.size
                    for (p in pSet) {
                        newPhaseDurationMap[p] = newDuration

                        var par = p.parent!!
                        while (par !== compositePhase && par !in parentOverridesToClear) {
                            parentOverridesToClear.add(par)
                            par = par.parent!!
                        }
                    }
                }

                for (p in parentOverridesToClear) {
                    newPhaseDurationMap[p] = Double.POSITIVE_INFINITY
                }
            }

            val simulatedDurationWithoutImbalance = phaseExecutionSimulator.simulate(compositePhase,
                    newPhaseDurationMap).totalDuration
            newPhaseDurationMap[compositePhase] = simulatedDurationWithoutImbalance
            results[spec] = simulatedDurationWithoutImbalance
            resultPhaseDurationOverrides[spec] = newPhaseDurationMap
        }

        phaseDurationMap[compositePhase] = simulatedDuration
        return PhaseImbalancePhaseResult(simulatedDuration, results, phaseDurationMap, resultPhaseDurationOverrides)
    }

    override fun extractPerformanceIssues(
            phase: Phase,
            results: PhaseImbalancePhaseResult
    ): Iterable<PerformanceIssue> {
        return results.simulatedDurationWithoutImbalance.map { (spec, duration) ->
            PhaseImbalancePerformanceIssue(phase, spec.pivotPhase, spec.targetPhaseType,
                    results.simulatedDuration, duration)
        }
    }

}

data class PhaseImbalanceSpecification(val pivotPhase: Phase, val targetPhaseType: PhaseType = pivotPhase.type)

class PhaseImbalancePhaseResult(
        val simulatedDuration: FractionalTimesliceCount,
        val simulatedDurationWithoutImbalance: Map<PhaseImbalanceSpecification, FractionalTimesliceCount>,
        val cachedPhaseDurations: PhaseDurationMap,
        val cachedPhaseDurationOverrides: Map<PhaseImbalanceSpecification, PhaseDurationMap>
)

class PhaseImbalancePerformanceIssue(
        val rootPhase: Phase,
        val pivotPhase: Phase,
        val targetPhaseType: PhaseType,
        val simulatedDuration: FractionalTimesliceCount,
        val simulatedDurationWithoutBottleneck: FractionalTimesliceCount
) : PerformanceIssue {

    override val affectedPhases: Set<Phase> = setOf(rootPhase)
    override val affectedMetrics: Set<Metric>? = null
    override val affectedMetricTypes: Set<MetricType>? = null

    override val estimatedImpact: FractionalTimesliceCount
        get() = simulatedDuration - simulatedDurationWithoutBottleneck

    override val relativeRealImpact: Double
        get() = estimatedImpact / rootPhase.timesliceDuration

    override val relativeSimulatedImpact: Double
        get() = estimatedImpact / simulatedDuration

    override fun toDisplayString(): String {
        return if (pivotPhase === rootPhase) {
            "Imbalance in \"${targetPhaseType.path}\" phases for simulated execution of phase \"${rootPhase.path}\""
        } else {
            "Imbalance in \"${targetPhaseType.path}\" phases within phase \"${pivotPhase.path}\" " +
                    "for simulated execution of phase \"${rootPhase.path}\""
        }
    }

}
