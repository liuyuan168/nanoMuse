package io.github.nanomuse.wakeword

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * nanoMuse: the packaged wake-word assets move together. Every pinyin token
 * in keywords.txt must exist in tokens.txt — one typo and the spotter either
 * fails to load or silently never matches the phrase.
 */
class KeywordsFileTest {

    private val dir: File by lazy {
        listOf(
            "src/main/assets/nanomuse/kws",
            "app/src/main/assets/nanomuse/kws",
        ).map(::File).first { it.exists() }
    }

    private val tokens: Set<String> by lazy {
        File(dir, "tokens.txt").readLines()
            .map { it.trim().split(" ").first() }
            .filter { it.isNotEmpty() }
            .toSet()
    }

    private val keywords: List<Pair<String, String>> by lazy {
        File(dir, "keywords.txt").readLines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { line ->
                val split = line.split("@")
                assertTrue("keywords.txt line must be '<tokens> @<phrase>': $line", split.size == 2)
                split[0].trim() to split[1].trim()
            }
    }

    @Test fun `at least one wake phrase is packaged`() {
        assertTrue("keywords.txt is empty", keywords.isNotEmpty())
    }

    @Test fun `every wake phrase is non-blank and has tokens`() {
        for ((tokensLine, phrase) in keywords) {
            assertTrue("phrase must not be blank", phrase.isNotBlank())
            assertTrue("phrase $phrase must have at least 2 pinyin tokens", tokensLine.split(" ").size >= 2)
        }
    }

    @Test fun `every pinyin token exists in tokens txt`() {
        for ((tokensLine, phrase) in keywords) {
            for (token in tokensLine.split(" ")) {
                assertTrue("token '$token' (phrase $phrase) is not in tokens.txt", token in tokens)
            }
        }
    }
}
