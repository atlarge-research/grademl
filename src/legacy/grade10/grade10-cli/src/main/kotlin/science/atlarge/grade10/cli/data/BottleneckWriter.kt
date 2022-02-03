package science.atlarge.grade10.cli.data

import science.atlarge.grade10.Grade10JobResult
import science.atlarge.grade10.cli.util.MetricList
import science.atlarge.grade10.cli.util.PhaseList
import science.atlarge.grade10.model.execution.Phase
import science.atlarge.grade10.util.TimesliceId
import java.io.File

object BottleneckWriter {

    const val FILENAME = "bottlenecks.tsv"
    const val SUBPHASE_FILENAME = "subphase-bottlenecks.tsv"

    fun output(
            outFile: File,
            jobResult: Grade10JobResult,
            phase: Phase,
            phaseList: PhaseList,
            metricList: MetricList
    ) {
        write(outFile, jobResult, listOf(phase), phase.firstTimeslice, phase.lastTimeslice, phaseList, metricList)
    }

    fun outputForSubphases(
            outFile: File,
            jobResult: Grade10JobResult,
            parentPhase: Phase,
            phaseList: PhaseList,
            metricList: MetricList
    ) {
        write(outFile, jobResult, parentPhase.subphases.values.toList(), parentPhase.firstTimeslice,
                parentPhase.lastTimeslice, phaseList, metricList)
    }

    private fun write(
            outFile: File,
            jobResult: Grade10JobResult,
            phases: List<Phase>,
            firstTimeslice: TimesliceId,
            lastTimeslice: TimesliceId,
            phaseList: PhaseList,
            metricList: MetricList
    ) {
        outFile.bufferedWriter().use { writer ->
            writer.appendln("phase.id\tmetric.type.id\tstart.time.slice\tend.time.slice.inclusive\tis.bottleneck")
            for (phase in phases) {
                val phaseStart = jobResult.absoluteTimesliceToRelative(maxOf(phase.firstTimeslice, firstTimeslice))
                val phaseEnd = jobResult.absoluteTimesliceToRelative(minOf(phase.lastTimeslice, lastTimeslice))
                if (phaseEnd < 0 || phaseEnd < phaseStart) {
                    continue
                }
                val timeSlicesToSkip = maxOf(0, firstTimeslice - phase.firstTimeslice).toInt()

                val phaseId = phaseList.phaseToIdentifier(phase)
                val phaseResult = jobResult.bottleneckIdentificationResult.metricTypeBottlenecks[phase]
                for (metricType in phaseResult.metricTypes) {
                    val metricId = metricList.metricTypeToIdentifier(metricType)
                    fun writeLine(start: Int, end: Int, isBottleneck: Boolean) {
                        writer.append(phaseId).append('\t')
                                .append(metricId).append('\t')
                                .append(start.toString()).append('\t')
                                .append(end.toString()).append('\t')
                                .appendln(if (isBottleneck) '1' else '0')
                    }

                    val iter = phaseResult.bottleneckIterator(metricType)
                    var time = phaseStart
                    // Skip until the start of the parent phase
                    repeat(timeSlicesToSkip) {
                        iter.nextBottleneckStatus()
                    }
                    // Get the initial bottleneck state
                    var isBottlenecked = iter.nextBottleneckStatus() != 0.toByte()
                    var startTime = time
                    time++
                    while (time <= phaseEnd) {
                        val newIsBottlenecked = iter.nextBottleneckStatus() != 0.toByte()
                        if (newIsBottlenecked != isBottlenecked) {
                            writeLine(startTime, time - 1, isBottlenecked)
                            isBottlenecked = newIsBottlenecked
                            startTime = time
                        }
                        time++
                    }
                    writeLine(startTime, time - 1, isBottlenecked)
                }
            }
        }
    }

}