package science.atlarge.grademl.input.tensorflow

import science.atlarge.grademl.core.util.TimestampNs
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.streams.toList

class TensorFlowLogParser private constructor(
    private val airflowLogDirectories: Iterable<Path>
) {

    private fun parse(): TensorFlowLog {
        val jobLogFiles = findAppLogFiles()
        return TensorFlowLog(
            jobLogFiles.map { parseTensorFlowLogFile(it) }
        )
    }

    private fun findAppLogFiles(): MutableList<File> {
        // Find all log files in the Airflow log directories
        val logFiles = airflowLogDirectories.flatMap { directory ->
            Files.walk(directory).use { fileList ->
                fileList.map { it.toFile() }
                    .filter { it.isFile && it.extension == "log" }
                    .toList()
            }
        }
        // Check each file for a TensorFlow reference
        val tensorFlowLogs = mutableListOf<File>()
        for (logFile in logFiles) {
            // Check if this log file contains a TensorFlow log entry
            val isTensorFlowLog = logFile.useLines { lines ->
                if (lines.any { "I tensorflow/" in it }) {
                    tensorFlowLogs.add(logFile)
                }
            }
        }
        return tensorFlowLogs
    }

    private fun parseTensorFlowLogFile(jobLogFile: File): TensorFlowJobLog {
        // Read the log file
        val logLines = jobLogFile.readLines()

        // Find the DAG ID for the Airflow task corresponding to this TensorFlow job
        val dagId = logLines.first { it.startsWith("AIRFLOW_CTX_DAG_ID=") }
            .split("=", limit = 2)[1].trim()

        // Find the run ID for the Airflow task corresponding to this TensorFlow job
        val runId = logLines.first { it.startsWith("AIRFLOW_CTX_DAG_RUN_ID=") }
            .split("=", limit = 2)[1].trim()

        // Find the task ID for the Airflow task corresponding to this TensorFlow job
        val taskId = logLines.first { it.startsWith("AIRFLOW_CTX_TASK_ID=") }
            .split("=", limit = 2)[1].trim()

        // Construct a job ID
        val jobId = TensorFlowJobId(dagId, runId, taskId)

        // Find records of the start and end time of the task
        val startTimeLine = logLines.first { "INFO - Executing <Task" in it }
        val endTimeLine = logLines.last { "INFO - Marking task" in it }

        // Parse dates and times (assuming local time)
        fun parseDateTimeForLogLine(logLine: String): TimestampNs {
            val dateTime = logLine.substring(1, logLine.indexOf(']'))
            val instant = LocalDateTime
                .parse(dateTime, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss,SSS"))
                .atZone(ZoneId.systemDefault())
                .toInstant()
            return instant.epochSecond * 1_000_000_000L + instant.nano
        }

        val jobStartTime = parseDateTimeForLogLine(startTimeLine)
        val jobEndTime = parseDateTimeForLogLine(endTimeLine)

        // Find log lines indicating the start of an epoch
        // The next "empty" (i.e., multi-line) log line after is the end of the epoch
        val epochs = mutableMapOf<Int, Pair<TimestampNs, TimestampNs>>()
        var logLineNumber = 0
        while (logLineNumber < logLines.size) {
            val line = logLines[logLineNumber]
            // Check if the current log line indicates the start of an epoch
            val matchingStartOfEpoch = START_LINE_REGEX.matchEntire(line)
            if (matchingStartOfEpoch == null) {
                // If not, continue processing the next log line
                logLineNumber++
                continue
            }
            // Extract the start time and epoch number
            val startTime = parseDateTimeForLogLine(line)
            val epoch = matchingStartOfEpoch.groupValues[1].toInt()
            // Find the next line representing the end of an epoch
            logLineNumber++
            while (END_LINE_REGEX.matchEntire(logLines[logLineNumber]) == null) logLineNumber++
            // Extract the end time of the epoch
            val endTime = parseDateTimeForLogLine(logLines[logLineNumber])

            epochs[epoch] = startTime to endTime

            logLineNumber++
        }
        val sortedEopchs = epochs.toList()
            .sortedWith(compareBy({ it.second.first }, { it.second.second }, { it.first }))

        return TensorFlowJobLog(
            jobId,
            jobStartTime,
            jobEndTime,
            sortedEopchs.map { it.first.toString() },
            sortedEopchs.map { it.second.first },
            sortedEopchs.map { it.second.second }
        )
    }

    companion object {
        private val START_LINE_REGEX = """\[[0-9- :,]*] .* INFO -.*<stdout>:Epoch ([0-9]+)/[0-9+]+""".toRegex()
        private val END_LINE_REGEX = """\[[0-9- :,]*] .* INFO .*""".toRegex()

        fun parseFromDirectories(tensorFlowLogDirectories: Iterable<Path>): TensorFlowLog {
            return TensorFlowLogParser(tensorFlowLogDirectories).parse()
        }
    }

}

class TensorFlowLog(
    val tensorFlowJobs: List<TensorFlowJobLog>
)

class TensorFlowJobLog(
    val jobId: TensorFlowJobId,
    val startTime: TimestampNs,
    val endTime: TimestampNs,
    val epochIds: List<String>,
    val epochStartTimes: List<TimestampNs>,
    val epochEndTimes: List<TimestampNs>
)

class TensorFlowJobId(
    val dagId: String,
    val runId: String,
    val taskId: String
)
