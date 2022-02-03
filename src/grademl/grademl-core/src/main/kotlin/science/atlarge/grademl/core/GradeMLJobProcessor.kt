package science.atlarge.grademl.core

import science.atlarge.grademl.core.attribution.ResourceAttribution
import science.atlarge.grademl.core.attribution.ResourceAttributionRuleProvider
import science.atlarge.grademl.core.attribution.ResourceAttributionSettings
import science.atlarge.grademl.core.input.InputSource
import science.atlarge.grademl.core.models.Environment
import science.atlarge.grademl.core.models.ExecutionModel
import science.atlarge.grademl.core.models.ResourceModel
import java.nio.file.Path
import kotlin.system.measureNanoTime

class GradeMLJobProcessor private constructor(
    private val inputDirectories: Iterable<Path>,
    private val outputDirectory: Path,
    private val inputSources: Iterable<InputSource>,
    private val attributionRuleProvider: (ExecutionModel, ResourceModel, Environment) -> ResourceAttributionRuleProvider,
    private val resourceAttributionSettings: ResourceAttributionSettings,
    private val progressReport: (GradeMLJobStatusUpdate) -> Unit
) {

    private val executionModel = ExecutionModel()
    private val resourceModel = ResourceModel()
    private val jobEnvironment = Environment()
    private lateinit var resourceAttribution: ResourceAttribution

    fun run(): GradeMLJob {
        progressReport(GradeMLJobStatusUpdate.JOB_ANALYSIS_STARTING)

        // Parse logs
        progressReport(GradeMLJobStatusUpdate.LOG_PARSING_STARTING)
        for (inputSource in inputSources) {
            val t = measureNanoTime {
                inputSource.parseJobData(inputDirectories, executionModel, resourceModel, jobEnvironment)
            }
            println(
                "Time taken to process input source ${inputSource.javaClass.canonicalName}: " +
                        "${String.format("%.2f", t / 1_000_000.0)} ms"
            )
        }
        progressReport(GradeMLJobStatusUpdate.LOG_PARSING_COMPLETED)

        // Configure resource attribution
        resourceAttribution = ResourceAttribution(
            executionModel, resourceModel, attributionRuleProvider(executionModel, resourceModel, jobEnvironment),
            resourceAttributionSettings
        )

        progressReport(GradeMLJobStatusUpdate.JOB_ANALYSIS_COMPLETED)
        return GradeMLJob(executionModel, resourceModel, jobEnvironment, resourceAttribution)
    }

    companion object {
        fun processJob(
            inputDirectories: Iterable<Path>,
            outputDirectory: Path,
            inputSources: Iterable<InputSource>,
            attributionRuleProvider: (ExecutionModel, ResourceModel, Environment) -> ResourceAttributionRuleProvider,
            resourceAttributionSettings: ResourceAttributionSettings = ResourceAttributionSettings(),
            progressReport: (GradeMLJobStatusUpdate) -> Unit = { }
        ): GradeMLJob {
            return GradeMLJobProcessor(
                inputDirectories,
                outputDirectory,
                inputSources,
                attributionRuleProvider,
                resourceAttributionSettings,
                progressReport
            ).run()
        }
    }

}