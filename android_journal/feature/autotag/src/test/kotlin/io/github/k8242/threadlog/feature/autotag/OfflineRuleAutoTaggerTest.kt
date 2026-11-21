package io.github.k8242.threadlog.feature.autotag

import com.google.common.truth.Truth.assertThat
import io.github.k8242.threadlog.core.model.AutoTagRule
import io.github.k8242.threadlog.core.model.RuleMatchType
import io.github.k8242.threadlog.core.model.TagRootCategory
import kotlinx.coroutines.test.runTest
import org.junit.Test

class OfflineRuleAutoTaggerTest {

    private val tagger = OfflineRuleAutoTagger()

    @Test
    fun providerId_isOfflineRuleV1() {
        assertThat(tagger.providerId).isEqualTo("offline_rule_v1")
    }

    @Test
    fun deletedEntry_returnsEmptyList() = runTest {
        val entry = testEntry(
            bodyPlain = "Sprint planning with Lexi on github.com",
            deletedAtMs = 1_763_655_000_000L,
        )
        val bookmarks = listOf(testBookmark(domain = "github.com"))
        val tags = listOf(testTag("t1", "people/friends/Lex", aliases = listOf("Lexi")))
        val rules = listOf(
            AutoTagRule("r1", "sprint", RuleMatchType.KEYWORD, "projects/sprint", 0.90f, true),
            AutoTagRule("r2", "github.com", RuleMatchType.DOMAIN, "projects/code", 0.95f, true),
        )

        val result = tagger.suggestTags(entry, bookmarks, tags, rules)

        assertThat(result).isEmpty()
    }

    @Test
    fun keywordRule_matchesWholeWordCaseInsensitively_andRejectsPartialWord() = runTest {
        val rules = listOf(
            AutoTagRule("r1", "sprint", RuleMatchType.KEYWORD, "topics/agile", 0.88f, true),
        )

        val matchedEntry = testEntry(bodyPlain = "Wrapped up the SPRINT retrospective today!")
        val partialEntry = testEntry(bodyPlain = "Went sprinting and presprint warmups.")

        val matchedSuggestions = tagger.suggestTags(matchedEntry, emptyList(), emptyList(), rules)
        val partialSuggestions = tagger.suggestTags(partialEntry, emptyList(), emptyList(), rules)

        assertThat(matchedSuggestions).hasSize(1)
        assertThat(matchedSuggestions.first().tagPath).isEqualTo("topics/agile")
        assertThat(matchedSuggestions.first().confidence).isWithin(0.001f).of(0.88f)
        assertThat(matchedSuggestions.first().reason).isEqualTo("Matched keyword 'sprint'")
        assertThat(partialSuggestions).isEmpty()
    }

    @Test
    fun keywordRule_matchesBookmarkTitleAndNote() = runTest {
        val entry = testEntry(bodyPlain = "Saved this link for later")
        val bookmarks = listOf(
            testBookmark(
                domain = "example.com",
                title = "Quarterly Sprint Guide",
                note = "Great notes on retros",
            ),
        )
        val rules = listOf(
            AutoTagRule("r1", "sprint", RuleMatchType.KEYWORD, "topics/planning", 0.85f, true),
            AutoTagRule("r2", "retros", RuleMatchType.KEYWORD, "topics/retrospective", 0.82f, true),
        )

        val suggestions = tagger.suggestTags(entry, bookmarks, emptyList(), rules)

        assertThat(suggestions.map { it.tagPath })
            .containsExactly("topics/planning", "topics/retrospective")
            .inOrder()
    }

    @Test
    fun domainRule_matchesExactSubdomainAndWwwNormalization_andRejectsLookalike() = runTest {
        val rules = listOf(
            AutoTagRule("r1", "www.GitHub.com", RuleMatchType.DOMAIN, "projects/code", 0.90f, true),
        )
        val entry = testEntry(bodyPlain = "Checking links")

        val exactMatch = tagger.suggestTags(
            entry,
            listOf(testBookmark(domain = "github.com")),
            emptyList(),
            rules,
        )
        val wwwMatch = tagger.suggestTags(
            entry,
            listOf(testBookmark(domain = "WWW.GITHUB.COM")),
            emptyList(),
            rules,
        )
        val subdomainMatch = tagger.suggestTags(
            entry,
            listOf(testBookmark(domain = "docs.github.com")),
            emptyList(),
            rules,
        )
        val lookalikeNoMatch = tagger.suggestTags(
            entry,
            listOf(testBookmark(domain = "notgithub.com")),
            emptyList(),
            rules,
        )

        assertThat(exactMatch).hasSize(1)
        assertThat(exactMatch.first().reason).isEqualTo("Matched domain 'github.com'")
        assertThat(wwwMatch).hasSize(1)
        assertThat(subdomainMatch).hasSize(1)
        assertThat(lookalikeNoMatch).isEmpty()
    }

