package science.atlarge.grademl.input.airflow.connections

import science.atlarge.grademl.core.models.ExecutionModel
import science.atlarge.grademl.core.models.ExecutionPhase
import science.atlarge.grademl.core.models.ResourceModel
import science.atlarge.grademl.input.airflow.AirflowDagId
import science.atlarge.grademl.input.airflow.AirflowLog
import science.atlarge.grademl.input.airflow.AirflowRunId
import science.atlarge.grademl.input.airflow.AirflowTaskId

object AirflowConnections {

    private val connections = listOf(
        AirflowSparkConnection,
        AirflowTensorFlowConnection
    )

    fun processConnectionsForAirflowLogs(
        airflowLog: AirflowLog,
        phasesByDagRunAndTaskId: Map<AirflowDagId, Map<AirflowRunId, Map<AirflowTaskId, ExecutionPhase>>>,
        unifiedExecutionModel: ExecutionModel,
        unifiedResourceModel: ResourceModel
    ) {
        for (connection in connections) {
            connection.processConnection(
                airflowLog,
                phasesByDagRunAndTaskId,
                unifiedExecutionModel,
                unifiedResourceModel
            )
        }
    }

}

interface AirflowConnection {
    fun processConnection(
        airflowLog: AirflowLog,
        phasesByDagRunAndTaskId: Map<AirflowDagId, Map<AirflowRunId, Map<AirflowTaskId, ExecutionPhase>>>,
        unifiedExecutionModel: ExecutionModel,
        unifiedResourceModel: ResourceModel
    )
}