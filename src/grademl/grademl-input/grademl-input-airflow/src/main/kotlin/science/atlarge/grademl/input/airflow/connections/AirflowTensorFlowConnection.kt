package science.atlarge.grademl.input.airflow.connections

import science.atlarge.grademl.core.models.ExecutionModel
import science.atlarge.grademl.core.models.ExecutionPhase
import science.atlarge.grademl.core.models.ResourceModel
import science.atlarge.grademl.input.airflow.AirflowDagId
import science.atlarge.grademl.input.airflow.AirflowLog
import science.atlarge.grademl.input.airflow.AirflowRunId
import science.atlarge.grademl.input.airflow.AirflowTaskId

object AirflowTensorFlowConnection : AirflowConnection {

    override fun processConnection(
        airflowLog: AirflowLog,
        phasesByDagRunAndTaskId: Map<AirflowDagId, Map<AirflowRunId, Map<AirflowTaskId, ExecutionPhase>>>,
        unifiedExecutionModel: ExecutionModel,
        unifiedResourceModel: ResourceModel
    ) {
        // Get a list of TensorFlow jobs to match
        val tensorFlowJobs = unifiedExecutionModel.rootPhase.children.filter { it.name == "TensorFlowJob" }
        // For each TensorFlow job, look up the corresponding Airflow task
        for (tensorFlowJob in tensorFlowJobs) {
            val dag = tensorFlowJob.tags["dag"]!!
            val run = tensorFlowJob.tags["run"]!!
            val task = tensorFlowJob.tags["task"]!!
            val airflowTask = phasesByDagRunAndTaskId[dag]!![run]!![task]!!
            // Migrate the TensorFlow job phase to be a child of the Airflow task phase
            unifiedExecutionModel.setParentOfPhase(tensorFlowJob, airflowTask)
            unifiedExecutionModel.updatePhase(tensorFlowJob, tags = emptyMap(), typeTags = emptySet())
        }
    }

}