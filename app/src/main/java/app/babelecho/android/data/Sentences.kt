package app.babelecho.android.data

/**
 * Sentence boundaries and transcript assembly, ported from the desktop `lib/sentences.mjs` and
 * `Meeting.addText()` / `trimRepeatedPrefix()` in `lib/meeting.mjs`.
 *
 * Recognition requests end wherever the audio was cut, not where a sentence ends. An unfinished
 * sentence therefore stays open and absorbs the start of the next piece when it follows quickly
 * from the same speaker, so one segment reads (and is translated) as one complete thought.
 */
object Sentences {
    // Terminal punctuation plus closing quotes/brackets. A Latin period ends a sentence only
    // before whitespace or the end, which keeps 3.5 and v1.2 intact.
    private val end = Regex("(?:[。！？!?…]+|\\.(?=\\s|$))[\"'”’」』）)\\]]*")
    private val latinEnd = Regex("[\\p{IsLatin}\\p{N}.,;:!?'\"”’)\\]]$")
    private val latinStart = Regex("^[\\p{IsLatin}\\p{N}(\"“‘\\[]")

    /** Split into sentences; the last element may be unfinished. */
    fun split(text: String): List<String> {
        val value = text.trim()
        if (value.isEmpty()) return emptyList()
        val out = mutableListOf<String>()
        var from = 0
        for (match in end.findAll(value)) {
            val to = match.range.last + 1
            value.substring(from, to).trim().takeIf { it.isNotEmpty() }?.let(out::add)
            from = to
        }
        value.substring(from).trim().takeIf { it.isNotEmpty() }?.let(out::add)
        return out
    }

    /** Whether text finishes on terminal punctuation. */
    fun endsSentence(text: String?): Boolean {
        val value = text?.trim().orEmpty()
        if (value.isEmpty()) return false
        val last = end.findAll(value).lastOrNull() ?: return false
        return last.range.last + 1 == value.length
    }

    /** Concatenate two pieces, adding a space only between Latin words. */
    fun join(left: String, right: String): String {
        val a = left.trimEnd()
        val b = right.trimStart()
        if (a.isEmpty()) return b
        if (b.isEmpty()) return a
        return a + (if (latinEnd.containsMatchIn(a) && latinStart.containsMatchIn(b)) " " else "") + b
    }

    /** Group sentences into pieces of about [target] characters; a sentence is never split. */
    fun group(sentences: List<String>, target: Int): List<String> {
        val groups = mutableListOf<String>()
        var current = ""
        for (sentence in sentences) {
            if (current.isNotEmpty() && current.length + sentence.length > target) {
                groups += current
                current = sentence
            } else current = join(current, sentence)
        }
        if (current.isNotEmpty()) groups += current
        return groups
    }

    /** Remove text recognised twice from audio carried across an overlap (desktop trimRepeatedPrefix). */
    fun trimRepeatedPrefix(previous: String?, current: String?): String {
        val text = current.orEmpty()
        if (previous.isNullOrEmpty() || text.isEmpty()) return text.trim()
        data class Unit(val char: String, val end: Int)
        fun units(value: String): List<Unit> {
            val out = mutableListOf<Unit>()
            var i = 0
            while (i < value.length) {
                val cp = value.codePointAt(i)
                val next = i + Character.charCount(cp)
                if (Character.isLetterOrDigit(cp)) out += Unit(String(Character.toChars(cp)).lowercase(), next)
                i = next
            }
            return out
        }
        val left = units(previous).takeLast(80)
        val right = units(text).take(80)
        for (count in minOf(left.size, right.size) downTo 3) {
            if (left.takeLast(count).joinToString("") { it.char } != right.take(count).joinToString("") { it.char }) continue
            return text.substring(right[count - 1].end).replace(Regex("^[\\s\\p{P}\\p{S}]+"), "").trim()
        }
        return text.trim()
    }
}

/**
 * Builds transcript segments from recognition results the way the desktop meeting does.
 * Works on a snapshot of the segments plus edits, so callers can merge the result into the
 * stored meeting under its lock without overwriting translations written in the meantime.
 */
