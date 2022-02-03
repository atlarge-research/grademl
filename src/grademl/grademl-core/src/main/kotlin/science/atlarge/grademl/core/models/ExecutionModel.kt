package science.atlarge.grademl.core.models

import science.atlarge.grademl.core.util.DurationNs
import science.atlarge.grademl.core.util.TimestampNs
import science.atlarge.grademl.core.util.computeGraphClosure
import java.util.*

class ExecutionModel {

    // Collection of phases
    val rootPhase: ExecutionPhase = RootExecutionPhase(this)
    private val _phases = mutableSetOf(rootPhase)
    val phases: Set<ExecutionPhase>
        get() = _phases

    // Parent-child relationships
    private val phaseParents = mutableMapOf<ExecutionPhase, ExecutionPhase>()
    private val phaseChildren = mutableMapOf<ExecutionPhase, MutableSet<ExecutionPhase>>()

    fun getParentOf(phase: ExecutionPhase): ExecutionPhase? = phaseParents[phase]
    fun getChildrenOf(phase: ExecutionPhase): Set<ExecutionPhase> = phaseChildren[phase] ?: emptySet()

    // Dataflow relationships
    private val phaseOutFlows = mutableMapOf<ExecutionPhase, MutableSet<ExecutionPhase>>()
    private val phaseInFlows = mutableMapOf<ExecutionPhase, MutableSet<ExecutionPhase>>()

    fun getOutFlowsOf(phase: ExecutionPhase): Set<ExecutionPhase> = phaseOutFlows[phase] ?: emptySet()
    fun getInFlowsOf(phase: ExecutionPhase): Set<ExecutionPhase> = phaseInFlows[phase] ?: emptySet()

    init {
        // Ensure that the root phase has a correct path
        rootPhase.recomputePath()
    }

    fun addPhase(
        name: String,
        tags: Map<String, String> = emptyMap(),
        typeTags: Set<String> = tags.keys,
        metadata: Map<String, String> = emptyMap(),
        description: String? = null,
        startTime: TimestampNs,
        endTime: TimestampNs,
        parent: ExecutionPhase = rootPhase
    ): ExecutionPhase {
        require(parent in _phases) { "Cannot add phase with parent that is not part of this ExecutionModel" }
        val phase = SubExecutionPhase(name, tags, typeTags, metadata, description, startTime, endTime, this)
        _phases.add(phase)
        phaseParents[phase] = parent
        phaseChildren.getOrPut(parent) { mutableSetOf() }.add(phase)
        phase.recomputePath()
        return phase
    }

    fun setParentOfPhase(
        phase: ExecutionPhase,
        newParent: ExecutionPhase
    ) {
        require(phase in _phases) { "Cannot set parent of phase that is not part of this ExecutionModel" }
        require(newParent in _phases) { "Cannot set parent to phase that is not part of this ExecutionModel" }
        require(phase != newParent) { "Cannot set a phase to become its own parent" }
        // Make sure newParent is not a descendant of phase
        var finger: ExecutionPhase? = newParent
        while (finger != null) {
            require(finger != phase) { "Cannot set a phase's parent to be one of its descendants" }
            finger = finger.parent
        }
        // Make sure phase does not have any dataflows, as migration of those is not currently supported
        require(phase.inFlows.isEmpty() && phase.outFlows.isEmpty()) {
            "Cannot change the parent of a phase with any incoming or outgoing dataflows"
        }
        // Perform the move
        val oldParent = phaseParents[phase]
        if (oldParent != null) phaseChildren[oldParent]!!.remove(phase)
        phaseParents[phase] = newParent
        phaseChildren.getOrPut(newParent) { mutableSetOf() }.add(phase)
        phase.recomputePath()
    }

    fun updatePhase(
        phase: ExecutionPhase,
        name: String = phase.name,
        tags: Map<String, String> = phase.tags,
        typeTags: Set<String> = phase.typeTags,
        metadata: Map<String, String> = phase.metadata,
        description: String? = phase.description,
        startTime: TimestampNs = phase.startTime,
        endTime: TimestampNs = phase.endTime
    ): ExecutionPhase {
        require(phase in _phases) { "Cannot update a phase that is not part of this ExecutionModel" }
        require(!phase.isRoot) { "Cannot update the root phase of an ExecutionModel" }
        // Create a new phase with the given parameters
        val newPhase = addPhase(name, tags, typeTags, metadata, description, startTime, endTime, phase.parent!!)
        // Swap any dataflow connections to use the new phase
        val inFlows = phaseInFlows[phase]
        if (inFlows != null) {
            for (inFlow in inFlows.orEmpty()) {
                phaseOutFlows[inFlow]!!.apply {
                    remove(phase)
                    add(newPhase)
                }
            }
            phaseInFlows[newPhase] = inFlows
            phaseInFlows.remove(phase)
        }
        val outFlows = phaseOutFlows[phase]
        if (outFlows != null) {
            for (outFlow in outFlows) {
                phaseInFlows[outFlow]!!.apply {
                    remove(phase)
                    add(newPhase)
                }
            }
            phaseOutFlows[newPhase] = outFlows
            phaseOutFlows.remove(phase)
        }
        // Swap any children to use the new phase and reset their paths
        val children = phaseChildren[phase]
        if (children != null) {
            phaseChildren[newPhase] = children
            for (child in children) {
                phaseParents[child] = newPhase
                child.recomputePath()
            }
            phaseChildren.remove(phase)
        }

        // Delete the old phase
        phaseChildren[phase.parent!!]!!.remove(phase)
        phaseParents.remove(phase)
        _phases.remove(phase)

        return newPhase
    }

