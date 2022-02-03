package science.atlarge.grade10.cli.commands

import science.atlarge.grade10.cli.CliState
import science.atlarge.grade10.cli.Command
import science.atlarge.grade10.cli.data.PhaseListWriter
import science.atlarge.grade10.cli.data.PhaseTypeListWriter
import science.atlarge.grade10.cli.util.writeRScript
import science.atlarge.grade10.model.execution.ExecutionModelSpecification
import science.atlarge.grade10.model.execution.Phase
import science.atlarge.grade10.model.execution.PhaseType
import science.atlarge.grade10.util.collectTreeNodes
import java.nio.file.Files
import java.nio.file.Path

object PlotSubphasesCommand : Command {

    private const val SCRIPT_FILENAME = "plot-subphases.R"

    override val name: String
        get() = "plot-subphases"
    override val shortHelpMessage: String
        get() = ""
    override val longHelpMessage: String
        get() = ""

    private val usage = "Correct usage: $name [--recursive] [--force] [--exclude-composite] [--depth DEPTH] " +
            "[--exclude-type PHASE_TYPE[,PHASE_TYPE ...]] [path-to-phase]"

    override fun process(arguments: List<String>, cliState: CliState) {
        var recursive = false
        var force = false
        var excludeComposite = false
        val paths = mutableListOf<String>()
        var depth = 1
        var argIndex = 0
        val excludeTypeStrs = mutableSetOf<String>()
        while (argIndex < arguments.size) {
            val arg = arguments[argIndex]
            when (arg) {
                "--recursive" -> recursive = true
                "--force" -> force = true
                "--exclude-composite" -> excludeComposite = true
                "--depth" -> {
                    argIndex++
                    if (argIndex !in arguments.indices) {
                        println("Missing specification of depth after \"--depth\" flag")
                        println(usage)
                        return
                    }
                    val newDepth = arguments[argIndex].toIntOrNull()
                    if (newDepth == null) {
                        println("Failed to parse specified depth: \"${arguments[argIndex]}\"")
                        println(usage)
                        return
                    }
                    if (newDepth < 1) {
                        println("Depth must be at least 1 to output subphases, was: \"$newDepth\"")
                        println(usage)
                        return
                    }
                    depth = newDepth
                }
                "--exclude-type" -> {
                    argIndex++
                    if (argIndex !in arguments.indices) {
                        println("Missing specification of phase type(s) after \"--exclude-type\" flag")
                        println(usage)
                        return
                    }
                    excludeTypeStrs.addAll(arguments[argIndex].split(","))
                }
                else -> paths.add(arg)
            }
            argIndex++
        }

        val excludeTypes = excludeTypeStrs.map { s ->
            resolvePhaseType(s, cliState.grade10JobResult.executionModel.specification) ?: return
        }.flatMap { excludedType ->
            collectTreeNodes(excludedType) { it.subphaseTypes.values }
        }.toSet()

        val phases = cliState.resolvePhaseGlobsOrPrintError(paths, recursive)
        if (phases.isEmpty()) return
        val filteredPhases = phases.filter { it.type !in excludeTypes }
        if (filteredPhases.isEmpty()) {
            println()
            println("Plotting subphases for 0 phases")
            println("  (all matching phases were excluded using --exclude-type)")
            return
        }

        println()
        println("Plotting subphases for ${phases.size} phase${if (phases.size > 1) "s" else ""}:")
        for (phase in phases) {
            println("... processing phase ${phase.path}")
            exportForPhase(phase, depth, excludeTypes, force, excludeComposite, cliState)
        }
    }

