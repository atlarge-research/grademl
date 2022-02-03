package science.atlarge.grademl.input.resource_monitor

import java.io.File

interface FileParser<out T> {

    fun parse(hostname: String, metricFiles: Iterable<File>): T

}