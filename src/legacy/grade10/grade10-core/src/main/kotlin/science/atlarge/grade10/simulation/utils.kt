package science.atlarge.grade10.simulation

class IntQueue(initialSize: Int = 15) {

    private var arr: IntArray
    private var arrSize: Int
    private var arrMask: Int

    private var index: Int = 0
    var size: Int = 0
        private set

    init {
        require(initialSize > 0)

        var s = initialSize - 1
        s = s.or(s.shr(1))
        s = s.or(s.shr(2))
        s = s.or(s.shr(4))
        s = s.or(s.shr(8))
        s = s.or(s.shr(16))

        arrSize = s + 1
        arrMask = s
        arr = IntArray(arrSize)
    }

    fun push(v: Int) {
        val loc = (index + size).and(arrMask)
        arr[loc] = v
        size++
        if (size == arrSize) {
            val newArr = IntArray(arrSize * 2)
            System.arraycopy(arr, index, newArr, 0, size - index)
            System.arraycopy(arr, 0, newArr, size - index, index)
            arr = newArr
            arrSize *= 2
            arrMask = arrSize - 1
            index = 0
        }
    }

    fun pop(): Int {
        require(isNotEmpty())
        val v = arr[index]
        index = (index + 1).and(arrMask)
        size--
        return v
    }

    fun peek(): Int {
        require(isNotEmpty())
        return arr[index]
    }

    fun isEmpty() = size == 0
    fun isNotEmpty() = !isEmpty()

}

class PendingTaskQueue(
        initialSize: Int = 15
) {

    private var keys: ScheduleTaskIdArray
    private var values: DoubleArray
    private var arrSize: Int

    var size: Int = 0
        private set

    init {
        require(initialSize > 0)

        var s = initialSize - 1
        s = s.or(s.shr(1))
        s = s.or(s.shr(2))
        s = s.or(s.shr(4))
        s = s.or(s.shr(8))
        s = s.or(s.shr(16))

        arrSize = s + 1
        keys = ScheduleTaskIdArray(arrSize)
        values = DoubleArray(arrSize)
    }

    fun insert(key: ScheduleTaskId, value: Double) {
        if (size == arrSize) resize()

        // Push parents down until the right place to insert is found
        var loc = size
        while (loc != 0) {
            val parentLoc = (loc - 1) / 2
            if (value < values[parentLoc] || (value == values[parentLoc] && key < keys[parentLoc])) {
                keys[loc] = keys[parentLoc]
                values[loc] = values[parentLoc]
                loc = parentLoc
            } else {
                break
            }
        }

        // Insert key-value pair
        keys[loc] = key
        values[loc] = value
        size++
    }

    fun pop() {
        require(size > 0)
        if (size == 1) {
            size = 0
            return
        }

        // Remove the last element in the heap
        val key = keys[size - 1]
        val value = values[size - 1]
        size--

        // Pull items up until the right place to insert is found
        var loc = 0
        while (true) {
            val leftChild = loc * 2 + 1
            val rightChild = loc * 2 + 2

            if (leftChild >= size) break

            val smallestChild = if (rightChild >= size) {
                leftChild
            } else when {
                values[leftChild] < values[rightChild] -> leftChild
                values[leftChild] > values[rightChild] -> rightChild
                keys[leftChild] < keys[rightChild] -> leftChild
                else -> rightChild
            }

            if (value > values[smallestChild] || (value == values[smallestChild] && key > keys[smallestChild])) {
                values[loc] = values[smallestChild]
                keys[loc] = keys[smallestChild]
                loc = smallestChild
                continue
            }

            break
        }

        // Insert the element
        keys[loc] = key
        values[loc] = value
    }

    fun peekKey(): ScheduleTaskId {
        return keys[0]
    }

    fun peekValue(): Double {
        return values[0]
    }

    fun isEmpty() = size == 0
    fun isNotEmpty() = !isEmpty()

    private fun resize() {
        arrSize *= 2
        keys = keys.copyOf(arrSize)
        values = values.copyOf(arrSize)
    }

}