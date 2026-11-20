package com.threadlog.core.parser

import com.threadlog.core.model.Tag
import com.threadlog.core.model.contract.LeafDisambiguator
import java.util.Locale

/**
 * Computes minimal leaf-first display labels for hierarchical tags.
 *
 * Tags with a unique leaf name (case-insensitive) display as their bare [Tag.leafName] (e.g.
 * `"Lex"`). Colliding leaves incrementally append the minimal number of ancestor path segments
 * in parentheses, growing outward from the immediate parent (`"Alex (work)"`, `"Alex (a/x)"`)
 * until every label in the collision group is case-insensitively unique.
 */
class DefaultLeafDisambiguator : LeafDisambiguator {

    override fun computeDisplayLabels(allActiveTags: List<Tag>): Map<String, String> {
        if (allActiveTags.isEmpty()) return emptyMap()

        val labels = LinkedHashMap<String, String>(allActiveTags.size)
        val byLeafLower = allActiveTags.groupBy { it.leafName.lowercase(Locale.ROOT) }

        for ((_, group) in byLeafLower) {
            if (group.size == 1) {
                val only = group.first()
                labels[only.id] = only.leafName
                continue
            }

            val ancestorsById = group.associate { tag ->
                tag.id to extractAncestors(tag.fullPath)
            }
            val depthById = group.associate { tag ->
                val ancestors = ancestorsById.getValue(tag.id)
                tag.id to if (ancestors.isEmpty()) 0 else 1
            }.toMutableMap()

            while (true) {
                val currentLabels = group.associate { tag ->
                    val ancestors = ancestorsById.getValue(tag.id)
                    val depth = depthById.getValue(tag.id)
                    tag.id to formatLabel(tag.leafName, ancestors, depth)
                }

                val collisions = group.groupBy { tag ->
                    currentLabels.getValue(tag.id).lowercase(Locale.ROOT)
                }.values.filter { it.size > 1 }

                if (collisions.isEmpty()) {
                    labels.putAll(currentLabels)
                    break
                }

                var progressed = false
                for (collidingGroup in collisions) {
                    for (tag in collidingGroup) {
                        val maxDepth = ancestorsById.getValue(tag.id).size
                        val currentDepth = depthById.getValue(tag.id)
                        if (currentDepth < maxDepth) {
                            depthById[tag.id] = currentDepth + 1
                            progressed = true
                        }
                    }
                }

                if (!progressed) {
                    labels.putAll(currentLabels)
                    break
                }
            }
        }

        return labels
    }

    private fun extractAncestors(fullPath: String): List<String> {
        val segments = fullPath
            .trim()
            .trim('/')
            .split('/')
            .filter { it.isNotEmpty() }
        return if (segments.size > 1) segments.dropLast(1) else emptyList()
    }

    private fun formatLabel(leafName: String, ancestors: List<String>, depth: Int): String {
        if (depth <= 0 || ancestors.isEmpty()) return leafName
        val suffix = ancestors.takeLast(depth).joinToString("/")
        return "$leafName ($suffix)"
    }
}
