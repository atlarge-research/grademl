package science.atlarge.grade10.simulation

import com.esotericsoftware.kryo.util.IdentityObjectIntMap
import science.atlarge.grade10.model.execution.Phase
import science.atlarge.grade10.util.FractionalTimesliceCount

class PhaseExecutionSimulationResult(
        private val rootPhaseId: PhaseId,
        private val phaseIdMap: IdentityObjectIntMap<Phase>,
        private val phaseStartTimes: DoubleArray,
        private val phaseEndTimes: DoubleArray
) {

    fun phaseToIndex(phase: Phase): Int = phaseIdMap.get(phase, -1) - rootPhaseId

    fun startTime(index: Int): FractionalTimesliceCount = phaseStartTimes[index]
    fun startTime(phase: Phase) = startTime(phaseToIndex(phase))

    fun endTime(index: Int): FractionalTimesliceCount = phaseEndTimes[index]
    fun endTime(phase: Phase) = endTime(phaseToIndex(phase))

    val totalDuration: FractionalTimesliceCount
        get() = phaseEndTimes[0]

}