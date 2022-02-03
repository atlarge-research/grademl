package science.atlarge.grade10.cli.data

import science.atlarge.grade10.cli.util.PhaseList
import science.atlarge.grade10.model.execution.Phase
import science.atlarge.grade10.simulation.PhaseExecutionSimulationResult
import java.io.File

object SimulatedPhaseListWriter {

    const val FILENAME = "simulated-phase-list.tsv"

    fun output(
            outFile: File,
            simulatedPhaseExecutionResult: PhaseExecutionSimulationResult,
            rootPhase: Phase,
            phases: Iterable<Phase>,
            phaseList: PhaseList
    ) {
        val allPhases = phases.toSet() + rootPhase
        require(allPhases.all { it.path in rootPhase.path }) {
            "All phases in phase list must be a child of the provided root phase"
        }

        outFile.bufferedWriter().use { writer ->
            writer.appendln("phase.id\tphase.type.id\tphase.path\tparent.phase.id\tdepth\tsimulated.start.time.slice\tsimulated.end.time.slice.inclusive\tcanonical.index")
            val phasesInOrder = createCanonicalOrder(rootPhase, allPhases)
            phasesInOrder.forEachIndexed { index, phase ->
                val phaseId = phaseList.phaseToIdentifier(phase)
                val parentPhaseId = if (phase === rootPhase) phaseId else phaseList.phaseToIdentifier(phase.parent!!)
                val depth = phase.path.pathComponents.size - rootPhase.path.pathComponents.size
                writer.apply {
                    append(phaseId)
                    append('\t')
                    append(phaseList.phaseTypeToIdentifier(phase.type))
                    append('\t')
                    append(phase.path.toString())
                    append('\t')
                    append(parentPhaseId)
                    append('\t')
                    append(depth.toString())
                    append('\t')
                    append(simulatedPhaseExecutionResult.startTime(phase).toString())
                    append('\t')
                    append(simulatedPhaseExecutionResult.endTime(phase).toString())
                    append('\t')
                    appendln(index.toString())

                }
            }
        }
    }

    private fun createCanonicalOrder(rootPhase: Phase, phases: Iterable<Phase>): List<Phase> {
        fun collectInOrder(p: Phase): List<Phase> {
            val subphases = p.subphases.values.sortedWith(compareBy(Phase::firstTimeslice, Phase::name))
            return listOf(p) + subphases.flatMap(::collectInOrder)
        }
        val phaseSet = phases.toSet()
        return collectInOrder(rootPhase).filter { it in phaseSet }
    }

}