    @Test
    fun regexRule_matchesCaseInsensitiveAndHandlesInvalidPatternGracefully() = runTest {
        val entry = testEntry(bodyPlain = "Fixed ticket PROJ-421 before lunch")
        val rules = listOf(
            AutoTagRule("bad", "[unclosed(group", RuleMatchType.REGEX, "projects/broken", 0.99f, true),
            AutoTagRule("good", "proj-\\d+", RuleMatchType.REGEX, "projects/tracker", 0.92f, true),
        )

        val suggestions = tagger.suggestTags(entry, emptyList(), emptyList(), rules)

        assertThat(suggestions).hasSize(1)
        assertThat(suggestions.first().tagPath).isEqualTo("projects/tracker")
        assertThat(suggestions.first().confidence).isWithin(0.001f).of(0.92f)
        assertThat(suggestions.first().reason).contains("Matched regex")
    }

    @Test
    fun disabledRules_areIgnored() = runTest {
        val entry = testEntry(bodyPlain = "Sprint planning")
        val rules = listOf(
            AutoTagRule("r1", "sprint", RuleMatchType.KEYWORD, "topics/agile", 0.95f, enabled = false),
        )

        val suggestions = tagger.suggestTags(entry, emptyList(), emptyList(), rules)

        assertThat(suggestions).isEmpty()
    }

    @Test
    fun existingTags_matchLeafNameAndAliases_andSkipShortOrAutoCategory() = runTest {
        val entry = testEntry(bodyPlain = "Met Lexi and Sam to talk about a plan with Alex")
        val tags = listOf(
            testTag("t1", "people/friends/Lex", aliases = listOf("Lexi")),
            testTag("t2", "people/work/Sam"),
            testTag("t3", "topics/a", aliases = listOf("x")), // < 2 chars -> ignored
            testTag(
                id = "t4",
                fullPath = "auto/plan",
                aliases = listOf("plan"),
                rootCategory = TagRootCategory.AUTO,
            ), // AUTO category -> skipped
        )

        val suggestions = tagger.suggestTags(entry, emptyList(), tags, emptyList())

        assertThat(suggestions.map { it.tagPath })
            .containsExactly("people/friends/Lex", "people/work/Sam")
            .inOrder()
        assertThat(suggestions[0].confidence)
            .isWithin(0.001f)
            .of(OfflineRuleAutoTagger.ALIAS_MATCH_CONFIDENCE)
        assertThat(suggestions[0].reason).isEqualTo("Matched alias 'Lexi'")
        assertThat(suggestions[1].confidence)
            .isWithin(0.001f)
            .of(OfflineRuleAutoTagger.LEAF_MATCH_CONFIDENCE)
        assertThat(suggestions[1].reason).isEqualTo("Matched tag 'Sam'")
    }

    @Test
    fun existingTagLeafName_doesNotMatchPartialWord() = runTest {
        val entry = testEntry(bodyPlain = "Spoke with Alex about the roadmap")
        val tags = listOf(
            testTag("t1", "people/friends/Lex"),
        )

        val suggestions = tagger.suggestTags(entry, emptyList(), tags, emptyList())

        assertThat(suggestions).isEmpty()
    }

    @Test
    fun normalizesTagPaths_deduplicatesKeepingHighestConfidence_andSortsDeterministically() = runTest {
        val entry = testEntry(bodyPlain = "Sprint kickoff with Lexi and Lex on alpha and beta")
        val tags = listOf(
            testTag("t1", "/people/friends/Lex/", aliases = listOf("Lexi")),
        )
        val rules = listOf(
            AutoTagRule("r1", "sprint", RuleMatchType.KEYWORD, "/people/friends/lex/", 0.93f, true),
            AutoTagRule("r2", "beta", RuleMatchType.KEYWORD, "topics/beta", 0.85f, true),
            AutoTagRule("r3", "alpha", RuleMatchType.KEYWORD, "topics/alpha", 0.85f, true),
        )

        val suggestions = tagger.suggestTags(entry, emptyList(), tags, rules)

        assertThat(suggestions).hasSize(3)
        assertThat(suggestions[0].tagPath).isEqualTo("people/friends/lex")
        assertThat(suggestions[0].confidence).isWithin(0.001f).of(0.93f)
        // Same confidence (0.85f) sorted by tagPath ASC
        assertThat(suggestions[1].tagPath).isEqualTo("topics/alpha")
        assertThat(suggestions[2].tagPath).isEqualTo("topics/beta")
    }
}
