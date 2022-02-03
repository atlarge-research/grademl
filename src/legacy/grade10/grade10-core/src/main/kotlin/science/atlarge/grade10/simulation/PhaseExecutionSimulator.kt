package science.atlarge.grade10.simulation

import science.atlarge.grade10.model.execution.ExecutionModel
import science.atlarge.grade10.model.execution.Phase
import science.atlarge.grade10.model.execution.PhaseType

interface PhaseExecutionSimulator {

    fun simulate(rootPhase: Phase, phaseDurations: PhaseDurationMap): PhaseExecutionSimulationResult

    fun newPhaseDurationMap(rootPhase: Phase): PhaseDurationMap

}

class DefaultPhaseExecutionSimulator(
        executionModel: ExecutionModel,
        schedulingConstraintRules: List<SchedulingConstraintRule> = emptyList()
) : PhaseExecutionSimulator {

    private val phases = constructPhaseArray(executionModel)
    private val phaseIdMap = constructPhaseIdMap(phases)
    private val phaseTreeSizes = constructPhaseTreeSizes(executionModel.rootPhase, phaseIdMap)
    private val taskDependencies = constructTaskDependencies(executionModel.rootPhase, phaseIdMap)
    private val reverseTaskDependencies = constructReverseTaskDependencies(taskDependencies)
    private val schedulingConstraintLimits: LongArray
    private val schedulingConstraintPerPhase: IntArray
    private val schedulingConstraintUsagePerPhase: LongArray

    private val defaultPhaseDurations: PhaseDurationMap

    init {
        val schedulingConstraints = schedulingConstraintRules.flatMap {
            it.createSchedulingConstraints(executionModel)
        }.toTypedArray()
        schedulingConstraintLimits = LongArray(schedulingConstraints.size) { i ->
            schedulingConstraints[i].resourceLimit
        }

        schedulingConstraintPerPhase = IntArray(phases.size)
        schedulingConstraintUsagePerPhase = LongArray(phases.size)
        for (c in 0 until schedulingConstraints.size) {
            val constraint = schedulingConstraints[c]
            for ((p, l) in constraint.phaseRequirements) {
                val phaseId = phaseIdMap.get(p, -1)
                require(schedulingConstraintPerPhase[phaseId] == 0) {
                    "Multiple constraints per phase not supported"
                }
                schedulingConstraintPerPhase[phaseId] = c + 1
                schedulingConstraintUsagePerPhase[phaseId] = l
            }
        }

        val blockedPhaseOverride = constructBlockedPhaseOverride(phaseIdMap, schedulingConstraints)
        defaultPhaseDurations = constructPhaseDurationMap(phases, phaseIdMap, phaseTreeSizes, blockedPhaseOverride)
    }

    override fun simulate(rootPhase: Phase, phaseDurations: PhaseDurationMap): PhaseExecutionSimulationResult {
        return PhaseExecutionSimulation(rootPhase, phaseDurations, phaseIdMap, phaseTreeSizes,
                taskDependencies, reverseTaskDependencies, schedulingConstraintLimits, schedulingConstraintPerPhase,
                schedulingConstraintUsagePerPhase).simulate()
    }

    override fun newPhaseDurationMap(rootPhase: Phase) = defaultPhaseDurations.subset(rootPhase)

    companion object {
        private fun constructPhaseArray(executionModel: ExecutionModel): Array<Phase> {
            val phaseList = arrayListOf<Phase>()
            fun addPhase(phase: Phase) {
                phaseList.add(phase)
                phase.subphases.values
                        .sortedWith(compareBy({ it.firstTimeslice }, { it.lastTimeslice }, { it.path }))
                        .forEach(::addPhase)
            }
            addPhase(executionModel.rootPhase)
            return phaseList.toTypedArray()
        }

        private fun constructPhaseIdMap(phases: Array<Phase>): PhaseIdMap {
            val map = PhaseIdMap(phases.size)
            for (i in phases.indices) {
                map.put(phases[i], i)
            }
            return map
        }

        private fun constructPhaseTreeSizes(rootPhase: Phase, phaseIdMap: PhaseIdMap): IntArray {
            val phaseTreeSizes = IntArray(phaseIdMap.size)
            fun addPhase(phase: Phase): Int {
                var subCount = 1
                for (subphase in phase.subphases.values) {
                    subCount += addPhase(subphase)
                }
                phaseTreeSizes[phaseIdMap.get(phase, -1)] = subCount
                return subCount
            }
            addPhase(rootPhase)
            return phaseTreeSizes
        }

        private fun constructTaskDependencies(
                rootPhase: Phase,
                phaseIdMap: PhaseIdMap
        ): Array<ScheduleTaskIdArray> {
            val empty = ScheduleTaskIdArray(0)
            val taskDependencyLists = Array(phaseIdMap.size * 2) { empty }
            val phaseIsDependency = BooleanArray(phaseIdMap.size)

            val endDeps = mutableListOf<Int>()
            fun addPhase(phase: Phase) {
                val phaseId = phaseIdMap.get(phase, -1)
                // Find IDs of all end tasks corresponding with dependencies of `phase`
                if (phase.dependencies.isNotEmpty()) {
                    val depIds = ScheduleTaskIdArray(phase.dependencies.size)
                    phase.dependencies.forEachIndexed { i, dep ->
                        val depId = phaseIdMap.get(dep, -1)
                        phaseIsDependency[depId] = true
                        depIds[i] = depId * 2 + 1 // ID of end task
                    }
                    depIds.sort()
                    taskDependencyLists[phaseId * 2] = depIds
                } else if (phase.parent != null) {
                    // If `phase` has no dependencies, add a dependency on the start of the parent phase, if any
                    taskDependencyLists[phaseId * 2] = intArrayOf(phaseIdMap.get(phase.parent, -1) * 2)
                }
                // Recursively add subphases
                phase.subphases.values.forEach(::addPhase)
                // Find subphases that are not dependencies, add them as dependency for the end of `phase`
                endDeps.clear()
                for (subphase in phase.subphases.values) {
                    val subphaseId = phaseIdMap.get(subphase, -1)
                    if (!phaseIsDependency[subphaseId]) {
                        endDeps.add(subphaseId * 2 + 1)
                    }
                }
                if (endDeps.isNotEmpty()) {
                    taskDependencyLists[phaseId * 2 + 1] = endDeps.toIntArray().also { it.sort() }
                } else {
                    taskDependencyLists[phaseId * 2 + 1] = intArrayOf(phaseId * 2)
                }
            }

            addPhase(rootPhase)
            return taskDependencyLists
        }

        private fun constructReverseTaskDependencies(taskDependencies: Array<ScheduleTaskIdArray>):
                Array<ScheduleTaskIdArray> {
            val reverseTaskDependencies = Array(taskDependencies.size) { mutableListOf<Int>() }
            for (tid in taskDependencies.indices) {
                for (did in taskDependencies[tid]) {
                    reverseTaskDependencies[did].add(tid)
                }
            }
            return Array(taskDependencies.size) { i -> reverseTaskDependencies[i].toIntArray().also { it.sort() } }
        }

        private fun constructPhaseDurationMap(
                phases: Array<Phase>,
                phaseIdMap: PhaseIdMap,
                phaseTreeSizes: IntArray,
                blockedPhaseOverride: BooleanArray
        ): PhaseDurationMap {
            val map = PhaseDurationMap(phases[0], phaseIdMap, phaseTreeSizes, blockedPhaseOverride)
            phases.forEachIndexed { index, phase ->
                if (phase.isLeaf) {
                    map[index] = phase.timesliceDuration.toDouble()
                }
            }
            return map
        }

        private fun constructBlockedPhaseOverride(
                phaseIdMap: PhaseIdMap,
                schedulingConstraints: Array<SchedulingConstraint>
        ): BooleanArray {
            fun findCommonAncestor(phases: Set<Phase>): Phase {
                require(phases.isNotEmpty())
                var currentPick = phases.first()
                for (p in phases) {
                    while (p.path !in currentPick.path) {
                        currentPick = currentPick.parent!!
                    }
                }
                return currentPick
            }
            fun findConcurrentPhasesWithSameParent(phases: Set<Phase>): List<Set<Phase>> {
                // Resolve all dependencies between the given set of phases
                val allDependencies = phases.associate { p ->
                    val deps = HashSet(p.dependencies)
                    val depsToCheck = ArrayList(deps)
                    while (depsToCheck.isNotEmpty()) {
                        val d = depsToCheck.removeAt(depsToCheck.lastIndex)
                        for (d2 in d.dependencies) {
                            if (d2 !in deps) {
                                deps.add(d2)
                                depsToCheck.add(d2)
                            }
                        }
                    }
                    p to deps.filter { it in phases }
                }.toMutableMap()
                // Find all phases such that every other phase is either a dependency of or dependent on
                // the selected phase. These phases are purely sequential, all others are not
                val sequentialPhases = phases.filter { p ->
                    val depCount = allDependencies[p]!!.size
                    val revDepCount = allDependencies.count { (_, d) -> d.contains(p) }
                    depCount + revDepCount + 1 == phases.size
                }.toSet()
                val nonSequentialPhaseGroups = (phases - sequentialPhases)
                        .groupBy { p ->
                            allDependencies[p]!!.intersect(sequentialPhases)
                        }
                        .values.map { it.toSet() }
                // Return the identified phases
                return sequentialPhases.map { setOf(it) } + nonSequentialPhaseGroups
            }
            fun findDisjointSubsets(phases: Set<Phase>, ancestor: Phase): List<Set<Phase>> {
                if (phases.isEmpty()) return emptyList()
                if (phases.size == 1) return listOf(phases)
                // Find for each phase the next lower ancestor so we can check which are disjoint
                val nextAncestorPerPhase = phases.map {
                    var p = it
                    while (p.parent !== ancestor) {
                        p = p.parent!!
                    }
                    p to it
                }
                val phasesPerAncestor = nextAncestorPerPhase.groupBy({ it.first }, { it.second })
                val identifiedAncestors = phasesPerAncestor.keys
                // Find concurrent ancestors
                val concurrentAncestorSets = findConcurrentPhasesWithSameParent(identifiedAncestors)
                // Collect resulting disjoint sets (splitting sets recursively where possible)
                val results = mutableListOf<Set<Phase>>()
                for (s in concurrentAncestorSets) {
                    if (s.size > 1) {
                        results.add(s.flatMap { phasesPerAncestor[it]!! }.toSet())
                    } else {
                        val s1 = s.first()
                        results.addAll(findDisjointSubsets(phasesPerAncestor[s1]!!.toSet(), s1))
                    }
                }
                return results
            }

            val isBlocked = BooleanArray(phaseIdMap.size)
            for (c in schedulingConstraints) {
                val phases = c.phaseRequirements.keys
                // Find the common ancestor of all phases that share this constraint
                val commonAncestor = findCommonAncestor(phases)
                // Find disjoint subsets of phases such that phases from different sets can never run in parallel
                val disjointSets = findDisjointSubsets(phases, commonAncestor)
                // Block all ancestor that cover at least one but not all phases in any disjoint set
                for (s in disjointSets) {
                    val setAncestor = findCommonAncestor(s)
                    for (p in s) {
                        if (p === setAncestor) continue
                        var par = p.parent!!
                        while (par !== setAncestor) {
                            isBlocked[phaseIdMap.get(par, -1)] = true
                            par = par.parent!!
                        }
                    }
                }
            }
            return isBlocked
        }
    }

}

