package science.atlarge.grade10.cli.commands

import science.atlarge.grade10.cli.CliState
import science.atlarge.grade10.cli.Command
import science.atlarge.grade10.cli.data.BottleneckWriter
import science.atlarge.grade10.cli.data.MetricTypeListWriter
import science.atlarge.grade10.cli.data.PhaseListWriter
import science.atlarge.grade10.cli.util.writeRScript
import science.atlarge.grade10.model.execution.Phase
import science.atlarge.grade10.util.collectTreeNodes
import java.nio.file.Files
import java.nio.file.Path

object PlotBottlenecksCommand : Command {

    private const val SCRIPT_FILENAME = "plot-bottlenecks.R"

    override val name: String
        get() = "plot-bottlenecks"
    override val shortHelpMessage: String
        get() = ""
    override val longHelpMessage: String
        get() = ""

    private val usage = "Correct usage: $name [--recursive] [--force] [--exclude-composite] [phase-path-expression ...]"

    override fun process(arguments: List<String>, cliState: CliState) {
        var recursive = false
        var force = false
        var excludeComposite = false
        val paths = mutableListOf<String>()
        arguments.forEach {
            when (it) {
                "--recursive" -> recursive = true
                "--force" -> force = true
                "--exclude-composite" -> excludeComposite = true
                else -> paths.add(it)
            }
        }

        val phases = cliState.resolvePhaseGlobsOrPrintError(paths, recursive)
        if (phases.isEmpty()) return
        println()
        println("Plotting bottlenecks for ${phases.size} phase${if (phases.size > 1) "s" else ""}:")
        for (phase in phases) {
            println("... processing phase ${phase.path}")
            exportForPhase(phase, force, excludeComposite, cliState)
        }
    }

    private fun exportForPhase(phase: Phase, force: Boolean, excludeComposite: Boolean, cliState: CliState) {
        if (phase.isComposite && excludeComposite) {
            return
        }

        val phaseOutputPath = cliState.phaseOutputPath(phase).also { it.toFile().mkdirs() }
        val dataOutputPath = phaseOutputPath.resolve(".data").also { it.toFile().mkdirs() }
        val rScriptPath = phaseOutputPath.resolve(".R").also { it.toFile().mkdirs() }

        writePhaseListFile(dataOutputPath, phase, cliState, force)
        writeMetricTypeListFile(dataOutputPath, phase, cliState, force)
        writeBottlenecksFile(dataOutputPath, phase, cliState, force)
        writeSubphaseBottlenecksFile(dataOutputPath, phase, cliState, force)

        val rScriptStream = javaClass.getResourceAsStream("/$SCRIPT_FILENAME")
        val rScriptOutPath = rScriptPath.resolve(SCRIPT_FILENAME)
        if (rScriptOutPath.toFile().exists()) {
            rScriptOutPath.toFile().delete()
        }
        writeRScript(rScriptStream, rScriptOutPath, mapOf(
                "ns_per_timeslice" to cliState.grade10JobResult.resourceModel.T.nanosecondsPerTimeslice.toString()
        ))

        val rScriptExec = System.getenv().getOrElse("RSCRIPT") { "Rscript" }

        val pb = ProcessBuilder(rScriptExec, rScriptOutPath.toAbsolutePath().toString())
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

    private fun writeMetricTypeListFile(dataOutputPath: Path, phase: Phase, cliState: CliState, force: Boolean) {
        val metrics = cliState.grade10JobResult.resourceAttributionResult.resourceAttribution[phase].metrics +
                cliState.grade10JobResult.resourceAttributionResult.resourceAttribution[phase].unusedMetrics
        val metricTypes = metrics.map { it.type }.toSet()
        val metricTypeListFile = dataOutputPath.resolve(MetricTypeListWriter.FILENAME).toFile()
        if (!metricTypeListFile.exists() || force) {
            MetricTypeListWriter.output(metricTypeListFile, metricTypes, cliState.metricList)
        }
    }

    private fun writeBottlenecksFile(dataOutputPath: Path, phase: Phase, cliState: CliState, force: Boolean) {
        val bottlenecksFile = dataOutputPath.resolve(BottleneckWriter.FILENAME).toFile()
        if (!bottlenecksFile.exists() || force) {
            BottleneckWriter.output(bottlenecksFile, cliState.grade10JobResult, phase, cliState.phaseList,
                    cliState.metricList)
        }
    }

    private fun writeSubphaseBottlenecksFile(dataOutputPath: Path, phase: Phase, cliState: CliState, force: Boolean) {
        val subphaseBottlenecksFile = dataOutputPath.resolve(BottleneckWriter.SUBPHASE_FILENAME).toFile()
        if (!subphaseBottlenecksFile.exists() || force) {
            BottleneckWriter.outputForSubphases(subphaseBottlenecksFile, cliState.grade10JobResult, phase,
                    cliState.phaseList, cliState.metricList)
        }
    }

}