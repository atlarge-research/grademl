package science.atlarge.grade10.examples.tensorflow

import science.atlarge.grade10.records.EventRecord
import science.atlarge.grade10.records.EventRecordType
import science.atlarge.grade10.records.Record
import science.atlarge.grade10.records.extraction.LogFileParser
import science.atlarge.grade10.util.TimestampNs

class TensorFlowLogFileParser : LogFileParser {

    override fun parseLine(line: String): Record? {
        return operationLogRegex.find(line)?.let { match ->
            val keyValuePairs = parseKeyValuePairs(match.groupValues[1])

            val timestampMicroseconds = keyValuePairs["time"]?.toLong()
                    ?: throw IllegalArgumentException("Line from TensorFlow log is missing the 'time' property: $line")
            val timestampNs: TimestampNs = timestampMicroseconds * 1000
            val event = keyValuePairs["event"]
                    ?: throw IllegalArgumentException("Line from TensorFlow log is missing the 'event' property: $line")

            val eventType = when (event) {
                "start-phase" -> EventRecordType.START
                "end-phase" -> EventRecordType.END
                "log" -> EventRecordType.SINGLE
                else -> throw IllegalArgumentException("Line from TensorFlow log contains invalid 'event' property: $line")
            }

            val meta = keyValuePairs.filterKeys { it != "time" && it != "event" }

            EventRecord(timestampNs, eventType, meta)
        }
    }

    companion object {

        private val operationLogRegex = Regex("""INFO:tensorflow:\[GRADE10] (.*)""")

        private fun parseKeyValuePairs(line: String): Map<String, String> =
                line.split(",")
                        .map { parseKeyValuePair(it) }
                        .toMap()

        private fun parseKeyValuePair(str: String): Pair<String, String> {
            val (k, v) = str.split("=", limit = 2).map(String::trim)
            return k to v
        }

    }

}
