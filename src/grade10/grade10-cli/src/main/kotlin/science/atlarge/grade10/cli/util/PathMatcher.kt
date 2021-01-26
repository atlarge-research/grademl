package science.atlarge.grade10.cli.util

import science.atlarge.grade10.model.Path
import science.atlarge.grade10.model.PathComponent

abstract class PhaseMatcherException(message: String) : Exception(message)

class InvalidPathExpressionException(
        val pathExpression: Path,
        val invalidPathComponent: PathComponent
) : PhaseMatcherException(
        "Could not parse component of path expression: $invalidPathComponent"
)

class PathNotFoundException(
        val currentPath: Path,
        val unresolvedPath: Path
) : PhaseMatcherException(
        if (unresolvedPath.isAbsolute) "Could not resolve absolute path '$unresolvedPath'"
        else "Could not resolve path '$unresolvedPath' relative to '$currentPath'"
)

class PathMatcher<T : Any>(
        private val rootNode: T,
        private val pathOfNode: (T) -> Path,
        private val namesOfNode: (T) -> Iterable<String>,
        private val parentOfNode: (T) -> T?,
        private val childrenOfNode: (T) -> Iterable<T>
) {

    fun matchExpression(pathExpression: String, currentNode: T): Set<T> {
        val path = Path.parse(pathExpression)
        validatePath(path)

        val initialNode = if (path.isAbsolute) rootNode else currentNode
        var matches = setOf(initialNode)
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
                throw PathNotFoundException(pathOfNode(initialNode),
                        Path(path.pathComponents.subList(0, index + 1), path.isRelative))
            }
        }

        return matches
    }

    fun matchExpression(pathExpression: String, currentNodes: Set<T>): Set<T> {
        val path = Path.parse(pathExpression)
        validatePath(path)

        var matches = if (path.isAbsolute) setOf(rootNode) else currentNodes
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
                return emptySet()
            }
        }

        return matches
    }

    private fun validatePath(path: Path) {
        val invalidComponent = path.pathComponents.find { component ->
            val isWildcard = component == "**"
            val containsWildcard = "**" in component
            val matchesRegex = PATH_COMPONENT_REGEX.matches(component)
            !isWildcard && (containsWildcard || !matchesRegex)
        }
        if (invalidComponent != null) {
            throw InvalidPathExpressionException(path, invalidComponent)
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
                .joinToString(separator =  """[^\[\]]*""") { Regex.escape(it) }
        val instanceRegex = when {
            parsedComp.groupValues[2].isNotEmpty() -> {
                parsedComp.groupValues[2]
                        .trimStart('[')
                        .trimEnd(']')
                        .split("*")
                        .joinToString(prefix = "\\[", postfix = "]", separator = """[^\[\]]*""") { Regex.escape(it) }
            }
            parsedComp.groupValues[1].endsWith("*") -> """\[[^\[\]]*]"""
            else -> ""
        }
        return (typeRegex + instanceRegex).toRegex()
    }

    companion object {
        private val PATH_COMPONENT_REGEX = """([^\[\]]+)(\[[^\[\]]+])?""".toRegex()
    }
}