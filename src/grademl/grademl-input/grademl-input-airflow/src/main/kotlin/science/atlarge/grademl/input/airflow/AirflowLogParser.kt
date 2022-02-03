package science.atlarge.grademl.input.airflow

import science.atlarge.grademl.core.util.TimestampNs
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.*
import kotlin.streams.toList

typealias AirflowDagId = String
typealias AirflowRunId = String
typealias AirflowTaskId = String

class AirflowLogParser private constructor(
    private val airflowLogDirectories: Iterable<Path>
) {

    private val dagIds = mutableSetOf<AirflowDagId>()
    private val taskIdsPerDag = mutableMapOf<AirflowDagId, Set<AirflowTaskId>>()
    private val taskDownstreamIdsPerDag = mutableMapOf<AirflowDagId, Map<AirflowTaskId, Set<AirflowTaskId>>>()
    private val runIdsPerDag = mutableMapOf<AirflowDagId, Set<AirflowRunId>>()
    private val taskStartTimesPerRunPerDag = mutableMapOf<AirflowDagId, MutableMap<AirflowRunId,
            MutableMap<AirflowTaskId, TimestampNs>>>()
    private val taskEndTimesPerRunPerDag = mutableMapOf<AirflowDagId, MutableMap<AirflowRunId,
            MutableMap<AirflowTaskId, TimestampNs>>>()
    private val taskLogContentsPerRunPerDag = mutableMapOf<AirflowDagId, MutableMap<AirflowRunId,
            MutableMap<AirflowTaskId, List<String>>>>()

    // Expected directory structure:
    // <airflowLogDirectory>/
    //     <dag_id>.dag - DAG structure, output of "airflow tasks list -t <dag_id>"
    //     <dag_id>.run_ids - identifier(s) for the DAG run(s) recorded in the given logs
    //     **/*.log - log file for every task run
    private fun parse(): AirflowLog {
        parseDagIds()
        parseDagStructures()
        parseRunIds()
        parseTaskInformationFiles()
        return AirflowLog(
            dagIds.map { dagId ->
                AirflowDagLog(
                    dagId,
                    runIdsPerDag[dagId].orEmpty(),
                    taskIdsPerDag[dagId].orEmpty(),
                    taskDownstreamIdsPerDag[dagId].orEmpty(),
                    taskStartTimesPerRunPerDag[dagId].orEmpty(),
                    taskEndTimesPerRunPerDag[dagId].orEmpty(),
                    taskLogContentsPerRunPerDag[dagId].orEmpty()
                )
            }.associateBy { it.dagId }
        )
    }

    private fun findDagFiles(): List<File> =
        airflowLogDirectories.flatMap { directory ->
            Files.list(directory).use { fileList ->
                fileList.map { it.toFile() }
                    .filter { it.extension == "dag" }
                    .toList()
            }
        }

    private fun findRunIdFiles(): List<File> =
        airflowLogDirectories.flatMap { directory ->
            Files.list(directory).use { fileList ->
                fileList.map { it.toFile() }
                    .filter { it.extension == "run_ids" }
                    .toList()
            }
        }

    private fun findLogFiles(): List<File> =
        airflowLogDirectories.flatMap { directory ->
            Files.walk(directory).use { fileList ->
                fileList.map { it.toFile() }
                    .filter { it.isFile && it.extension == "log" }
                    .toList()
            }
        }

    private fun parseDagIds() {
        dagIds.addAll(findDagFiles().map { it.nameWithoutExtension })
        require(dagIds.isNotEmpty()) { "No files matching '*.dag' found in $airflowLogDirectories" }
    }

    private fun parseDagStructures() {
        val dagFilesById = findDagFiles().groupBy { it.nameWithoutExtension }
        for ((dagId, dagFiles) in dagFilesById) {
            // Read task ids and task order from the first file describing a DAG with a particular id
            val (taskIds, taskDownstreamIds) = parseDagStructure(dagFiles[0])
            // Ensure that there are no other definitions with the same id
            for (dagFile in dagFiles.subList(1, dagFiles.size)) {
                val (otherTaskIds, otherTaskDownstreamIds) = parseDagStructure(dagFile)
                require(taskIds.containsAll(otherTaskIds) && otherTaskIds.containsAll(taskIds)) {
                    "Found conflicting definitions of tasks in DAG: \"$dagId\""
                }
                require(
                    taskDownstreamIds.keys.containsAll(otherTaskDownstreamIds.keys) &&
                            otherTaskDownstreamIds.keys.containsAll(taskDownstreamIds.keys)
                ) {
                    "Found conflicting definitions of task orders in DAG: \"$dagId\""
                }
                for (taskId in otherTaskDownstreamIds.keys) {
                    require(
                        taskDownstreamIds[taskId]!!.containsAll(otherTaskDownstreamIds[taskId]!!) &&
                                otherTaskDownstreamIds[taskId]!!.containsAll(taskDownstreamIds[taskId]!!)
                    ) {
                        "Found conflicting definitions of task orders in DAG: \"$dagId\""
                    }
                }
            }
            // Register the DAG description
            taskIdsPerDag[dagId] = taskIds
            taskDownstreamIdsPerDag[dagId] = taskDownstreamIds
        }
    }

    private fun parseDagStructure(dagStructureFile: File): Pair<Set<String>, Map<String, Set<String>>> {
        // Read the DAG structure file
        val dagStructureLines = dagStructureFile.readLines().filter { it.isNotEmpty() }

        // Create data structure to track task ids and task order
        val taskIds = mutableSetOf<String>()
        val taskDownstreamIds = mutableMapOf<String, MutableSet<String>>()

        // Parse tree representation of the task DAG
        val taskIdStack = Stack<String>()
        val indentationStack = Stack<Int>()
        for (line in dagStructureLines) {
            // Get the task ID and add it
            val taskId = line.split("): ").last().trimEnd('>')
            taskIds.add(taskId)
            // Get the indentation of the current line and pop from the stack any tasks
            // with the same or greater indentation, i.e., non-parent tasks
            val indentation = line.indexOf('<')
            while (indentationStack.isNotEmpty() && indentationStack.peek() >= indentation) {
                taskIdStack.pop()
                indentationStack.pop()
            }
            // Add downstream task constraint if applicable
            if (taskIdStack.isNotEmpty()) {
                val upstream = taskIdStack.peek()
                taskDownstreamIds.getOrPut(upstream) { mutableSetOf() }.add(taskId)
            }
            // Store task on indentation stack
            taskIdStack.push(taskId)
            indentationStack.push(indentation)
        }

        return taskIds to taskDownstreamIds
    }

    private fun parseRunIds() {
        val runIdFilesById = findRunIdFiles().groupBy { it.nameWithoutExtension }
        for ((dagId, runIdFiles) in runIdFilesById) {
            require(dagId in dagIds) { "Found run log for unknown DAG: \"$dagId\"" }
            runIdsPerDag[dagId] = runIdFiles.flatMap { f -> f.readLines().filter { it.isNotBlank() } }.toSet()
        }
    }

    private fun parseTaskInformationFiles() {
        for (logFile in findLogFiles()) {
            parseTaskInformation(logFile)
        }
    }

    private fun parseTaskInformation(taskLogFile: File) {
        // Read the log file
        val logLines = taskLogFile.readLines()

        // Find the DAG ID for this task
        val dagId = logLines.first { it.startsWith("AIRFLOW_CTX_DAG_ID=") }
            .split("=", limit = 2)[1].trim()
        require(dagId in dagIds) { "Found task log for unknown DAG: \"$dagId\"" }

        // Find the run ID for this task
        val runId = logLines.first { it.startsWith("AIRFLOW_CTX_DAG_RUN_ID=") }
            .split("=", limit = 2)[1].trim()
        require(runId in runIdsPerDag[dagId].orEmpty()) {
            "Found task log for unknown run: \"$runId\" (DAG: \"$dagId\")"
        }

        // Find the task ID for this task
        val taskId = logLines.first { it.startsWith("AIRFLOW_CTX_TASK_ID=") }
            .split("=", limit = 2)[1].trim()

        // Find records of the start and end time of the task
        val startTimeLine = logLines.first { "INFO - Executing <Task" in it }
        val endTimeLine = logLines.last { "INFO - Marking task" in it }

        // Parse dates and times (assuming local time)
        fun parseDateTime(dateTime: String): Long {
            val instant = LocalDateTime
                .parse(dateTime, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss,SSS"))
                .atZone(ZoneId.systemDefault())
                .toInstant()
            return instant.epochSecond * 1_000_000_000L + instant.nano
        }

        val startTime = parseDateTime(startTimeLine.substring(1, startTimeLine.indexOf(']')))
        val endTime = parseDateTime(endTimeLine.substring(1, endTimeLine.indexOf(']')))

        taskStartTimesPerRunPerDag.getOrPut(dagId) { mutableMapOf() }
            .getOrPut(runId) { mutableMapOf() }[taskId] = startTime
        taskEndTimesPerRunPerDag.getOrPut(dagId) { mutableMapOf() }
            .getOrPut(runId) { mutableMapOf() }[taskId] = endTime
        taskLogContentsPerRunPerDag.getOrPut(dagId) { mutableMapOf() }
            .getOrPut(runId) { mutableMapOf() }[taskId] = logLines
    }

    companion object {

        fun parseFromDirectories(airflowLogDirectories: Iterable<Path>): AirflowLog {
            return AirflowLogParser(airflowLogDirectories).parse()
        }

    }

}

data class AirflowLog(
    val dagLogs: Map<AirflowDagId, AirflowDagLog>
)

data class AirflowDagLog(
    val dagId: AirflowDagId,
    val runIds: Set<AirflowRunId>,
    val taskIds: Set<AirflowTaskId>,
    val taskDownstreamIds: Map<AirflowTaskId, Set<AirflowTaskId>>,
    val taskStartTimesPerRun: Map<AirflowRunId, Map<AirflowTaskId, TimestampNs>>,
    val taskEndTimesPerRun: Map<AirflowRunId, Map<AirflowTaskId, TimestampNs>>,
    val taskLogContentsPerRunPerDag: Map<AirflowRunId, Map<AirflowTaskId, List<String>>>
)