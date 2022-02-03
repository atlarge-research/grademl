package science.atlarge.grademl.core.util

import science.atlarge.grademl.core.models.Path
import java.io.File
import java.io.InputStream

fun instantiateRScript(rScriptFile: File, settings: Map<String, String> = emptyMap()) {
    val rScriptStream = Path::class.java.getResourceAsStream("/${rScriptFile.name}")!!
    if (rScriptFile.exists()) {
        rScriptFile.delete()
    }
    writeRScript(rScriptStream, rScriptFile, settings)
}

fun writeRScript(sourceStream: InputStream, destination: File, settings: Map<String, String> = emptyMap()) {
    val settingRegex = """^\s*#+\s*:setting\s+([^\s]+)\s+.*$""".toRegex()
    sourceStream.bufferedReader().useLines { inputLines ->
        destination.bufferedWriter().use { writer ->
            inputLines.forEach { line ->
                val settingMatch = settingRegex.matchEntire(line)
                if (settingMatch == null) {
                    writer.appendLine(line)
                } else {
                    val settingName = settingMatch.groupValues[1]
                    val settingValue = settings[settingName] ?: throw IllegalArgumentException(
                        "Missing value for setting $settingName"
                    )
                    writer.appendLine("$settingName <- $settingValue")
                }
            }
        }
    }
}

fun runRScript(rScriptFile: File): Boolean {
    val rScriptExec = System.getenv().getOrElse("RSCRIPT") { "Rscript" }
    val rScriptDirectory = rScriptFile.parentFile

    val pb = ProcessBuilder(rScriptExec, rScriptFile.absolutePath)
    pb.directory(rScriptDirectory)
    pb.redirectErrorStream(true)
    pb.redirectOutput(rScriptDirectory.resolve(rScriptFile.nameWithoutExtension + ".log"))
    return pb.start().waitFor() == 0
}

fun File.asRPathString(): String {
    return if ("win" in System.getProperty("os.name").lowercase()) {
        "shortPathName(\"${canonicalPath.replace("\\", "\\\\")}\")"
    } else {
        "\"$canonicalPath\""
    }
}