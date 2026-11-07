package io.github.carnager.commander.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Audiotrack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material.icons.outlined.RepeatOne
import androidx.compose.material.icons.outlined.Shuffle
import androidx.compose.material.icons.outlined.SkipNext
import androidx.compose.material.icons.outlined.SkipPrevious
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import io.github.carnager.commander.R
import io.github.carnager.commander.media.NowPlaying
import kotlinx.coroutines.delay

/** The player's position, polled while it plays: the controller has no position events. */
@Composable
private fun rememberPosition(vm: CommanderViewModel, np: NowPlaying): Long {
    var pos by remember { mutableLongStateOf(vm.audio.position) }
    LaunchedEffect(np.playing, np.index) {
        pos = vm.audio.position
        while (np.playing) {
            delay(500)
            pos = vm.audio.position
        }
    }
    return pos
}

@Composable
private fun rememberArtwork(data: ByteArray?): ImageBitmap? = remember(data) {
    data?.let { runCatching { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() }.getOrNull() }
}

@Composable
private fun Artwork(np: NowPlaying, size: androidx.compose.ui.unit.Dp?, modifier: Modifier = Modifier) {
    val art = rememberArtwork(np.artwork)
    val shape = RoundedCornerShape(if (size != null && size < 64.dp) 8.dp else 16.dp)
    val m = (if (size != null) modifier.size(size) else modifier).clip(shape)
    if (art != null) {
        Image(art, null, contentScale = ContentScale.Crop, modifier = m)
    } else {
        Box(m.background(kindColor(Kind.AUDIO).copy(alpha = 0.18f)), contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.Audiotrack, null, tint = kindColor(Kind.AUDIO), modifier = Modifier.size(if (size != null) size / 2 else 72.dp))
        }
    }
}

/** A line above the tab bar while something is queued; tap for the full player. */
@Composable
fun MiniPlayer(vm: CommanderViewModel, np: NowPlaying, onOpen: () -> Unit) {
    val pos = rememberPosition(vm, np)
    Column(Modifier.fillMaxWidth()) {
        if (np.duration > 0) {
            LinearProgressIndicator(
                progress = { (pos.toFloat() / np.duration).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().height(2.dp),
                drawStopIndicator = {},
                gapSize = 0.dp,
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        ) {
            Artwork(np, 40.dp)
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(np.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    np.artist ?: "${np.index + 1} / ${np.queue.size}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            PlayPause(vm, np, filled = false)
            IconButton(onClick = { vm.audio.next() }, enabled = np.index < np.queue.lastIndex || np.repeat == Player.REPEAT_MODE_ALL) {
                Icon(Icons.Outlined.SkipNext, stringResource(R.string.player_next))
            }
            IconButton(onClick = { vm.audio.stop() }) { Icon(Icons.Outlined.Close, stringResource(R.string.player_stop)) }
        }
    }
}

@Composable
private fun PlayPause(vm: CommanderViewModel, np: NowPlaying, filled: Boolean) {
    val icon = if (np.playing) Icons.Outlined.Pause else Icons.Outlined.PlayArrow
    val label = stringResource(if (np.playing) R.string.player_pause else R.string.player_play)
    if (filled) {
        FilledIconButton(onClick = { vm.audio.toggle() }, modifier = Modifier.size(64.dp)) {
            if (np.buffering) CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp, color = MaterialTheme.colorScheme.onPrimary)
            else Icon(icon, label, Modifier.size(32.dp))
        }
    } else {
        IconButton(onClick = { vm.audio.toggle() }) {
            if (np.buffering) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) else Icon(icon, label)
        }
    }
}

private fun time(ms: Long): String {
    val s = ms / 1000
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

/** The full player: artwork, seek bar, controls, and the queue to jump around in. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerSheet(vm: CommanderViewModel, np: NowPlaying, onDismiss: () -> Unit) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet) {
        val pos = rememberPosition(vm, np)
        var dragging by remember { mutableStateOf(false) }
        var drag by remember { mutableFloatStateOf(0f) }
        val queue = rememberLazyListState(initialFirstVisibleItemIndex = (np.index - 1).coerceAtLeast(0))
        LazyColumn(state = queue, modifier = Modifier.fillMaxWidth()) {
            item {
                Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Artwork(np, null, Modifier.widthIn(max = 280.dp).fillMaxWidth().aspectRatio(1f))
                    Text(
                        np.title,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 20.dp),
                    )
                    listOfNotNull(np.artist, np.album).joinToString(" · ").takeIf { it.isNotEmpty() }?.let {
                        Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    val shown = if (dragging) drag else pos.toFloat()
                    Slider(
                        value = shown.coerceIn(0f, np.duration.toFloat().coerceAtLeast(1f)),
                        onValueChange = { dragging = true; drag = it },
                        onValueChangeFinished = { vm.audio.seek(drag.toLong()); dragging = false },
                        valueRange = 0f..np.duration.toFloat().coerceAtLeast(1f),
                        enabled = np.duration > 0,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                    Row(Modifier.fillMaxWidth()) {
                        Text(time(shown.toLong()), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Box(Modifier.weight(1f))
                        Text(if (np.duration > 0) time(np.duration) else "–", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                    ) {
                        val on = MaterialTheme.colorScheme.primary
                        val off = MaterialTheme.colorScheme.onSurfaceVariant
                        IconButton(onClick = { vm.audio.toggleShuffle() }) {
                            Icon(Icons.Outlined.Shuffle, stringResource(R.string.player_shuffle), tint = if (np.shuffle) on else off)
                        }
                        IconButton(onClick = { vm.audio.previous() }) { Icon(Icons.Outlined.SkipPrevious, stringResource(R.string.player_previous), Modifier.size(32.dp)) }
                        PlayPause(vm, np, filled = true)
                        IconButton(onClick = { vm.audio.next() }) { Icon(Icons.Outlined.SkipNext, stringResource(R.string.player_next), Modifier.size(32.dp)) }
                        IconButton(onClick = { vm.audio.cycleRepeat() }) {
                            Icon(
                                if (np.repeat == Player.REPEAT_MODE_ONE) Icons.Outlined.RepeatOne else Icons.Outlined.Repeat,
                                stringResource(R.string.player_repeat),
                                tint = if (np.repeat == Player.REPEAT_MODE_OFF) off else on,
                            )
                        }
                    }
                }
            }
            itemsIndexed(np.queue) { i, name ->
                val current = i == np.index
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .clickable { vm.audio.jump(i) }
                        .padding(horizontal = 24.dp, vertical = 8.dp),
                ) {
                    Text(
                        "${i + 1}",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                        modifier = Modifier.widthIn(min = 32.dp),
                    )
                    Text(
                        name,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.MiddleEllipsis,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            item { Box(Modifier.height(24.dp)) }
        }
    }
}
