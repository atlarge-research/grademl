package science.atlarge.grademl.core.attribution

import science.atlarge.grademl.core.models.ExecutionPhase
import science.atlarge.grademl.core.models.Metric

class MappingAttributionRuleProvider(
    mappings: Iterable<Mapping>
) : ResourceAttributionRuleProvider {

    private val mappingsByName = mappings.associateBy { it.metadataName }

    init {
        for ((metadataName, matchingMappings) in mappings.groupBy { it.metadataName }) {
            require(matchingMappings.size == 1) {
                "MappingAttributionRuleProvider does not support more than on mapping per metadata tag, " +
                        "found ${matchingMappings.size} for \"$metadataName\""
            }
        }
    }

    override fun forPhaseAndMetric(phase: ExecutionPhase, metric: Metric): ResourceAttributionRule? {
        // Get metadata for phase and the resource associated with the given metric
        val phaseMetadata = selectMetadata(generateSequence(phase) { it.parent }.map { it.metadata })
        val resourceMetadata = selectMetadata(generateSequence(metric.resource) { it.parent }.map { it.metadata })

        // Find metadata that has a defined mapping and a value for both the phase and resource
        val relevantMetadata = mappingsByName.keys.intersect(phaseMetadata.keys).intersect(resourceMetadata.keys)

        // Check if all selected metadata matches between the phase and resource
        val metadataMatches = relevantMetadata.all { name ->
            mappingsByName[name]!!.equalityCheck(phaseMetadata[name]!!, resourceMetadata[name]!!)
        }

        // If metadata does not match, disable resource attribution using the None rule
        return if (!metadataMatches) {
            ResourceAttributionRule.None
        } else {
            null
        }
    }

    private fun selectMetadata(metadataMaps: Sequence<Map<String, String>>): Map<String, String> {
        val selectedMetadata = mutableMapOf<String, String>()
        for (map in metadataMaps) {
            for ((k, v) in map) {
                if (k !in selectedMetadata) selectedMetadata[k] = v
            }
        }
        return selectedMetadata
    }

    class Mapping(
        val metadataName: String,
        val equalityCheck: (String, String) -> Boolean = { a, b -> a == b }
    )

}