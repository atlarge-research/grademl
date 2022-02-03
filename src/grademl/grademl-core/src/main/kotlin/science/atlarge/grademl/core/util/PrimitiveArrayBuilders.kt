package science.atlarge.grademl.core.util

class LongArrayBuilder(initialSize: Int = 16) {

    var size = 0
        private set
    private var maxSize: Int = initialSize
    private var array: LongArray

    init {
        require(initialSize >= 0) { "Array size must be non-negative" }
        array = LongArray(maxSize)
    }

    fun append(value: Long) {
        if (size >= maxSize)
            extendArray()
        array[size] = value
        size++
    }

    fun last(): Long {
        require(size > 0) { "Cannot read last element of empty array" }
        return array[size - 1]
    }

    fun replaceLast(value: Long) {
        require(size > 0) { "Cannot replace last element of empty array" }
        array[size - 1] = value
    }

    fun dropLast(count: Int = 1) {
        require(count >= 0) { "Cannot drop a negative number of elements" }
        size = (size - count).coerceAtLeast(0)
    }

    private fun extendArray() {
        maxSize += (maxSize / 2).coerceAtLeast(1)
        array = array.copyOf(maxSize)
    }

    fun toArray(): LongArray = array.copyOfRange(0, size)

}

class DoubleArrayBuilder(initialSize: Int = 16) {

    var size = 0
        private set
    private var maxSize: Int = initialSize
    private var array: DoubleArray

    init {
        require(initialSize >= 0) { "Array size must be non-negative" }
        array = DoubleArray(maxSize)
    }

    fun append(value: Double) {
        if (size >= maxSize)
            extendArray()
        array[size] = value
        size++
    }

    fun last(): Double {
        require(size > 0) { "Cannot read last element of empty array" }
        return array[size - 1]
    }

    fun replaceLast(value: Double) {
        require(size > 0) { "Cannot replace last element of empty array" }
        array[size - 1] = value
    }

    fun dropLast(count: Int = 1) {
        require(count >= 0) { "Cannot drop a negative number of elements" }
        size = (size - count).coerceAtLeast(0)
    }

    private fun extendArray() {
        maxSize += (maxSize / 2).coerceAtLeast(1)
        array = array.copyOf(maxSize)
    }

    fun toArray(): DoubleArray = array.copyOfRange(0, size)

}
