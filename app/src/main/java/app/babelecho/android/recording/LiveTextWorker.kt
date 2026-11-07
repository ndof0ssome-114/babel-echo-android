package app.babelecho.android.recording

import app.babelecho.android.cloud.CloudClient
import app.babelecho.android.data.AppSettings
import app.babelecho.android.data.MeetingStore
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking

/**
 * Live translation and rolling summary for one recording, on a worker of their own so that a
 * slow text model never delays speech recognition.
 *
 * Segment IDs that fail to translate stay pending and are retried with the next batch, at most
 * [MAX_TRANSLATION_ATTEMPTS] times each, so a transient error does not leave sentences
 * untranslated forever and a persistent one does not loop on paid requests.
 */
internal class LiveTextWorker(
    private val store: MeetingStore,
    private val cloud: CloudClient,
    private val settings: AppSettings,
    private val meetingId: String,
    private val onUpdated: () -> Unit,
    private val onError: (String) -> Unit,
) {
    private val executor = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "babel-live-text") }
    private val pending = LinkedHashMap<Int, Int>() // segment ID -> failed attempts; executor thread only
    private var lastSummaryAt = System.currentTimeMillis()

    private val translate = settings.translateTo.isNotBlank() && settings.hasText("translation")
    private val summarize = settings.hasText("summary") && settings.autoSummarySeconds > 0

    fun segmentsAdded(ids: List<Int>) {
        if (!translate && !summarize) return
        runCatching { executor.execute { work(ids) } }
    }

    private fun work(ids: List<Int>) = guarded {
        if (translate) {
            ids.forEach { pending.putIfAbsent(it, 0) }
            translatePending()
        }
        if (summarize) summarizeIfDue()
    }

    /** The meeting can be deleted mid-request; that must not kill the worker thread. */
    private fun guarded(block: () -> Unit) {
        try { block() } catch (error: InterruptedException) { Thread.currentThread().interrupt() }
        catch (error: Exception) { onError("实时处理失败 / Live processing failed: ${error.message ?: error.javaClass.simpleName}") }
    }

    private fun translatePending() {
        if (pending.isEmpty()) return
        val meeting = store.get(meetingId) ?: return
        val batch = meeting.segments.filter { it.id in pending && it.translated.isBlank() }
        pending.keys.retainAll(batch.map { it.id }.toSet())
        if (batch.isEmpty()) return
        // The two sentences before the batch resolve pronouns and omitted subjects.
        val firstIndex = meeting.segments.indexOfFirst { it.id == batch.first().id }
        val context = meeting.segments.take(firstIndex.coerceAtLeast(0)).takeLast(2).map { it.text }
        runCatching { runBlocking { cloud.translateSegments(batch, settings.translateTo, settings, context) } }
            .onSuccess { translations ->
                val byId = batch.map { it.id }.zip(translations).toMap()
                store.update(meetingId) { current ->
                    current.copy(segments = current.segments.map { segment ->
                        byId[segment.id]?.let { segment.copy(translated = it) } ?: segment
                    })
                }
                pending.keys.removeAll(byId.keys)
                onUpdated()
            }
            .onFailure { error ->
                batch.forEach { segment ->
                    val attempts = (pending[segment.id] ?: 0) + 1
                    if (attempts >= MAX_TRANSLATION_ATTEMPTS) pending.remove(segment.id) else pending[segment.id] = attempts
                }
                onError("翻译失败，原文已保留 / Translation failed: ${error.message}")
            }
    }

    private fun summarizeIfDue() {
        if (System.currentTimeMillis() - lastSummaryAt < settings.autoSummarySeconds * 1_000L) return
        val current = store.get(meetingId) ?: return
        if (current.segments.size <= current.summaryUpTo) return
        val upTo = current.segments.size
        val fresh = current.segments.drop(current.summaryUpTo)
        // Like desktop, a few new words are not worth a paid rewrite of the whole summary.
        if (fresh.sumOf { it.text.trim().length } < settings.autoSummaryMinChars) return
        runCatching { runBlocking { cloud.updateSummary(current.summary, fresh, settings, current.title, current.mode) } }
            .onSuccess { summary ->
                store.update(meetingId) { it.copy(summary = summary, summaryUpTo = upTo) }
                onUpdated()
            }
            .onFailure { onError("实时摘要失败 / Live summary failed: ${it.message}") }
        // A failure waits for the next interval instead of retrying on every new sentence.
        lastSummaryAt = System.currentTimeMillis()
    }

    /** Runs the queued work, then a last translation pass, waiting at most [timeoutMs]. */
    fun finish(timeoutMs: Long) {
        runCatching { executor.execute { guarded { if (translate) translatePending() } } }
        executor.shutdown()
        val done = runCatching { executor.awaitTermination(timeoutMs, TimeUnit.MILLISECONDS) }.getOrDefault(false)
        if (!done) {
            cloud.cancel()
            executor.shutdownNow()
            runCatching { executor.awaitTermination(2, TimeUnit.SECONDS) }
        }
    }

    companion object {
        const val MAX_TRANSLATION_ATTEMPTS = 3
    }
}
