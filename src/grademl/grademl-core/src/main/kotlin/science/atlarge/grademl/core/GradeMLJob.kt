package science.atlarge.grademl.core

import science.atlarge.grademl.core.attribution.ResourceAttribution
import science.atlarge.grademl.core.models.Environment
import science.atlarge.grademl.core.models.ExecutionModel
import science.atlarge.grademl.core.models.ResourceModel

class GradeMLJob(
    val unifiedExecutionModel: ExecutionModel,
    val unifiedResourceModel: ResourceModel,
    val jobEnvironment: Environment,
    val resourceAttribution: ResourceAttribution,
)