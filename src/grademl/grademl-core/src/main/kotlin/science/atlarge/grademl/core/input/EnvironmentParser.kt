package science.atlarge.grademl.core.input

import science.atlarge.grademl.core.models.Environment
import science.atlarge.grademl.core.models.ExecutionModel
import science.atlarge.grademl.core.models.Machine
import science.atlarge.grademl.core.models.ResourceModel
import java.nio.file.Path
import kotlin.io.path.readLines

object EnvironmentParser : InputSource {

    override fun parseJobData(
        jobDataDirectories: Iterable<Path>,
        unifiedExecutionModel: ExecutionModel,
        unifiedResourceModel: ResourceModel,
        jobEnvironment: Environment
    ): Boolean {
        return parseMachineIdList(jobDataDirectories, jobEnvironment)
    }

    private fun parseMachineIdList(jobDataDirectories: Iterable<Path>, jobEnvironment: Environment): Boolean {
        // Find "hosts" files in the job data directories
        val hostsFiles = jobDataDirectories.map { it.resolve("hosts") }.filter { it.toFile().isFile }

        // Parse each hosts file
        val machines = mutableMapOf<String, MutableSet<String>>()
        val allMachineIds = mutableSetOf<String>()
        for (hostsFile in hostsFiles) {
            // Read the hosts file, ignoring all blank lines and lines starting with a # (i.e., a comment)
            val lines = hostsFile.readLines()
                .map { it.trim() }
                .filter { it.isNotBlank() && !it.startsWith('#') }
            for (line in lines) {
                val ids = line.split(' ', '\t').filter { it.isNotBlank() }
                // Merge host information with previous information on the same host, if it exists
                if (ids[0] in machines) {
                    val newIds = ids - machines[ids[0]]!!
                    require(newIds.none { it in allMachineIds }) {
                        "Detected duplicate machine ID (\"${newIds.intersect(allMachineIds).first()}\") " +
                                "while adding machine with canonical ID: \"${ids[0]}\""
                    }
                    allMachineIds.addAll(newIds)
                    machines[ids[0]]!!.addAll(newIds)
                } else {
                    require(ids.none { it in allMachineIds }) {
                        "Detected duplicate machine ID (\"${ids.intersect(allMachineIds).first()}\") " +
                                "while adding machine with canonical ID: \"${ids[0]}\""
                    }
                    allMachineIds.addAll(ids)
                    machines[ids[0]] = ids.toMutableSet()
                }
            }
        }

        // Add machines to the given Environment description
        for ((canonicalId, allIds) in machines) {
            jobEnvironment.addMachine(Machine(canonicalId, allIds - canonicalId))
        }

        return machines.isNotEmpty()
    }

}