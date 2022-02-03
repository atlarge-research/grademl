package science.atlarge.grade10.cli.data

import science.atlarge.grade10.Grade10JobResult
import science.atlarge.grade10.cli.util.PhaseList
import science.atlarge.grade10.model.execution.PhaseType
import java.io.File

object PhaseTypeListWriter {

    const val FILENAME = "phase-type-list.tsv"

    fun output(
            outFile: File,
            jobResult: Grade10JobResult,
            rootPhaseType: PhaseType,
            phaseTypes: Iterable<PhaseType>,
            phaseList: PhaseList
    ) {
        val allPhaseTypes = phaseTypes.toSet() + rootPhaseType
        require(allPhaseTypes.all { it.path in rootPhaseType.path }) {
            "All phase types in phase list must be a child of the provided root phase type"
        }

        outFile.bufferedWriter().use { writer ->
            writer.appendln("phase.type.id\tphase.type.path\tparent.phase.type.id\tdepth")
            val phaseTypesById = allPhaseTypes.map { phaseList.phaseTypeToIdentifier(it) to it }
                    .sortedBy { it.second.path }
            for ((phaseTypeId, phaseType) in phaseTypesById) {
                val parentPhaseId =
                        if (phaseType === rootPhaseType) phaseTypeId
                        else phaseList.phaseTypeToIdentifier(phaseType.parent!!)
                val depth = phaseType.path.pathComponents.size - rootPhaseType.path.pathComponents.size
                writer.apply {
                    append(phaseTypeId)
                    append('\t')
                    append(phaseType.path.toString())
                    append('\t')
                    append(parentPhaseId)
                    append('\t')
                    appendln(depth.toString())
                }
            }
        }
    }

}