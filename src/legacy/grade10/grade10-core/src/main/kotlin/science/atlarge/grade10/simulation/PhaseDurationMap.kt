package science.atlarge.grade10.simulation

import com.esotericsoftware.kryo.util.IdentityObjectIntMap
import science.atlarge.grade10.model.execution.Phase

class PhaseDurationMap private constructor(
        val rootPhase: Phase,
        private val durations: DoubleArray,
        private val phaseIdMap: IdentityObjectIntMap<Phase>,
        private val phaseTreeSizes: IntArray,
        private val phaseDurationOverrideBlocked: BooleanArray
) {

    constructor(
            rootPhase: Phase,
            phaseIdMap: IdentityObjectIntMap<Phase>,
            phaseTreeSizes: IntArray,
            phaseDurationOverrideBlocked: BooleanArray
    ) : this(
            rootPhase,
            DoubleArray(phaseTreeSizes[phaseIdMap.get(rootPhase, -1)]) { Double.POSITIVE_INFINITY },
            phaseIdMap,
            phaseTreeSizes,
            phaseDurationOverrideBlocked
    )

    private val minId = phaseIdMap.get(rootPhase, -1)
    private val maxId = minId + phaseTreeSizes[minId] - 1

    fun isDefinedFor(phase: Phase) = isDefinedFor(phaseIdMap.get(phase, -1))
    fun isDefinedFor(phaseId: PhaseId) = phaseId in minId..maxId

    operator fun get(phase: Phase) = durations[phaseIdMap.get(phase, -1) - minId]
    operator fun get(phaseId: PhaseId) = durations[phaseId - minId]

    operator fun set(phase: Phase, duration: Double) {
        this[phaseIdMap.get(phase, -1)] = duration
    }
    operator fun set(phaseId: PhaseId, duration: Double) {
        if (canOverridePhaseDuration(phaseId)) {
            durations[phaseId - minId] = duration
        }
    }

    fun canOverridePhaseDuration(phase: Phase) = canOverridePhaseDuration(phaseIdMap.get(phase, -1))
    fun canOverridePhaseDuration(phaseId: PhaseId): Boolean {
        return !phaseDurationOverrideBlocked[phaseId]
    }

    fun copy() = PhaseDurationMap(rootPhase, durations.copyOf(), phaseIdMap, phaseTreeSizes,
            phaseDurationOverrideBlocked)

    fun copyRangeFrom(other: PhaseDurationMap) {
        copyRangeFrom(other, other.rootPhase)
    }
    fun copyRangeFrom(other: PhaseDurationMap, phase: Phase) {
        copyRangeFrom(other, phaseIdMap.get(phase, -1))
    }
    fun copyRangeFrom(other: PhaseDurationMap, phaseId: PhaseId) {
        require(other.phaseIdMap === this.phaseIdMap)
        require(isDefinedFor(phaseId) && other.isDefinedFor(phaseId))
        val count = phaseTreeSizes[phaseId]
        val thisStart = phaseId - minId
        val otherStart = phaseId - other.minId
        System.arraycopy(other.durations, otherStart, durations, thisStart, count)
    }

    fun subset(newRootId: PhaseId) = subset(phaseIdMap.findKey(newRootId))
    fun subset(newRoot: Phase): PhaseDurationMap {
        val rootId = phaseIdMap.get(newRoot, -1)
        val treeSize = phaseTreeSizes[rootId]
        val newDurations = durations.copyOfRange(rootId, rootId + treeSize)
        return PhaseDurationMap(newRoot, newDurations, phaseIdMap, phaseTreeSizes, phaseDurationOverrideBlocked)
    }

}