class TranscriptAssembler(existing: List<TranscriptSegment>) {
    data class Change(val updated: List<TranscriptSegment>, val added: List<TranscriptSegment>, val closedIds: List<Int>) {
        fun apply(segments: List<TranscriptSegment>): List<TranscriptSegment> {
            val byId = updated.associateBy { it.id }
            return segments.map { segment -> byId[segment.id]?.let { segment.copy(text = it.text, endMs = it.endMs) } ?: segment } + added
        }
    }

    private var last: TranscriptSegment? = existing.lastOrNull()
    private var lastOpen = false
    private var nextId = (existing.maxOfOrNull { it.id } ?: -1) + 1

    /** The trailing unfinished sentence, if any (not yet ready for translation). */
    val openSegmentId: Int? get() = if (lastOpen) last?.id else null

    fun add(text: String, startMs: Long, endMs: Long, speaker: String): Change {
        var rest = text.trim()
        if (rest.isEmpty()) return Change(emptyList(), emptyList(), emptyList())
        val total = rest.length
        val span = (endMs - startMs).coerceAtLeast(0)
        fun at(chars: Int) = startMs + span * minOf(chars, total) / total
        val updated = mutableListOf<TranscriptSegment>()
        val closed = mutableListOf<Int>()

        val previous = last
        if (previous != null && lastOpen) {
            if (previous.speaker == speaker && startMs - previous.endMs <= CONTINUE_GAP_MS) {
                val head = Sentences.split(rest).first()
                rest = rest.substring(head.length).trim()
                val merged = previous.copy(text = Sentences.join(previous.text, head),
                    endMs = maxOf(previous.endMs, if (rest.isNotEmpty()) at(total - rest.length) else endMs))
                updated += merged
                last = merged
                if (Sentences.endsSentence(head) || merged.text.length >= MAX_OPEN_CHARS) { lastOpen = false; closed += merged.id }
                if (rest.isEmpty()) return Change(updated, emptyList(), closed)
            } else {
                lastOpen = false
                closed += previous.id
            }
        }

        val sentences = Sentences.split(rest).toMutableList()
        // The unfinished tail gets its own segment so the complete sentences before it can be
        // translated now instead of waiting for the next piece.
        val tail = if (Sentences.endsSentence(sentences.last())) null else sentences.removeAt(sentences.lastIndex)
        val groups = Sentences.group(sentences, SEGMENT_TARGET_CHARS).toMutableList()
        if (tail != null) groups += tail
        var offset = total - rest.length
        val added = groups.mapIndexed { i, group ->
            val final = i == groups.lastIndex
            val from = offset
            offset += group.length
            val segment = TranscriptSegment(nextId++, at(from), if (final) endMs else at(offset), speaker, group)
            val open = final && tail != null && group.length < MAX_OPEN_CHARS
            if (!open) closed += segment.id
            if (final) { last = segment; lastOpen = open }
            segment
        }
        return Change(updated, added, closed)
    }

    /** Finish the trailing sentence (pause, stop, or a long silence); returns its ID if one was open. */
    fun closeOpen(): Int? {
        if (!lastOpen) return null
        lastOpen = false
        return last?.id
    }

    /** Close the open sentence once nothing recorded after [nowMs] can still continue it. */
    fun closeIfStale(nowMs: Long, graceMs: Long): Int? {
        val open = last ?: return null
        return if (lastOpen && nowMs - open.endMs > CONTINUE_GAP_MS + graceMs) closeOpen() else null
    }

    companion object {
        /** An unfinished sentence absorbs the next text only within this gap from the same speaker. */
        const val CONTINUE_GAP_MS = 1_500L
        /** Short sentences are grouped up to this length; a longer sentence stays whole. */
        const val SEGMENT_TARGET_CHARS = 80
        /** Text without terminal punctuation is closed once it grows this long. */
        const val MAX_OPEN_CHARS = 240
    }
}
