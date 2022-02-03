package science.atlarge.grade10.examples.tensorflow

import science.atlarge.grade10.Grade10Platform
import science.atlarge.grade10.Grade10PlatformRegistry
import science.atlarge.grade10.model.execution.ExecutionModelSpecification
import science.atlarge.grade10.model.resources.ResourceModelSpecification
import java.nio.file.Path

object TensorFlowPlatform : Grade10Platform {

    override val name: String = "tensorflow"
    override val version: String = "2.2-model-1"
    override val executionModelSpecification: ExecutionModelSpecification = TensorFlowExecutionModel.specification
    override val resourceModelSpecification: ResourceModelSpecification = TensorFlowResourceModel.specification

    override fun createJob(inputDirectories: List<Path>, outputDirectory: Path): TensorFlowJob {
        return TensorFlowJob(inputDirectories, outputDirectory)
    }

    fun register() {
        Grade10PlatformRegistry.add(this)
    }

}