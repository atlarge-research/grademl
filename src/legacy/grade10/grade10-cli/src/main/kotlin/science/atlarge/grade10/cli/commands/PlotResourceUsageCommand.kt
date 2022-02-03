package science.atlarge.grade10.cli.commands

import science.atlarge.grade10.cli.CliState
import science.atlarge.grade10.cli.Command
import science.atlarge.grade10.cli.data.BlockingResourceUsageWriter
import science.atlarge.grade10.cli.data.ConsumableResourceUsageWriter
import science.atlarge.grade10.cli.data.MetricListWriter
import science.atlarge.grade10.cli.data.MetricTypeListWriter
import science.atlarge.grade10.model.execution.Phase
import science.atlarge.grade10.util.collectTreeNodes
import java.nio.file.Files
import java.nio.file.Path

object PlotResourceUsageCommand : Command {

    private const val SCRIPT_FILENAME = "plot-resource-usage.R"

    override val name: String
        get() = "plot-resource-usage"
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
        println("Plotting resource usage for ${phases.size} phase${if (phases.size > 1) "s" else ""}:")
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

        writeConsumableUsageFile(dataOutputPath, phase, cliState, force)
        writeBlockingUsageFile(dataOutputPath, phase, cliState, force)
        writeMetricListFile(dataOutputPath, phase, cliState, force)
        writeMetricTypeListFile(dataOutputPath, phase, cliState, force)

        val rScriptStream = javaClass.getResourceAsStream("/$SCRIPT_FILENAME")
        val rScriptOutPath = rScriptPath.resolve(SCRIPT_FILENAME)
        if (rScriptOutPath.toFile().exists()) {
            rScriptOutPath.toFile().delete()
        }
        Files.copy(rScriptStream, rScriptOutPath)

        val rScriptExec = System.getenv().getOrElse("RSCRIPT") { "Rscript" }

        val pb = ProcessBuilder(rScriptExec, rScriptOutPath.toAbsolutePath().toString())
        pb.directory(rScriptPath.toFile())
        pb.redirectErrorStream(true)
        pb.redirectOutput(rScriptPath.resolve(SCRIPT_FILENAME.removeSuffix(".R") + ".log").toFile())
        pb.start().waitFor()
    }

    private fun writeBlockingUsageFile(dataOutputPath: Path, phase: Phase, cliState: CliState, force: Boolean) {
        val resAttrResult = cliState.grade10JobResult.resourceAttributionResult.resourceAttribution[phase]
        val blockingUsageFile = dataOutputPath.resolve(BlockingResourceUsageWriter.FILENAME).toFile()
        if (!blockingUsageFile.exists() || force) {
            BlockingResourceUsageWriter.output(blockingUsageFile, cliState.grade10JobResult, phase,
                    resAttrResult.blockingMetrics, cliState.metricList)
        }
    }

    private fun writeConsumableUsageFile(dataOutputPath: Path, phase: Phase, cliState: CliState, force: Boolean) {
        val resAttrResult = cliState.grade10JobResult.resourceAttributionResult.resourceAttribution[phase]
        val consumableUsageFile = dataOutputPath.resolve(ConsumableResourceUsageWriter.FILENAME).toFile()
        if (!consumableUsageFile.exists() || force) {
            ConsumableResourceUsageWriter.output(consumableUsageFile, cliState.grade10JobResult, phase,
                    resAttrResult.consumableMetrics, cliState.metricList)
        }
    }

    private fun writeMetricListFile(dataOutputPath: Path, phase: Phase, cliState: CliState, force: Boolean) {
        val metrics = cliState.grade10JobResult.resourceAttributionResult.resourceAttribution[phase].metrics +
                cliState.grade10JobResult.resourceAttributionResult.resourceAttribution[phase].unusedMetrics
        val metricListFile = dataOutputPath.resolve(MetricListWriter.FILENAME).toFile()
        if (!metricListFile.exists() || force) {
            MetricListWriter.output(metricListFile, metrics, cliState.metricList)
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

}