package com.itsm.p5

import com.itsm.intelligence.TextSimilarity
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TextSimilarityTest {
    @Test
    fun `tokens drop punctuation, stopwords and short words`() {
        assertEquals(setOf("vpn", "keeps", "dropping", "office"), TextSimilarity.tokens("The VPN keeps dropping, in the Office!"))
    }

    @Test
    fun `jaccard is the overlap over the union`() {
        assertEquals(0.4, TextSimilarity.jaccard(setOf("a1", "b2", "c3"), setOf("b2", "c3", "d4", "e5")), 1e-9)
        assertEquals(0.0, TextSimilarity.jaccard(emptySet(), setOf("x")))
    }

    @Test
    fun `similar titles score high and unrelated ones low`() {
        fun score(t1: String, t2: String) = TextSimilarity.score(TextSimilarity.tokens(t1), emptySet(), TextSimilarity.tokens(t2), emptySet())
        assertTrue(score("VPN cannot connect from home", "Cannot connect to VPN from home") > 0.6)
        assertTrue(score("VPN cannot connect from home", "Printer out of toner") < 0.1)
    }

    @Test
    fun `title counts more than description`() {
        val t = TextSimilarity.tokens("vpn timeout")
        val d1 = TextSimilarity.tokens("alpha bravo charlie")
        val d2 = TextSimilarity.tokens("delta echo foxtrot")
        val sameTitle = TextSimilarity.score(t, d1, t, d2)
        val sameDesc = TextSimilarity.score(TextSimilarity.tokens("one two"), d1, TextSimilarity.tokens("three four"), d1)
        assertTrue(sameTitle > sameDesc)
    }
}
