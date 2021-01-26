package science.atlarge.grade10.simulation

import com.esotericsoftware.kryo.util.IdentityObjectIntMap
import science.atlarge.grade10.model.execution.Phase

typealias PhaseId = Int

typealias ScheduleTaskId = Int
typealias ScheduleTaskIdArray = IntArray
typealias ScheduleTaskIdQueue = IntQueue

typealias PhaseIdMap = IdentityObjectIntMap<Phase>