private class PhaseExecutionSimulation(
        rootPhase: Phase,
        private val phaseDurations: PhaseDurationMap,
        private val phaseIdMap: PhaseIdMap,
        phaseTreeSizes: IntArray,
        taskDependencies: Array<ScheduleTaskIdArray>,
        private val reverseTaskDependencies: Array<ScheduleTaskIdArray>,
        constraintLimits: LongArray,
        private val constraintPerPhase: IntArray,
        private val constraintSizePerPhase: LongArray
) {

    private val taskQueue = ScheduleTaskIdQueue()
    private val pendingTaskCompletions = PendingTaskQueue()
    private val rootPhaseId = phaseIdMap.get(rootPhase, -1)
    private val rootTaskId = 2 * rootPhaseId
    private val phaseCount = phaseTreeSizes[rootPhaseId]
    private val phaseStartTimes = DoubleArray(phaseCount) { Double.POSITIVE_INFINITY }
    private val phaseEndTimes = DoubleArray(phaseCount) { Double.POSITIVE_INFINITY }
    private val remainingTaskDependencyCounts = IntArray(phaseCount * 2) { taskIdOffset ->
        taskDependencies[taskIdOffset + rootPhaseId * 2].size
    }
    private val remainingConstraintLimits = constraintLimits.copyOf()
    private val pendingTasksPerConstraint = Array(constraintLimits.size) { IntQueue() }
    private val constraintsToCheck = IntQueue()

    fun simulate(): PhaseExecutionSimulationResult {
        var currentTime = 0.0
        taskQueue.push(rootPhaseId * 2)

        while (phaseEndTimes[0] == Double.POSITIVE_INFINITY && (taskQueue.isNotEmpty() ||
                        constraintsToCheck.isNotEmpty() || pendingTaskCompletions.isNotEmpty())) {
            do {
                // Process pending tasks
                while (taskQueue.isNotEmpty()) {
                    val nextTask = taskQueue.pop()
                    processTask(currentTime, nextTask)
                }
                // Check if any constraints have freed up to add more tasks to the queue
                while (constraintsToCheck.isNotEmpty()) {
                    val constraint = constraintsToCheck.pop()
                    checkConstraint(constraint)
                }
            } while (taskQueue.isNotEmpty())
            // Move to the next timestamp if there are pending task completions
            if (pendingTaskCompletions.isNotEmpty()) {
                currentTime = pendingTaskCompletions.peekValue()
                while (pendingTaskCompletions.isNotEmpty() && pendingTaskCompletions.peekValue() == currentTime) {
                    tryQueue(pendingTaskCompletions.peekKey())
                    pendingTaskCompletions.pop()
                }
            }
        }

        require(phaseEndTimes[0] != Double.POSITIVE_INFINITY) { "Root phase never completed" }

        return PhaseExecutionSimulationResult(rootPhaseId, phaseIdMap, phaseStartTimes, phaseEndTimes)
    }

    fun processTask(currentTime: Double, task: ScheduleTaskId) {
        if (task % 2 == 0) startPhase(currentTime, task / 2, task)
        else completePhase(currentTime, task / 2, task)
    }

    fun startPhase(currentTime: Double, phase: PhaseId, task: ScheduleTaskId) {
        phaseStartTimes[phase - rootPhaseId] = currentTime

        val duration = phaseDurations[phase]
        if (duration != Double.POSITIVE_INFINITY) {
            // If the duration of this phase is defined, there is no need to simulate it and its subphases
            pendingTaskCompletions.insert(task + 1, currentTime + duration)
        } else {
            // Check if any tasks can start the depend on the completion of this task
            for (d in reverseTaskDependencies[task]) {
                remainingTaskDependencyCounts[d - rootTaskId]--
                if (remainingTaskDependencyCounts[d - rootTaskId] == 0) {
                    tryQueue(d)
                }
            }
        }
    }

    fun completePhase(currentTime: Double, phase: PhaseId, task: ScheduleTaskId) {
        phaseEndTimes[phase - rootPhaseId] = currentTime
        // Release resources used by this phase as part of scheduling constraints (if any)
        if (constraintPerPhase[phase] != 0) {
            val constraintId = constraintPerPhase[phase] - 1
            remainingConstraintLimits[constraintId] += constraintSizePerPhase[phase]
            if (pendingTasksPerConstraint[constraintId].isNotEmpty()) {
                constraintsToCheck.push(constraintId)
            }
        }
        // Check if any tasks can start the depend on the completion of this task
        if (phase != rootPhaseId) {
            for (d in reverseTaskDependencies[task]) {
                remainingTaskDependencyCounts[d - rootTaskId]--
                if (remainingTaskDependencyCounts[d - rootTaskId] == 0) {
                    tryQueue(d)
                }
            }
        }
    }

    fun tryQueue(taskId: ScheduleTaskId) {
        if (taskId % 2 == 0) {
            // Phase-start tasks may have concurrency constraints
            val phaseId = taskId / 2
            if (constraintPerPhase[phaseId] == 0) {
                // Start the phase immediately if it has no constraints
                taskQueue.push(taskId)
            } else {
                processConstrainedPhaseStart(phaseId)
            }
        } else {
            // Phase-end tasks can never have constraints
            taskQueue.push(taskId)
        }
    }

    /**
     * Implements the scheduling policy for starting resource-constrained phases. Default policy: FCFS.
     */
    fun processConstrainedPhaseStart(phaseId: PhaseId) {
        val taskId = phaseId * 2
        val constraintId = constraintPerPhase[phaseId] - 1
        // If there is a queue, append this phase
        if (pendingTasksPerConstraint[constraintId].isNotEmpty()) {
            pendingTasksPerConstraint[constraintId].push(taskId)
        } else {
            // Check if the phase can start immediately or needs to be enqueued
            val remainingLimit = remainingConstraintLimits[constraintId]
            val requirement = constraintSizePerPhase[phaseId]
            if (requirement <= remainingLimit) {
                // If the phase requires no more resources than available, schedule it
                remainingConstraintLimits[constraintId] -= requirement
                taskQueue.push(taskId)
            } else {
                // Otherwise, queue it
                pendingTasksPerConstraint[constraintId].push(taskId)
            }
        }
    }

    fun checkConstraint(constraintId: Int) {
        while (pendingTasksPerConstraint[constraintId].isNotEmpty()) {
            val remainingLimit = remainingConstraintLimits[constraintId]
            val nextTask = pendingTasksPerConstraint[constraintId].peek()
            val taskRequirement = constraintSizePerPhase[nextTask / 2]
            if (taskRequirement <= remainingLimit) {
                pendingTasksPerConstraint[constraintId].pop()
                taskQueue.push(nextTask)
                remainingConstraintLimits[constraintId] -= taskRequirement
            } else {
                return
            }
        }
    }

}

interface SchedulingConstraintRule {

    fun createSchedulingConstraints(executionModel: ExecutionModel): Iterable<SchedulingConstraint>

}

/**
 * Strong requirement for constraint: there must not be an ancestry relationship between any two phases subject to the
 * same constraint.
 */
class SchedulingConstraint(
        val rule: SchedulingConstraintRule,
        val resourceLimit: Long,
        val phaseRequirements: Map<Phase, Long>
)

class ConcurrencyConstraintRule(
        val perInstanceType: PhaseType,
        val constrainedType: PhaseType,
        val concurrencyLimit: (perInstance: Phase) -> Long
) : SchedulingConstraintRule {

    override fun createSchedulingConstraints(executionModel: ExecutionModel): Iterable<SchedulingConstraint> {
        val constraints = mutableListOf<SchedulingConstraint>()
        val instancePhases = executionModel.rootPhase.findPhasesForType(perInstanceType)
        for (i in instancePhases) {
            val constrainedPhases = i.findPhasesForType(constrainedType)
            val limit = concurrencyLimit(i)
            constraints.add(SchedulingConstraint(this, limit, constrainedPhases.associateBy({ it }, { 1L })))
        }
        return constraints
    }

}