    private val pathMatcher = PathMatcher(
        rootNode = rootPhase,
        namesOfNode = { phase -> listOf(phase.name, phase.identifier) },
        parentOfNode = { phase -> getParentOf(phase) },
        childrenOfNode = { phase -> getChildrenOf(phase) }
    )

    fun resolvePath(
        path: ExecutionPhasePath,
        relativeToPhase: ExecutionPhase = rootPhase
    ): PathMatchResult<ExecutionPhase> {
        require(relativeToPhase in _phases) { "Cannot resolve path relative to phase not in this ExecutionModel" }
        return pathMatcher.match(path, relativeToPhase)
    }

    internal fun addDataflowRelationship(source: ExecutionPhase, sink: ExecutionPhase) {
        require(source != sink) { "Cannot add dataflow between phase and itself" }
        require(source in _phases && sink in _phases) {
            "Cannot add dataflow to phase(s) not part of this ExecutionModel"
        }
        require(phaseParents[source] === phaseParents[sink]) {
            "Cannot add dataflow to phases with different parents"
        }
        require(!dataflowPathExists(sink, source)) { "Cannot introduce cycles in dataflow relationships" }

        phaseOutFlows.getOrPut(source) { mutableSetOf() }.add(sink)
        phaseInFlows.getOrPut(sink) { mutableSetOf() }.add(source)
    }

    private fun dataflowPathExists(source: ExecutionPhase, sink: ExecutionPhase): Boolean {
        val phasesToCheck = LinkedList<ExecutionPhase>()
        phasesToCheck.add(source)
        while (phasesToCheck.isNotEmpty()) {
            val phase = phasesToCheck.pop()
            val nextPhases = phaseOutFlows[phase] ?: continue
            if (sink in nextPhases) return true
            phasesToCheck.addAll(nextPhases)
        }
        return false
    }

}

sealed class ExecutionPhase(
    private val model: ExecutionModel
) {

    abstract val name: String
    abstract val tags: Map<String, String>
    abstract val typeTags: Set<String>
    abstract val metadata: Map<String, String>
    abstract val description: String?
    abstract val startTime: TimestampNs
    abstract val endTime: TimestampNs

    val identifier: String by lazy {
        if (tags.isEmpty()) {
            name
        } else {
            "$name[${tags.entries.sortedBy { it.key }.joinToString(separator = ", ") { "${it.key}=${it.value}" }}]"
        }
    }

    val typeIdentifier: String by lazy {
        require(tags.keys.containsAll(typeTags)) { "Type tags must be a subset of tags" }
        if (typeTags.isEmpty()) {
            name
        } else {
            val ts = tags.entries.filter { it.key in typeTags }.sortedBy { it.key }
            "$name[${ts.joinToString(separator = ", ") { "${it.key}=${it.value}" }}]"
        }
    }

    lateinit var path: ExecutionPhasePath
        private set

    var type: ExecutionPhaseType = ExecutionPhaseType(ExecutionPhasePath.ROOT)
        private set

    val duration: DurationNs
        get() = if (startTime < endTime) endTime - startTime else 0

    val isRoot: Boolean
        get() = parent == null
    val parent: ExecutionPhase?
        get() = model.getParentOf(this)
    val children: Set<ExecutionPhase>
        get() = model.getChildrenOf(this)
    val outFlows: Set<ExecutionPhase>
        get() = model.getOutFlowsOf(this)
    val inFlows: Set<ExecutionPhase>
        get() = model.getInFlowsOf(this)

    val ancestors: Set<ExecutionPhase>
        get() = computeGraphClosure(listOf(this)) { listOfNotNull(it.parent) }
    val descendants: Set<ExecutionPhase>
        get() = computeGraphClosure(listOf(this)) { it.children }

    private var hashCode: Int = 0

    fun addOutgoingDataflow(sink: ExecutionPhase) {
        model.addDataflowRelationship(this, sink)
    }

    internal fun recomputePath() {
        // Determine the (type) path of this ExecutionPhase
        path = parent?.path?.resolve(identifier) ?: ExecutionPhasePath.ROOT
        type = ExecutionPhaseType(parent?.type?.path?.resolve(typeIdentifier) ?: ExecutionPhasePath.ROOT)
        // Cache hash code
        hashCode = path.hashCode()
        // Propagate the change
        children.forEach(ExecutionPhase::recomputePath)
    }

}

private class RootExecutionPhase(
    model: ExecutionModel
) : ExecutionPhase(model) {
    override val name: String
        get() = ""
    override val tags: Map<String, String>
        get() = emptyMap()
    override val typeTags: Set<String>
        get() = emptySet()
    override val metadata: Map<String, String>
        get() = emptyMap()
    override val description: String?
        get() = null
    override val startTime: TimestampNs
        get() = children.map { it.startTime }.minOrNull() ?: 0L
    override val endTime: TimestampNs
        get() = children.map { it.endTime }.maxOrNull() ?: 0L
}

private class SubExecutionPhase(
    override val name: String,
    override val tags: Map<String, String>,
    override val typeTags: Set<String>,
    override val metadata: Map<String, String>,
    override val description: String?,
    override val startTime: TimestampNs,
    override val endTime: TimestampNs,
    model: ExecutionModel
) : ExecutionPhase(model)

@JvmInline
value class ExecutionPhaseType(val path: ExecutionPhasePath) {
    val parent: ExecutionPhaseType?
        get() = if (path.pathComponents.isNotEmpty()) {
            ExecutionPhaseType(ExecutionPhasePath(path.pathComponents.dropLast(1), path.isRelative))
        } else {
            null
        }

    override fun toString() = path.toString()
}

typealias ExecutionPhasePath = Path