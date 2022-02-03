package science.atlarge.grademl.query

import com.github.h0tk3y.betterParse.grammar.tryParseToEnd
import com.github.h0tk3y.betterParse.parser.ErrorResult
import com.github.h0tk3y.betterParse.parser.Parsed
import science.atlarge.grademl.core.GradeMLEngine
import science.atlarge.grademl.core.GradeMLJobStatusUpdate
import science.atlarge.grademl.core.attribution.ResourceAttributionSettings
import science.atlarge.grademl.input.airflow.Airflow
import science.atlarge.grademl.input.resource_monitor.ResourceMonitor
import science.atlarge.grademl.input.spark.Spark
import science.atlarge.grademl.input.tensorflow.TensorFlow
import science.atlarge.grademl.query.parsing.QueryGrammar
import java.io.File
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.readLines
import kotlin.system.exitProcess

object QueryCli {

    @JvmStatic
    fun main(args: Array<String>) {
        println("Welcome to the GradeML query engine!")
        println()

        if (args.size < 2 || args.size > 3) {
            println("Usage: query-cli <jobLogDirectories> <jobAnalysisDirectory> [queryScript]")
            println("  jobLogDirectories must be separated by the ${File.pathSeparatorChar} character")
            exitProcess(1)
        }

        val inputPaths = args[0].split(File.pathSeparatorChar).map { Paths.get(it) }
        val outputPath = Paths.get(args[1])
        val queryScript = if (args.size >= 3) Paths.get(args[2]) else null

        GradeMLEngine.registerInputSource(ResourceMonitor)
        GradeMLEngine.registerInputSource(Spark)
        GradeMLEngine.registerInputSource(TensorFlow)
        GradeMLEngine.registerInputSource(Airflow)

        val gradeMLJob = GradeMLEngine.analyzeJob(
            inputPaths, outputPath, ResourceAttributionSettings(
                enableTimeSeriesCompression = true,
                enableRuleCaching = true,
                enableAttributionResultCaching = true
            )
        ) { update ->
            when (update) {
                GradeMLJobStatusUpdate.LOG_PARSING_STARTING -> {
                    println("Parsing job log files.")
                }
                GradeMLJobStatusUpdate.LOG_PARSING_COMPLETED -> {
                    println("Completed parsing of input files.")
                    println()
                }
                else -> {
                }
            }
        }

        if (
            gradeMLJob.unifiedExecutionModel.phases.size == 1 &&
            gradeMLJob.unifiedResourceModel.resources.any { it.metrics.isNotEmpty() }
        ) {
            println(
                "Did not find any execution logs. " +
                        "Creating dummy execution model to allow analysis of the resource model."
            )
            println()
            val (startTime, endTime) = gradeMLJob.unifiedResourceModel.resources.flatMap { it.metrics }
                .map { it.data.timestamps.first() to it.data.timestamps.last() }
                .reduce { acc, pair -> minOf(acc.first, pair.first) to maxOf(acc.second, pair.second) }
            gradeMLJob.unifiedExecutionModel.addPhase("dummy_phase", startTime = startTime, endTime = endTime)
        }

        if (queryScript != null) {
            runScript(QueryEngine(gradeMLJob, outputPath.resolve("query-output")), queryScript)
        } else {
            runCli(QueryEngine(gradeMLJob, outputPath.resolve("query-output")))
        }
    }

    private fun runScript(queryEngine: QueryEngine, queryScript: Path) {
        val scriptLines = queryScript.readLines()
        var linesProcessed = 0
        var queriesProcessed = 0
        while (linesProcessed < scriptLines.size) {
            // Read until a semicolon
            val queryLines = mutableListOf<String>()
            do {
                val nextLine = scriptLines[linesProcessed++]
                if (nextLine.trim().startsWith("//")) continue
                queryLines.add(nextLine)
            } while (linesProcessed < scriptLines.size && !queryLines.last().endsWith(";"))
            if (queryLines.isEmpty()) return

            // Process the query
            queriesProcessed++
            println("RUNNING QUERY $queriesProcessed:")
            for (line in queryLines) println("| $line")
            println()

            // Parse the query/queries
            val queries = when (val parseResult = QueryGrammar.tryParseToEnd(queryLines.joinToString("\n"))) {
                is Parsed -> parseResult.value
                is ErrorResult -> {
                    println()
                    println("Failed to parse query: $parseResult")
                    continue
                }
            }

            // Run the queries
            queries.forEach {
                try {
                    queryEngine.executeStatement(it)
                } catch (t: Throwable) {
                    t.printStackTrace()
                    println()
                }
            }
        }
    }

    private fun runCli(queryEngine: QueryEngine) {
        // Print introduction for user
        println("Explore the job's performance data interactively by issuing queries.")
        println("See the README for a description of the query language and example queries.")
        println()

        // Repeatedly read, parse, and execute queries until the users quits the application
        while (true) {
            // Read until a semicolon
            val queryLines = mutableListOf<String>()
            do {
                val nextLine = readLine() ?: return
                if (nextLine.trim().startsWith("//")) continue
                queryLines.add(nextLine)
            } while (queryLines.isEmpty() || !queryLines.last().endsWith(";"))

            // Parse the query/queries
            val queries = when (val parseResult = QueryGrammar.tryParseToEnd(queryLines.joinToString("\n"))) {
                is Parsed -> parseResult.value
                is ErrorResult -> {
                    println()
                    println("Failed to parse query: $parseResult")
                    continue
                }
            }

            // Run the queries
            queries.forEach {
                try {
                    queryEngine.executeStatement(it)
                } catch (t: Throwable) {
                    t.printStackTrace()
                    println()
                }
            }
        }
    }

}