package science.atlarge.grade10.cli.commands

import science.atlarge.grade10.cli.CliState
import science.atlarge.grade10.model.Path
import science.atlarge.grade10.model.execution.Phase
import science.atlarge.grade10.util.collectTreeNodes

fun CliState.resolvePhaseOrPrintError(phasePath: String): Phase? {
    return resolvePhaseOrPrintError(Path.parse(phasePath))
}

fun CliState.resolvePhaseOrPrintError(phasePath: Path): Phase? {
    val phase = currentPhase.resolve(phasePath)
    if (phase == null) {
        println("Cannot find phase: ${currentPhase.path.resolve(phasePath)}")
    }
    return phase
}

private val PHASE_GLOB_REGEX = """([^\[\]]+)(\[[^\[\]]+])?""".toRegex()
fun CliState.resolvePhaseGlobOrPrintError(phaseGlob: String): List<Phase> {
    if ('*' !in phaseGlob) {
        return resolvePhaseOrPrintError(phaseGlob)?.let { listOf(it) } ?: emptyList()
    }

    val path = Path.parse(phaseGlob)
    for (comp in path.pathComponents) {
        if (comp == "**" || (PHASE_GLOB_REGEX.matches(comp) && "**" !in comp)) continue
        println("Can not parse component of path expression: $comp")
        return emptyList()
    }

    var matches = setOf(currentPhase)
    path.pathComponents.forEachIndexed { i, comp ->
        when (comp) {
            "." -> {
                // No change
            }
            ".." -> {
                matches = matches.mapNotNull { it.parent }.toSet()
            }
            "**" -> {
                matches = matches.flatMap { p ->
                    collectTreeNodes(p) { it.subphases.values }
                }.toSet()
            }
            else -> {
                val parsedComp = PHASE_GLOB_REGEX.matchEntire(comp)!!
                val phaseTypeGlob = parsedComp.groupValues[1]
                val phaseTypeRegex = phaseTypeGlob.split("*")
                        .joinToString(separator = """.*""") { Regex.escape(it) }
                        .toRegex()
                val matchingPhaseTypes = matches.flatMap { it.subphases.values }
                        .filter { phaseTypeRegex.matches(it.type.name) }
                when {
                    parsedComp.groupValues[2].isNotEmpty() -> {
                        val instanceIdRegex = parsedComp.groupValues[2]
                                .trimStart('[')
                                .trimEnd(']')
                                .split("*")
                                .joinToString(separator = """.*""") { Regex.escape(it) }
                                .toRegex()
                        matches = matchingPhaseTypes
                                .filter { p -> p.type.repeatability.isRepeatable &&
                                        instanceIdRegex.matches(p.instanceId) }
                                .toSet()
                    }
                    phaseTypeGlob.endsWith("*") -> matches = matchingPhaseTypes.toSet()
                    else -> matches = matchingPhaseTypes.filter { !it.type.repeatability.isRepeatable }.toSet()
                }
            }
        }

        if (matches.isEmpty()) {
            val pathComps = path.pathComponents.subList(0, i + 1)
            val currentPath = Path(currentPhase.path.pathComponents + pathComps, false)
            println("Cannot find phase matching: $currentPath")
            return emptyList()
        }
    }

    return matches.toList()
}

fun CliState.resolvePhaseGlobsOrPrintError(paths: List<String>, recursive: Boolean = false): List<Phase> {
    val phases = mutableListOf<Phase>()
    if (paths.isEmpty()) {
        phases.add(currentPhase)
    } else {
        for (path in paths) {
            val newPhases = resolvePhaseGlobOrPrintError(path)
            if (newPhases.isEmpty()) return emptyList()
            phases.addAll(newPhases)
        }
    }

    return if (recursive) {
        phases.flatMap { p -> collectTreeNodes(p) { it.subphases.values } }.distinct()
    } else {
        phases.distinct()
    }
}