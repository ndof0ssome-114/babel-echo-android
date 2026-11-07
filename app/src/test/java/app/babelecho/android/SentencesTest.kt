package app.babelecho.android

import app.babelecho.android.data.Sentences
import app.babelecho.android.data.TranscriptAssembler
import app.babelecho.android.data.TranscriptSegment
import org.junit.Assert.*
import org.junit.Test

/** Mirrors desktop scripts/test-sentences.mjs so both platforms shape transcripts the same way. */
class SentencesTest {
    @Test fun splittingJoiningAndGrouping() {
        assertEquals(listOf("今天开会。", "讨论预算！", "还有"), Sentences.split("今天开会。讨论预算！还有"))
        assertEquals(listOf("「はい。」", "そうです"), Sentences.split("「はい。」そうです"))
        assertEquals(listOf("Version 3.5 shipped.", "Next we"), Sentences.split("Version 3.5 shipped. Next we"))
        assertTrue(Sentences.endsSentence("终于结束了。"))
        assertTrue(Sentences.endsSentence("他说「好的。」"))
        assertFalse(Sentences.endsSentence("然后我们"))
        assertFalse(Sentences.endsSentence("pi is 3.14"))
        assertEquals("我们下周再讨论", Sentences.join("我们下周", "再讨论"))
        assertEquals("we will meet", Sentences.join("we will", "meet"))
        assertEquals(listOf("短。也短。", "这一句比较长一些。"), Sentences.group(listOf("短。", "也短。", "这一句比较长一些。"), 6))
    }

    @Test fun overlapTrimmingMatchesDesktop() {
        assertEquals("然后继续", Sentences.trimRepeatedPrefix("我们讨论预算问题", "预算问题，然后继续"))
        assertEquals("next part", Sentences.trimRepeatedPrefix("the budget review", "Budget review. next part"))
        assertEquals("全新的话", Sentences.trimRepeatedPrefix("完全不同", "全新的话"))
        assertEquals("ab", Sentences.trimRepeatedPrefix("xab", "ab"), )
    }

    private fun run(vararg pieces: Triple<String, Pair<Long, Long>, String>): Pair<List<TranscriptSegment>, List<Int>> {
        val assembler = TranscriptAssembler(emptyList())
        var segments = emptyList<TranscriptSegment>()
        val closed = mutableListOf<Int>()
        for ((text, span, speaker) in pieces) {
            val change = assembler.add(text, span.first, span.second, speaker)
            segments = change.apply(segments)
            closed += change.closedIds
        }
        return segments to closed
    }

    @Test fun cutSentencesAreReassembledAndOnlyCompleteOnesAreReleased() {
        val assembler = TranscriptAssembler(emptyList())
        var segments = emptyList<TranscriptSegment>()
        var change = assembler.add("我们先看预算。然后下个季度的", 0, 20_000, "说话人1")
        segments = change.apply(segments)
        assertEquals(2, segments.size)
        assertEquals(listOf(0), change.closedIds)
        assertEquals(1, assembler.openSegmentId)

        change = assembler.add("计划要重新排。另外", 19_000, 39_000, "说话人1")
        segments = change.apply(segments)
        assertEquals(3, segments.size)
        assertEquals("然后下个季度的计划要重新排。", segments[1].text)
        assertTrue(segments[1].endMs in 20_001 until 39_000)
        assertEquals(listOf(1), change.closedIds)

        // A different speaker never continues the sentence.
        change = assembler.add("我同意", 39_100, 41_000, "说话人2")
        segments = change.apply(segments)
        assertEquals("另外", segments[2].text)
        assertTrue(2 in change.closedIds)
        // A long gap closes it too.
        change = assembler.add("好的", 45_000, 46_000, "说话人2")
        segments = change.apply(segments)
        assertEquals("我同意", segments[segments.size - 2].text)
        assertTrue(3 in change.closedIds)
        assertEquals(4, assembler.closeOpen())
        assertNull(assembler.closeOpen())
    }

    @Test fun longBlocksAreGroupedAndUnpunctuatedSpeechIsBounded() {
        val sentence = "这是一个用来测试分组的句子。"
        val (block, _) = run(Triple(sentence.repeat(20), 0L to 28_000L, "说话人1"))
        assertTrue(block.size >= 3)
        assertTrue(block.all { it.text.length <= 80 + sentence.length })
        assertEquals(28_000L, block.last().endMs)
        for (i in 1 until block.size) assertEquals(block[i - 1].endMs, block[i].startMs)

        val pieces = (0 until 6).map { Triple("没有标点的连续口语内容".repeat(5), it * 20_000L to (it + 1) * 20_000L, "说话人1") }
        val (unpunctuated, closed) = run(*pieces.toTypedArray())
        assertTrue(unpunctuated.size >= 2)
        assertTrue(unpunctuated.dropLast(1).all { it.id in closed })
    }

    @Test fun staleOpenSentenceIsClosed() {
        val assembler = TranscriptAssembler(emptyList())
        assembler.add("还没有说完", 0, 2_000, "说话人1")
        assertNull(assembler.closeIfStale(4_000, 2_000))
        assertEquals(0, assembler.closeIfStale(6_000, 2_000))
    }

    @Test fun assemblyContinuesAfterExistingSegmentsAndKeepsTranslations() {
        val existing = listOf(TranscriptSegment(7, 0, 1_000, "说话人1", "旧句子。", translated = "Old."))
        val assembler = TranscriptAssembler(existing)
        val change = assembler.add("新的话。", 2_000, 3_000, "说话人1")
        val merged = change.apply(existing)
        assertEquals(listOf(7, 8), merged.map { it.id })
        assertEquals("Old.", merged[0].translated)
    }
}
