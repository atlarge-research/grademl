package science.atlarge.grade10.cli.commands

import science.atlarge.grade10.cli.CliState
import science.atlarge.grade10.cli.Command
import science.atlarge.grade10.cli.data.BlockingMetricsWriter
import science.atlarge.grade10.cli.data.ConsumableMetricsWriter
import science.atlarge.grade10.cli.data.MetricListWriter
import science.atlarge.grade10.cli.data.MetricTypeListWriter
import science.atlarge.grade10.cli.util.writeRScript
import science.atlarge.grade10.model.execution.Phase
import science.atlarge.grade10.model.resources.Metric
import science.atlarge.grade10.util.collectTreeNodes
import java.nio.file.Files
import java.nio.file.Path

object PlotRawMetricsCommand : Command {

    private const val SCRIPT_FILENAME = "plot-raw-metrics.R"

    override val name: String
        get() = "plot-raw-metrics"
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
        println("Plotting raw metrics for ${phases.size} phase${if (phases.size > 1) "s" else ""}:")
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

        writeConsumableMetricsFile(dataOutputPath, phase, cliState, force)
        writeBlockingMetricsFile(dataOutputPath, phase, cliState, force)
        writeMetricListFile(dataOutputPath, phase, cliState, force)
        writeMetricTypeListFile(dataOutputPath, phase, cliState, force)

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

    private fun writeBlockingMetricsFile(dataOutputPath: Path, phase: Phase, cliState: CliState, force: Boolean) {
        val resAttrResult = cliState.grade10JobResult.resourceAttributionResult.resourceAttribution[phase]
        val blockingUsageFile = dataOutputPath.resolve(BlockingMetricsWriter.FILENAME).toFile()
        val blockingMetrics = if (phase.isRoot) {
            collectTreeNodes(cliState.grade10JobResult.resourceModel.rootResource) { it.subresources.values }
                    .flatMap { it.metrics.values }
                    .filterIsInstance<Metric.Blocking>()
        } else {
            resAttrResult.blockingMetrics
        }
        if (!blockingUsageFile.exists() || force) {
            BlockingMetricsWriter.output(blockingUsageFile, cliState.grade10JobResult, phase.firstTimeslice,
                    phase.lastTimeslice, blockingMetrics, cliState.metricList)
        }
    }

    private fun writeConsumableMetricsFile(dataOutputPath: Path, phase: Phase, cliState: CliState, force: Boolean) {
        val resAttrResult = cliState.grade10JobResult.resourceAttributionResult.resourceAttribution[phase]
        val consumableUsageFile = dataOutputPath.resolve(ConsumableMetricsWriter.FILENAME).toFile()
        val consumableMetrics = if (phase.isRoot) {
            collectTreeNodes(cliState.grade10JobResult.resourceModel.rootResource) { it.subresources.values }
                    .flatMap { it.metrics.values }
                    .filterIsInstance<Metric.Consumable>()
        } else {
            resAttrResult.consumableMetrics
        }
        if (!consumableUsageFile.exists() || force) {
            ConsumableMetricsWriter.output(consumableUsageFile, cliState.grade10JobResult, phase.firstTimeslice,
                    phase.lastTimeslice, consumableMetrics, cliState.metricList)
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