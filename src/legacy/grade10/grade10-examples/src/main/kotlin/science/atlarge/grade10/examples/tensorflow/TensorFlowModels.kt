package science.atlarge.grade10.examples.tensorflow

import science.atlarge.grade10.model.execution.PhaseTypeRepeatability
import science.atlarge.grade10.model.execution.buildExecutionModelSpecification
import science.atlarge.grade10.model.resources.ResourceModel
import science.atlarge.grade10.model.resources.buildResourceModel
import science.atlarge.grade10.model.resources.buildResourceModelSpecification
import science.atlarge.grade10.records.RecordStore
import science.atlarge.grade10.util.Time

object TensorFlowExecutionModel {

    val specification = buildExecutionModelSpecification {
        newSubphaseType("ModelFit") {
            newSubphaseType("CreateDataHandler")
            newSubphaseType("CreateTrainFunction")
            newSubphaseType("LoadInitialCheckpoint")
            newSubphaseType("Epoch", repeatability = PhaseTypeRepeatability.SequentialRepeated("epoch")) {
                newSubphaseType("TrainStep", repeatability = PhaseTypeRepeatability.SequentialRepeated("step"))
                newSubphaseType("ValidationStep")

                subphaseType("ValidationStep") after subphaseType("TrainStep")
            }

            subphaseType("CreateTrainFunction") after subphaseType("CreateDataHandler")
            subphaseType("LoadInitialCheckpoint") after subphaseType("CreateTrainFunction")
            subphaseType("Epoch") after subphaseType("LoadInitialCheckpoint")
        }
    }

}

object TensorFlowResourceModel {

    const val ROOT_NAME = "tensorflow"

    val specification = buildResourceModelSpecification {
        newSubresourceType(ROOT_NAME)
    }

    fun fromRecords(records: RecordStore, T: Time): ResourceModel {
        return buildResourceModel(specification, T) {
            newSubresource(ROOT_NAME)
        }
    }

}