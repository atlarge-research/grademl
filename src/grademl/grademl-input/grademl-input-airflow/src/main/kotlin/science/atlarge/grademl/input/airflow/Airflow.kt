package science.atlarge.grademl.input.airflow

import science.atlarge.grademl.core.input.InputSource
import science.atlarge.grademl.core.models.Environment
import science.atlarge.grademl.core.models.ExecutionModel
import science.atlarge.grademl.core.models.ExecutionPhase
import science.atlarge.grademl.core.models.ResourceModel
import science.atlarge.grademl.input.airflow.connections.AirflowConnections
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.system.exitProcess

object Airflow : InputSource {

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

        // Parse Airflow log files
        val airflowLog = AirflowLogParser.parseFromDirectories(airflowLogDirectories)

        // Iterate over DAGs to build the execution model
        val phasesByDagRunAndTaskId = mutableMapOf<AirflowDagId, MutableMap<AirflowRunId,
                Map<AirflowTaskId, ExecutionPhase>>>()
        for ((dagId, dagLog) in airflowLog.dagLogs) {
            // Iterate over DAG runs to build the execution model
            for (runId in dagLog.runIds) {
                // Determine which tasks exist for this run
                val tasksInRun = dagLog.taskStartTimesPerRun[runId]?.keys.orEmpty()
                val runStartTime = (dagLog.taskStartTimesPerRun[runId]?.values?.minOrNull()
                    ?: dagLog.taskStartTimesPerRun.values.flatMap { it.values }.minOrNull() ?: 0L)
                val runEndTime = (dagLog.taskEndTimesPerRun[runId]?.values?.maxOrNull() ?: runStartTime)
                // Add a phase for the DAG run and for each task
                val runPhase = unifiedExecutionModel.addPhase(
                    name = dagId,
                    tags = mapOf("run_id" to runId),
                    typeTags = emptySet(),
                    startTime = runStartTime,
                    endTime = runEndTime
                )
                val taskPhases = tasksInRun.associateWith { taskName ->
                    unifiedExecutionModel.addPhase(
                        name = taskName,
                        startTime = dagLog.taskStartTimesPerRun[runId]!![taskName]!!,
                        endTime = dagLog.taskEndTimesPerRun[runId]!![taskName]!!,
                        parent = runPhase
                    )
                }
                // Add dataflow relationships between tasks
                for ((upstream, downstreamIds) in dagLog.taskDownstreamIds) {
                    val upstreamPhase = taskPhases[upstream] ?: continue
                    for (downstream in downstreamIds) {
                        val downstreamPhase = taskPhases[downstream] ?: continue
                        upstreamPhase.addOutgoingDataflow(downstreamPhase)
                    }
                }
                // Add the task phases to a lookup table
                phasesByDagRunAndTaskId.getOrPut(dagId) { mutableMapOf() }[runId] = taskPhases
            }
        }

        // Handle connections with other frameworks supported by GradeML (e.g., modify SparkApplication execution
        // phases to be the children of corresponding Airflow tasks)
        AirflowConnections.processConnectionsForAirflowLogs(
            airflowLog,
            phasesByDagRunAndTaskId,
            unifiedExecutionModel,
            unifiedResourceModel
        )

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
    val foundAirflowLogs = Airflow.parseJobData(
        args.map { Paths.get(it) },
        executionModel,
        ResourceModel(),
        Environment()
    )
    require(foundAirflowLogs) {
        "Cannot find Airflow logs in any of the given jobLogDirectories"
    }
    println("Execution model extracted from Airflow logs:")

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