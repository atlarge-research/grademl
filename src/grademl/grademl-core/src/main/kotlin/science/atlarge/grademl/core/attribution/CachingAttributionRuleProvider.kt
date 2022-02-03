package science.atlarge.grademl.core.attribution

import science.atlarge.grademl.core.models.ExecutionPhase
import science.atlarge.grademl.core.models.Metric
import java.io.BufferedWriter
import java.io.FileWriter
import java.io.Writer
import java.nio.file.Path
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class CachingAttributionRuleProvider(
    outputPath: Path,
    private val baseAttributionRuleProvider: ResourceAttributionRuleProvider
) : ResourceAttributionRuleProvider {

    private val ruleCacheDirectory = outputPath.resolve(".attr-rule-cache")
    private val phaseListFile = ruleCacheDirectory.resolve("phase-list").toFile()
    private val metricListFile = ruleCacheDirectory.resolve("metric-list").toFile()
    private val mappingFile = ruleCacheDirectory.resolve("phase-metric-mapping").toFile()

    private val phaseList: MutableList<String>
    private val metricList: MutableList<String>
    private val mapping: MutableMap<String, MutableMap<String, ResourceAttributionRule?>>

    private val phaseToIdMap = mutableMapOf<String, Int>()
    private val metricToIdMap = mutableMapOf<String, Int>()

    private val phaseListWriter: Writer
    private val metricListWriter: Writer
    private val mappingWriter: Writer
    private val writerLock = ReentrantLock()
    private val flushThread: Thread

    init {
        phaseList = readPhaseList()
        metricList = readMetricList()
        mapping = readMapping()

        phaseList.mapIndexed { index, phase -> phase to index }.toMap(phaseToIdMap)
        metricList.mapIndexed { index, metric -> metric to index }.toMap(metricToIdMap)

        ruleCacheDirectory.toFile().mkdirs()
        phaseListWriter = BufferedWriter(FileWriter(phaseListFile, true))
        metricListWriter = BufferedWriter(FileWriter(metricListFile, true))
        mappingWriter = BufferedWriter(FileWriter(mappingFile, true))

        flushThread = Thread {
            while (true) {
                Thread.sleep(5000)
                writerLock.withLock {
                    phaseListWriter.flush()
                    metricListWriter.flush()
                    mappingWriter.flush()
                }
            }
        }
        flushThread.isDaemon = true
        flushThread.start()
    }

    override fun forPhaseAndMetric(phase: ExecutionPhase, metric: Metric): ResourceAttributionRule? {
        val phaseCache = mapping[phase.path.toString()]
        if (phaseCache != null) {
            val metricPath = metric.path.toString()
            if (metricPath in phaseCache) {
                return phaseCache[metricPath]
            }
        }

        val computedRule = baseAttributionRuleProvider.forPhaseAndMetric(phase, metric)

        cacheRule(phase, metric, computedRule)

        return computedRule
    }

    private fun readPhaseList(): MutableList<String> =
        if (phaseListFile.exists()) {
            phaseListFile.readLines().filter { it.isNotBlank() }.toMutableList()
        } else {
            mutableListOf()
        }

    private fun readMetricList(): MutableList<String> =
        if (metricListFile.exists()) {
            metricListFile.readLines().filter { it.isNotBlank() }.toMutableList()
        } else {
            mutableListOf()
        }

    private fun readMapping(): MutableMap<String, MutableMap<String, ResourceAttributionRule?>> {
        if (!mappingFile.exists()) return mutableMapOf()

        val mappingLines = mappingFile.readLines().filter { it.isNotBlank() }
        val outMap = mutableMapOf<String, MutableMap<String, ResourceAttributionRule?>>()
        for (line in mappingLines) {
            val (phaseId, metricId, ruleStr) = line.split(" ")
            val phase = phaseList[phaseId.toInt()]
            val metric = metricList[metricId.toInt()]
            val rule = when {
                ruleStr == "x" -> null
                ruleStr == "n" -> ResourceAttributionRule.None
                ruleStr.startsWith("e") -> ResourceAttributionRule.Exact(ruleStr.drop(1).toDouble())
                ruleStr.startsWith("v") -> ResourceAttributionRule.Variable(ruleStr.drop(1).toDouble())
                else -> throw IllegalArgumentException()
            }
            outMap.getOrPut(phase) { mutableMapOf() }[metric] = rule
        }
        return outMap
    }

    private fun cacheRule(phase: ExecutionPhase, metric: Metric, rule: ResourceAttributionRule?) {
        writerLock.withLock {
            val phasePath = phase.path.toString()
            if (phasePath !in phaseToIdMap) {
                phaseList.add(phasePath)
                phaseToIdMap[phasePath] = phaseList.size - 1
                phaseListWriter.write("$phasePath\n")
            }
            val phaseId = phaseToIdMap[phasePath]

            val metricPath = metric.path.toString()
            if (metricPath !in metricToIdMap) {
                metricList.add(metricPath)
                metricToIdMap[metricPath] = metricList.size - 1
                metricListWriter.write("$metricPath\n")
            }
            val metricId = metricToIdMap[metricPath]

            mapping.getOrPut(phasePath) { mutableMapOf() }[metricPath] = rule
            mappingWriter.write(
                "$phaseId $metricId ${
                    when (rule) {
                        null -> "x"
                        ResourceAttributionRule.None -> "n"
                        is ResourceAttributionRule.Exact -> "e${rule.demand}"
                        is ResourceAttributionRule.Variable -> "v${rule.demand}"
                    }
                }\n"
            )
        }
    }

}