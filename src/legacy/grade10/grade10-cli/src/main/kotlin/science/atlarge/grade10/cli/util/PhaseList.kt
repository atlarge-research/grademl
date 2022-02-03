package science.atlarge.grade10.cli.util

import science.atlarge.grade10.model.Path
import science.atlarge.grade10.model.execution.ExecutionModel
import science.atlarge.grade10.model.execution.Phase
import science.atlarge.grade10.model.execution.PhaseType
import science.atlarge.grade10.util.collectTreeNodes
import java.nio.file.Path as JPath

class PhaseList(
        rootPhase: Phase
) {

    private val phases = collectTreeNodes(rootPhase) { it.subphases.values }

    private val pathToIdentifierMap = mutableMapOf<Path, String>()
    private val identifierToPathMap = mutableMapOf<String, Path>()

    init {
        fun add(path: Path, identifier: String) {
            pathToIdentifierMap[path] = identifier
            identifierToPathMap[identifier] = path
        }

        val phaseTypes = phases.map { it.type }.toSet()
        val phaseTypeCount = phaseTypes.size
        val maxPhaseCount = phases.groupBy(Phase::type).map { it.value.size }.max() ?: 0

        phases.groupBy(Phase::type) { it }
                .map { (type, phases) -> type.path to orderSameTypePhases(phases).map { it.path } }
                .sortedBy { it.first }
                .forEachIndexed { typeNum, (typePath, phasePaths) ->
                    val phaseTypeId = "p${padInt(typeNum + 1, phaseTypeCount)}"
                    add(typePath, phaseTypeId)
                    phasePaths.forEachIndexed { phaseNum, phasePath ->
                        if (phasePath != typePath) {
                            add(phasePath, "$phaseTypeId-${padInt(phaseNum + 1, maxPhaseCount)}")
                        }
                    }
                }
    }

    fun phaseToIdentifier(phase: Phase): String = pathToIdentifier(phase.path)

    fun phaseTypeToIdentifier(phaseType: PhaseType): String = pathToIdentifier(phaseType.path)

    fun pathToIdentifier(path: Path): String = pathToIdentifierMap[path]
            ?: throw IllegalArgumentException("No identifier found for path \"$path\"")

    fun identifierToPath(identifier: String): Path = identifierToPathMap[identifier]
            ?: throw IllegalArgumentException("No path found for identifier \"$identifier\"")

    fun writeToFile(outputDirectory: JPath, overwriteIfExists: Boolean = false) {
        writeSelectedPhasesToFile(outputDirectory, phases, overwriteIfExists)
    }

    fun writePhaseAndSubphasesToFile(
            outputDirectory: JPath,
            phase: Phase,
            overwriteIfExists: Boolean = false
    ) {
        writeSelectedPhasesToFile(
                outputDirectory,
                collectTreeNodes(phase) { it.subphases.values },
                overwriteIfExists
        )
    }

    fun writeSelectedPhasesToFile(
            outputDirectory: JPath,
            phases: Iterable<Phase>,
            overwriteIfExists: Boolean = false
    ) {
        if (!overwriteIfExists && outputDirectory.resolve(PHASE_LIST_FILENAME).toFile().exists()) {
            return
        }

        val sortedIdentifiers = phases
                .flatMap { listOf(it.path, it.type.path) }
                .map { pathToIdentifier(it) }
                .toSet()
                .sorted()

        outputDirectory.resolve(PHASE_LIST_FILENAME).toFile().bufferedWriter().use { writer ->
            writer.appendln("id\tpath")
            sortedIdentifiers.forEach { id ->
                val path = identifierToPath(id)
                writer.apply {
                    append(id)
                    append('\t')
                    appendln(path.toString())
                }
            }
        }
    }

    companion object {

        const val PHASE_LIST_FILENAME = "phase-list.tsv"

        fun fromExecutionModel(executionModel: ExecutionModel): PhaseList {
            return PhaseList(executionModel.rootPhase)
        }

        private fun padInt(value: Int, maxValue: Int): String {
            require(value >= 0 && maxValue >= 0)
            return when {
                maxValue >= 1_000_000_000 -> String.format("%010d", value)
                maxValue >= 100_000_000 -> String.format("%09d", value)
                maxValue >= 10_000_000 -> String.format("%08d", value)
                maxValue >= 1_000_000 -> String.format("%07d", value)
                maxValue >= 100_000 -> String.format("%06d", value)
                maxValue >= 10_000 -> String.format("%05d", value)
                maxValue >= 1_000 -> String.format("%04d", value)
                maxValue >= 100 -> String.format("%03d", value)
                maxValue >= 10 -> String.format("%02d", value)
                else -> value.toString()
            }
        }

        private fun orderSameTypePhases(phases: List<Phase>): List<Phase> {
            return phases.sortedWith(compareBy({ it.firstTimeslice }, { it.lastTimeslice }, { it.path }))
        }

    }

}
