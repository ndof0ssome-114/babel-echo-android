package app.babelecho.android

import android.media.AudioAttributes
import android.media.MediaPlayer
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.babelecho.android.data.Meeting
import kotlinx.coroutines.delay
import java.io.File
import java.util.Locale

@Composable
fun PlaybackCard(meeting: Meeting, seekMs: Long?, seekRevision: Int, onError: (String) -> Unit) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var player by remember(meeting.id) { mutableStateOf<MediaPlayer?>(null) }
    var ready by remember(meeting.id) { mutableStateOf(false) }
    var playing by remember(meeting.id) { mutableStateOf(false) }
    var position by remember(meeting.id) { mutableLongStateOf(0) }
    var duration by remember(meeting.id) { mutableLongStateOf(meeting.durationMs) }
    var scrubbing by remember(meeting.id) { mutableStateOf(false) }
    val reportError by rememberUpdatedState(onError)
    DisposableEffect(meeting.id, lifecycle) {
        val media = MediaPlayer()
        player = media
        media.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
        media.setOnPreparedListener { ready = true; duration = it.duration.toLong() }
        media.setOnCompletionListener { playing = false; position = duration }
        media.setOnErrorListener { _, _, _ -> playing = false; ready = false; reportError("无法播放录音 / Cannot play recording"); true }
        runCatching {
            require(File(meeting.audioPath).length() > 44) { "Recording file is missing or empty" }
            media.setDataSource(meeting.audioPath); media.prepareAsync()
        }.onFailure { reportError(it.message ?: "Cannot open recording") }
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP && ready) { runCatching { media.pause() }; playing = false }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); media.release(); player = null }
    }
    LaunchedEffect(playing, ready) {
        while (playing && ready) {
            if (!scrubbing) position = player?.currentPosition?.toLong() ?: 0
            delay(200)
        }
    }
    LaunchedEffect(seekMs, seekRevision, ready) {
        if (ready && seekMs != null) {
            val target = seekMs.coerceIn(0, duration)
            player?.seekTo(target, MediaPlayer.SEEK_CLOSEST)
            position = target
        }
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(enabled = ready, onClick = {
                    if (playing) player?.pause() else {
                        if (position >= duration) player?.seekTo(0)
                        player?.start()
                    }
                    playing = !playing
                }) { Icon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, "播放或暂停 / Play or pause") }
                Text("${playbackTime(position)} / ${playbackTime(duration)}", Modifier.weight(1f))
                Text("WAV · 本机 / Local", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Slider(position.toFloat().coerceIn(0f, duration.coerceAtLeast(1).toFloat()), {
                scrubbing = true; position = it.toLong()
            }, enabled = ready, valueRange = 0f..duration.coerceAtLeast(1).toFloat(), onValueChangeFinished = {
                player?.seekTo(position, MediaPlayer.SEEK_CLOSEST); scrubbing = false
            })
        }
    }
}

private fun playbackTime(ms: Long): String = String.format(Locale.US, "%02d:%02d", ms / 60_000, ms / 1_000 % 60)
