package science.atlarge.grademl.input.resource_monitor.util

fun concatenateArrays(arrays: List<LongArray>, separator: LongArray = longArrayOf()): LongArray {
    // Skip if no arrays are provided
    if (arrays.isEmpty()) {
        return longArrayOf()
    }
    // Allocate an array of the correct dimensions
    val totalSize = arrays.sumOf { it.size } + separator.size * (arrays.size - 1)
    val outArray = LongArray(totalSize)
    // Iterate over arrays and copy their contents to the output array, inserting separators as appropriate
    var offset = 0
    for (i in arrays.indices) {
        // Insert separator, except before the first array
        if (i != 0) {
            separator.copyInto(outArray, destinationOffset = offset)
            offset += separator.size
        }
        // Copy next array
        arrays[i].copyInto(outArray, destinationOffset = offset)
        offset += arrays[i].size
    }

    return outArray
}

fun concatenateArrays(arrays: List<DoubleArray>, separator: DoubleArray = doubleArrayOf()): DoubleArray {
    // Skip if no arrays are provided
    if (arrays.isEmpty()) {
        return doubleArrayOf()
    }
    // Allocate an array of the correct dimensions
    val totalSize = arrays.sumOf { it.size } + separator.size * (arrays.size - 1)
    val outArray = DoubleArray(totalSize)
    // Iterate over arrays and copy their contents to the output array, inserting separators as appropriate
    var offset = 0
    for (i in arrays.indices) {
        // Insert separator, except before the first array
        if (i != 0) {
            separator.copyInto(outArray, destinationOffset = offset)
            offset += separator.size
        }
        // Copy next array
        arrays[i].copyInto(outArray, destinationOffset = offset)
        offset += arrays[i].size
    }

    return outArray
}

fun concatenateOptionalArrays(
    arrays: List<DoubleArray?>,
    expectedArraySizes: List<Int>,
    separator: DoubleArray = doubleArrayOf()
): DoubleArray? {
    require(arrays.indices.all { i -> arrays[i] == null || arrays[i]!!.size == expectedArraySizes[i] }) {
        "Given arrays must match the expected array sizes"
    }
    // Skip if no arrays are provided
    if (arrays.isEmpty()) {
        return doubleArrayOf()
    }
    if (arrays.all { it == null }) {
        return null
    }
    // Allocate an array of the correct dimensions
    val totalSize = expectedArraySizes.sum() + separator.size * (arrays.size - 1)
    val outArray = DoubleArray(totalSize)
    // Iterate over arrays and copy their contents to the output array, replacing null arrays with zero values,
    // and inserting separators as appropriate
    var offset = 0
    for (i in arrays.indices) {
        // Insert separator, except before the first array
        if (i != 0) {
            separator.copyInto(outArray, destinationOffset = offset)
            offset += separator.size
        }
        // Copy next array, if it exists
        if (arrays[i] != null) {
            arrays[i]!!.copyInto(outArray, destinationOffset = offset)
            offset += arrays[i]!!.size
        } else {
            // Otherwise, insert zero values
            outArray.fill(0.0, offset, offset + expectedArraySizes[i])
            offset += expectedArraySizes[i]
        }
    }

    return outArray
}