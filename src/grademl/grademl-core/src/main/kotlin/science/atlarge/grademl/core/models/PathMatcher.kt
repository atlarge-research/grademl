package science.atlarge.grademl.core.models

class PathMatcher<T : Any>(
    private val rootNode: T,
    private val namesOfNode: (T) -> Iterable<String>,
    private val parentOfNode: (T) -> T?,
    private val childrenOfNode: (T) -> Iterable<T>
) {

    fun match(pathExpression: String, currentNode: T): PathMatchResult<T> {
        return match(Path.parse(pathExpression), listOf(currentNode))
    }

    fun match(pathExpression: String, currentNodes: Iterable<T>): PathMatchResult<T> {
        return match(Path.parse(pathExpression), currentNodes)
    }

    fun match(path: Path, currentNode: T): PathMatchResult<T> {
        return match(path, listOf(currentNode))
    }

    fun match(path: Path, currentNodes: Iterable<T>): PathMatchResult<T> {
        validatePath(path)?.also { return it }

        var matches = if (path.isAbsolute) setOf(rootNode) else currentNodes.toSet()
        path.pathComponents.forEachIndexed { index, component ->
            matches = when (component) {
                "." -> matches
                ".." -> matches.mapNotNull { parentOfNode(it) }.toSet()
                "**" -> matches.flatMap(::collectNodesWithoutRoot).toSet()
                else -> {
                    val componentRegex = pathComponentAsRegex(component)
                    matches.flatMap(childrenOfNode).filter {
                        namesOfNode(it).any { name -> componentRegex.matches(name) }
                    }.toSet()
                }
            }
            if (matches.isEmpty()) {
                return PathNotFound(Path(path.pathComponents.subList(0, index + 1), path.isRelative))
            }
        }

        return PathMatches(matches)
    }

    private fun validatePath(path: Path): PathMatchResult<T>? {
        val invalidComponent = path.pathComponents.find { component ->
            val isWildcard = component == "**"
            val containsWildcard = "**" in component
            val matchesRegex = PATH_COMPONENT_REGEX.matches(component)
            !isWildcard && (containsWildcard || !matchesRegex)
        }
        return if (invalidComponent != null) {
            InvalidPathExpression(path, invalidComponent)
        } else {
            null
        }
    }

    private fun collectNodesWithoutRoot(currentNode: T): Set<T> {
        val collected = mutableSetOf<T>()
        for (child in childrenOfNode(currentNode)) {
            collected.add(child)
            collected.addAll(collectNodesWithoutRoot(child))
        }
        return collected
    }

    private fun pathComponentAsRegex(component: PathComponent): Regex {
        val parsedComp = PATH_COMPONENT_REGEX.matchEntire(component)!!
        val typeRegex = parsedComp.groupValues[1]
            .split("*")
            .joinToString(separator = """[^\[\]]*""") { Regex.escape(it) }
        val instanceRegex = when {
            parsedComp.groupValues[2].isNotEmpty() -> {
                parsedComp.groupValues[2]
                    .trimStart('[')
                    .trimEnd(']')
                    .split("*")
                    .joinToString(prefix = "\\[", postfix = "]", separator = """[^\[\]]*""") { Regex.escape(it) }
            }
            parsedComp.groupValues[1].endsWith("*") -> """(\[[^\[\]]*])?"""
            else -> ""
        }
        return (typeRegex + instanceRegex).toRegex()
    }

    companion object {
        private val PATH_COMPONENT_REGEX =
            "^([^${Path.SEPARATOR}:\\[\\]]+)(\\[[^${Path.SEPARATOR}:\\[\\]]+])?$".toRegex()
    }
}

sealed class PathMatchResult<out T : Any>

data class PathMatches<T : Any>(
    val matches: Iterable<T>
) : PathMatchResult<T>()

abstract class PathMatchException : PathMatchResult<Nothing>() {
    abstract val message: String
}

data class InvalidPathExpression(
    val pathExpression: Path,
    val invalidPathComponent: PathComponent
) : PathMatchException() {
    override val message = "Could not parse component of path expression: \"$invalidPathComponent\"."
}

data class PathNotFound(
    val unresolvedPath: Path
) : PathMatchException() {
    override val message = "Found no matches for path: \"$unresolvedPath\"."
}