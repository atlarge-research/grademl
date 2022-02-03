package science.atlarge.grademl.input.tensorflow

import science.atlarge.grademl.core.input.InputSource
import science.atlarge.grademl.core.models.Environment
import science.atlarge.grademl.core.models.ExecutionModel
import science.atlarge.grademl.core.models.ExecutionPhase
import science.atlarge.grademl.core.models.ResourceModel
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.system.exitProcess

object TensorFlow : InputSource {

    override fun parseJobData(
        jobDataDirectories: Iterable<Path>,
        unifiedExecutionModel: ExecutionModel,
        unifiedResourceModel: ResourceModel,
        jobEnvironment: Environment
    ): Boolean {
        // Find Airflow log directories
        val airflowLogDirectories = jobDataDirectories
            .map { it.resolve("logs").resolve("airflow") }
            .filter { it.toFile().isDirectory }
        if (airflowLogDirectories.isEmpty()) return false

        // Parse TensorFlow log files
        val tensorFlowLog = TensorFlowLogParser.parseFromDirectories(airflowLogDirectories)

        // Iterate over TensorFlow jobs to build the execution model
        for (jobLog in tensorFlowLog.tensorFlowJobs) {
            // Add execution phase for job
            val appPhase = unifiedExecutionModel.addPhase(
                name = "TensorFlowJob",
                tags = mapOf(
                    "dag" to jobLog.jobId.dagId,
                    "run" to jobLog.jobId.runId,
                    "task" to jobLog.jobId.taskId
                ),
                startTime = jobLog.startTime,
                endTime = jobLog.endTime
            )
            // Add execution phases for epochs
            var lastEpochPhase: ExecutionPhase? = null
            jobLog.epochIds.forEachIndexed { i, epochId ->
                val nextEpochPhase = unifiedExecutionModel.addPhase(
                    name = "Epoch",
                    tags = mapOf("id" to epochId),
                    typeTags = emptySet(),
                    startTime = jobLog.epochStartTimes[i],
                    endTime = jobLog.epochEndTimes[i],
                    parent = appPhase
                )
                // Add dependency on previous epoch (if there was any)
                lastEpochPhase?.addOutgoingDataflow(nextEpochPhase)
                lastEpochPhase = nextEpochPhase
            }
        }

        return true
    }

}

// Wrapper for testing the log parser
fun main(args: Array<String>) {
    if (args.isEmpty() || args[0] == "--help") {
        println("Arguments: <jobLogDirectory> [...]")
        exitProcess(if (args.isEmpty()) -1 else 0)
    }

    val executionModel = ExecutionModel()
    val foundTensorFlowLogs =
        TensorFlow.parseJobData(args.map { Paths.get(it) }, executionModel, ResourceModel(), Environment())
    require(foundTensorFlowLogs) {
        "Cannot find TensorFlow logs in any of the given jobLogDirectories"
    }
    println("Execution model extracted from TensorFlow logs:")

    fun printPhase(phase: ExecutionPhase, indent: String) {
        val outFlows = phase.outFlows.sortedBy { it.identifier }
        println("$indent/${phase.identifier}")
        println(
            "$indent      Start time:          %d.%09d"
                .format(phase.startTime / 1_000_000_000, phase.startTime % 1_000_000_000)
        )
        println(
            "$indent      End time:            %d.%09d"
                .format(phase.endTime / 1_000_000_000, phase.endTime % 1_000_000_000)
        )
        println("$indent      Outgoing dataflows:  (${outFlows.joinToString(", ") { it.identifier }})")
        for (childPhase in phase.children.sortedBy { it.identifier }) {
            printPhase(childPhase, "$indent  ")
        }
    }
    for (topLevelPhase in executionModel.rootPhase.children.sortedBy { it.identifier }) {
        printPhase(topLevelPhase, "  ")
    }
}