    private fun resolvePhaseType(pathStr: String, executionModelSpec: ExecutionModelSpecification): PhaseType? {
        val path = science.atlarge.grade10.model.Path.parse(pathStr)
        if (path.isAbsolute) {
            val matchingType = executionModelSpec.resolvePhaseType(path)
            if (matchingType == null) {
                println("No phase type found for path \"$pathStr\"")
            }
            return matchingType
        } else {
            var matchingTypes = collectTreeNodes(executionModelSpec.rootPhaseType) { it.subphaseTypes.values }.toList()
            for (component in path.pathComponents) {
                val subPath = science.atlarge.grade10.model.Path.relative(component)
                matchingTypes = matchingTypes.mapNotNull { it.resolve(subPath) }
            }
            return when {
                matchingTypes.isEmpty() -> {
                    println("No phase type found for expression \"$pathStr\"")
                    null
                }
                matchingTypes.size > 1 -> {
                    println("Multiple phase types found for expression \"$pathStr\": ${matchingTypes.joinToString(
                            limit = 2) { it.path.toString() }}")
                    null
                }
                else -> matchingTypes[0]
            }
        }
    }

    private fun exportRecursive(
            phase: Phase,
            depth: Int,
            excludedTypes: Set<PhaseType>,
            force: Boolean,
            excludeComposite: Boolean,
            cliState: CliState
    ) {
        exportForPhase(phase, depth, excludedTypes, force, excludeComposite, cliState)
        phase.subphases.forEach { _, subphase ->
            if (subphase.type !in excludedTypes) {
                exportRecursive(subphase, depth, excludedTypes, force, excludeComposite, cliState)
            }
        }
    }

    private fun exportForPhase(
            phase: Phase,
            depth: Int,
            excludedTypes: Set<PhaseType>,
            force: Boolean,
            excludeComposite: Boolean,
            cliState: CliState
    ) {
        if (phase.isComposite && excludeComposite) {
            return
        }

        val phaseOutputPath = cliState.phaseOutputPath(phase).also { it.toFile().mkdirs() }
        val dataOutputPath = phaseOutputPath.resolve(".data").also { it.toFile().mkdirs() }
        val rScriptPath = phaseOutputPath.resolve(".R").also { it.toFile().mkdirs() }

        writePhaseListFile(dataOutputPath, phase, cliState, force)
        writePhaseTypeListFile(dataOutputPath, phase, cliState, force)

        val rScriptStream = javaClass.getResourceAsStream("/$SCRIPT_FILENAME")
        val rScriptOutPath = rScriptPath.resolve(SCRIPT_FILENAME)
        if (rScriptOutPath.toFile().exists()) {
            rScriptOutPath.toFile().delete()
        }
        writeRScript(rScriptStream, rScriptOutPath, mapOf(
                "depth_limit" to depth.toString(),
                "phase_type_filter" to "c(${excludedTypes.joinToString(prefix = "'", separator = "', '",
                        postfix = "'") { pt -> cliState.phaseList.phaseTypeToIdentifier(pt) }})",
                "ns_per_timeslice" to cliState.grade10JobResult.resourceModel.T.nanosecondsPerTimeslice.toString()
        ))

        val rScriptExec = System.getenv().getOrElse("RSCRIPT") { "Rscript" }

        val pb = ProcessBuilder(rScriptExec, rScriptOutPath.toAbsolutePath().toString(), depth.toString())
        pb.directory(rScriptPath.toFile())
        pb.redirectErrorStream(true)
        pb.redirectOutput(rScriptPath.resolve(SCRIPT_FILENAME.removeSuffix(".R") + ".log").toFile())
        pb.start().waitFor()
    }

    private fun writePhaseListFile(dataOutputPath: Path, phase: Phase, cliState: CliState, force: Boolean) {
        val phases = collectTreeNodes(phase) { it.subphases.values }.toList()
        val phaseListFile = dataOutputPath.resolve(PhaseListWriter.FILENAME).toFile()
        if (!phaseListFile.exists() || force) {
            PhaseListWriter.output(phaseListFile, cliState.grade10JobResult, phase, phases, cliState.phaseList)
        }
    }

    private fun writePhaseTypeListFile(dataOutputPath: Path, phase: Phase, cliState: CliState, force: Boolean) {
        val phaseTypes = collectTreeNodes(phase.type) { it.subphaseTypes.values }.toList()
        val phaseTypeListFile = dataOutputPath.resolve(PhaseTypeListWriter.FILENAME).toFile()
        if (!phaseTypeListFile.exists() || force) {
            PhaseTypeListWriter.output(phaseTypeListFile, cliState.grade10JobResult, phase.type, phaseTypes,
                    cliState.phaseList)
        }
    }

}