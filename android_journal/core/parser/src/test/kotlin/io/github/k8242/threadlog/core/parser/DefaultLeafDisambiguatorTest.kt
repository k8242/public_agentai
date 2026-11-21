// Copyright (c) 2026 k8242
// SPDX-License-Identifier: MIT

package io.github.k8242.threadlog.core.parser

import com.google.common.truth.Truth.assertThat
import io.github.k8242.threadlog.core.model.Tag
import io.github.k8242.threadlog.core.model.TagRootCategory
import org.junit.Test

class DefaultLeafDisambiguatorTest {

    private val disambiguator = DefaultLeafDisambiguator()

    @Test
    fun computeDisplayLabels_emptyList_returnsEmptyMap() {
        assertThat(disambiguator.computeDisplayLabels(emptyList())).isEmpty()
    }

    @Test
    fun computeDisplayLabels_uniqueLeaves_returnBareLeafNames() {
        val tags = listOf(
            tag("1", "people/friends/Lex", "Lex"),
            tag("2", "projects/journal-app", "journal-app"),
            tag("3", "topics/kotlin", "kotlin"),
        )

        val result = disambiguator.computeDisplayLabels(tags)

        assertThat(result).containsExactly(
            "1", "Lex",
            "2", "journal-app",
            "3", "kotlin",
        )
    }

    @Test
    fun computeDisplayLabels_singleParentCollision_appendsImmediateParent() {
        val tags = listOf(
            tag("1", "people/work/Alex", "Alex"),
            tag("2", "people/friends/Alex", "Alex"),
            tag("3", "people/friends/Lex", "Lex"),
        )

        val result = disambiguator.computeDisplayLabels(tags)

        assertThat(result).containsExactly(
            "1", "Alex (work)",
            "2", "Alex (friends)",
            "3", "Lex",
        )
    }

    @Test
    fun computeDisplayLabels_multiAncestorCollision_growsOnlyCollidingSubset() {
        val tags = listOf(
            tag("1", "people/a/x/Alex", "Alex"),
            tag("2", "people/b/x/Alex", "Alex"),
            tag("3", "people/work/Alex", "Alex"),
        )

        val result = disambiguator.computeDisplayLabels(tags)

        assertThat(result).containsExactly(
            "1", "Alex (a/x)",
            "2", "Alex (b/x)",
            "3", "Alex (work)",
        )
    }

    @Test
    fun computeDisplayLabels_mixedDepthCollisions_handlesRootAndNestedCleanly() {
        val tags = listOf(
            tag("root", "Alex", "Alex"),
            tag("nested", "people/Alex", "Alex"),
        )

        val result = disambiguator.computeDisplayLabels(tags)

        assertThat(result).containsExactly(
            "root", "Alex",
            "nested", "Alex (people)",
        )
    }

    @Test
    fun computeDisplayLabels_shallowAndDeepSharingImmediateParent_expandsDeepOnly() {
        val tags = listOf(
            tag("shallow", "x/Alex", "Alex"),
            tag("deep", "a/x/Alex", "Alex"),
        )

        val result = disambiguator.computeDisplayLabels(tags)

        assertThat(result).containsExactly(
            "shallow", "Alex (x)",
            "deep", "Alex (a/x)",
        )
    }

    @Test
    fun computeDisplayLabels_caseInsensitiveLeafAndParentCollisions() {
        val tags = listOf(
            tag("1", "people/Work/Alex", "Alex"),
            tag("2", "projects/work/alex", "alex"),
        )

        val result = disambiguator.computeDisplayLabels(tags)

        assertThat(result).containsExactly(
            "1", "Alex (people/Work)",
            "2", "alex (projects/work)",
        )
    }

    private fun tag(id: String, fullPath: String, leafName: String): Tag {
        val category = TagRootCategory.fromPath(fullPath)
        return Tag(
            id = id,
            fullPath = fullPath,
            leafName = leafName,
            rootCategory = category,
            colorToken = category.key,
        )
    }
}
