package io.github.k8242.threadlog.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TagRootCategoryTest {

    @Test
    fun fromPath_recognizesAllFiveCategories() {
        assertThat(TagRootCategory.fromPath("people/friends/Lex")).isEqualTo(TagRootCategory.PEOPLE)
        assertThat(TagRootCategory.fromPath("topics/health/running")).isEqualTo(TagRootCategory.TOPICS)
        assertThat(TagRootCategory.fromPath("projects/journal-app")).isEqualTo(TagRootCategory.PROJECTS)
        assertThat(TagRootCategory.fromPath("auto/mood/energized")).isEqualTo(TagRootCategory.AUTO)
        assertThat(TagRootCategory.fromPath("misc/custom")).isEqualTo(TagRootCategory.OTHER)

        assertThat(TagRootCategory.PEOPLE.key).isEqualTo("people")
        assertThat(TagRootCategory.TOPICS.key).isEqualTo("topics")
        assertThat(TagRootCategory.PROJECTS.key).isEqualTo("projects")
        assertThat(TagRootCategory.AUTO.key).isEqualTo("auto")
        assertThat(TagRootCategory.OTHER.key).isEqualTo("other")
    }

    @Test
    fun fromPath_isCaseInsensitive() {
        assertThat(TagRootCategory.fromPath("PEOPLE/Alex")).isEqualTo(TagRootCategory.PEOPLE)
        assertThat(TagRootCategory.fromPath("Topics/Tech")).isEqualTo(TagRootCategory.TOPICS)
        assertThat(TagRootCategory.fromPath("PrOjEcTs/V1")).isEqualTo(TagRootCategory.PROJECTS)
        assertThat(TagRootCategory.fromPath("AUTO/Work")).isEqualTo(TagRootCategory.AUTO)
        assertThat(TagRootCategory.fromPath("OTHER/Misc")).isEqualTo(TagRootCategory.OTHER)
    }

    @Test
    fun fromPath_handlesSingleSegmentPaths() {
        assertThat(TagRootCategory.fromPath("people")).isEqualTo(TagRootCategory.PEOPLE)
        assertThat(TagRootCategory.fromPath("topics")).isEqualTo(TagRootCategory.TOPICS)
        assertThat(TagRootCategory.fromPath("projects")).isEqualTo(TagRootCategory.PROJECTS)
        assertThat(TagRootCategory.fromPath("auto")).isEqualTo(TagRootCategory.AUTO)
        assertThat(TagRootCategory.fromPath("other")).isEqualTo(TagRootCategory.OTHER)
        assertThat(TagRootCategory.fromPath("unknown")).isEqualTo(TagRootCategory.OTHER)
    }

    @Test
    fun fromPath_handlesDeeplyNestedPaths() {
        assertThat(TagRootCategory.fromPath("people/friends/college/east/Lex"))
            .isEqualTo(TagRootCategory.PEOPLE)
        assertThat(TagRootCategory.fromPath("projects/2026/q4/threadlog/v1"))
            .isEqualTo(TagRootCategory.PROJECTS)
    }

    @Test
    fun fromPath_handlesEmptyBlankAndUnknownPaths() {
        assertThat(TagRootCategory.fromPath("")).isEqualTo(TagRootCategory.OTHER)
        assertThat(TagRootCategory.fromPath("   ")).isEqualTo(TagRootCategory.OTHER)
        assertThat(TagRootCategory.fromPath("friends/Lex")).isEqualTo(TagRootCategory.OTHER)
    }

    @Test
    fun fromPath_handlesLeadingAndTrailingSlashes() {
        assertThat(TagRootCategory.fromPath("/people/friends/Lex")).isEqualTo(TagRootCategory.PEOPLE)
        assertThat(TagRootCategory.fromPath("///topics/health/")).isEqualTo(TagRootCategory.TOPICS)
        assertThat(TagRootCategory.fromPath("projects/")).isEqualTo(TagRootCategory.PROJECTS)
        assertThat(TagRootCategory.fromPath(" /auto/x/ ")).isEqualTo(TagRootCategory.AUTO)
        assertThat(TagRootCategory.fromPath("/")).isEqualTo(TagRootCategory.OTHER)
        assertThat(TagRootCategory.fromPath("///")).isEqualTo(TagRootCategory.OTHER)
    }
}
