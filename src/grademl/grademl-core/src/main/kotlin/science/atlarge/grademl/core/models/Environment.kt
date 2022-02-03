package science.atlarge.grademl.core.models

class Environment {

    private val _machines = mutableListOf<Machine>()
    private val machinesById = mutableMapOf<String, Machine>()

    val machines: List<Machine>
        get() = _machines

    fun machineForId(id: String): Machine? = machinesById[id]

    fun addMachine(machine: Machine) {
        require(machine.canonicalId !in machinesById && machine.alternativeIds.none { it in machinesById }) {
            "Cannot add machine with an ID already in use by a different machine"
        }
        // Keep machine list sorted by canonical ID
        val positionToInsert = _machines.binarySearch(machine, compareBy(Machine::canonicalId)).let { idx ->
            if (idx < 0) idx.inv() else idx
        }
        _machines.add(positionToInsert, machine)
        machinesById[machine.canonicalId] = machine
        for (altId in machine.alternativeIds) machinesById[altId] = machine
    }

}

class Machine(val canonicalId: String, val alternativeIds: Set<String>)