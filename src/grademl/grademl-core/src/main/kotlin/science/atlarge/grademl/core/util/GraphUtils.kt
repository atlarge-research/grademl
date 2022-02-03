package science.atlarge.grademl.core.util

fun <T> computeGraphClosure(initialNodes: Iterable<T>, expandNode: (T) -> Iterable<T>): Set<T> {
    val seen = initialNodes.toMutableSet()
    val toVisit = initialNodes.toMutableList()
    while (toVisit.isNotEmpty()) {
        val current = toVisit.removeLast()
        for (next in expandNode(current)) {
            if (next !in seen) {
                seen.add(next)
                toVisit.add(next)
            }
        }
    }
    return seen
}