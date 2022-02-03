package science.atlarge.grademl.core

import science.atlarge.grademl.core.attribution.*
import science.atlarge.grademl.core.input.EnvironmentParser
import science.atlarge.grademl.core.input.InputSource
import science.atlarge.grademl.core.models.CommonMetadata
import science.atlarge.grademl.core.models.Environment
import science.atlarge.grademl.core.models.ExecutionModel
import science.atlarge.grademl.core.models.ResourceModel
import java.nio.file.Path

object GradeMLEngine {

    private val knownInputSources = mutableSetOf<InputSource>(
        EnvironmentParser
    )

    fun analyzeJob(
        jobDataDirectories: Iterable<Path>,
        jobOutputDirectory: Path,
        resourceAttributionSettings: ResourceAttributionSettings = ResourceAttributionSettings(),
        progressReport: (GradeMLJobStatusUpdate) -> Unit = { }
    ): GradeMLJob {
        fun createAttributionRuleProvider(
            executionModel: ExecutionModel,
            resourceModel: ResourceModel,
            environment: Environment
        ): ResourceAttributionRuleProvider {
            // Provider 1: Limit resource attribution to the machine a phase runs on
            val machineMapping = MappingAttributionRuleProvider.Mapping(CommonMetadata.MACHINE_ID) { id1, id2 ->
                val machine1 = environment.machineForId(id1)
                val machine2 = environment.machineForId(id2)
                if (machine1 != null && machine2 != null) machine1 === machine2
                else id1 == id2
            }
            // Provider 2: Regression fit of resource demand rules
            val bestFitProvider = BestFitAttributionRuleProvider.from(
                executionModel,
                resourceModel,
                jobOutputDirectory,
                MappingAttributionRuleProvider(listOf(machineMapping))
            )
            // Provider 3: Cache attribution rules to disk (if enabled)
            val cachedProvider = if (resourceAttributionSettings.enableRuleCaching) {
                CachingAttributionRuleProvider(jobOutputDirectory, bestFitProvider)
            } else bestFitProvider

            return cachedProvider
        }

        return GradeMLJobProcessor.processJob(
            jobDataDirectories,
            jobOutputDirectory,
            knownInputSources,
            ::createAttributionRuleProvider,
            resourceAttributionSettings,
            progressReport
        )
    }

    fun registerInputSource(inputSource: InputSource) {
        knownInputSources.add(inputSource)
    }

}