package io.github.carnager.commander.ui

import androidx.compose.ui.res.stringResource
import io.github.carnager.commander.R
import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import me.saket.telephoto.zoomable.coil3.ZoomableAsyncImage
import me.saket.telephoto.zoomable.DoubleClickToZoomListener
import me.saket.telephoto.zoomable.ZoomSpec
import me.saket.telephoto.zoomable.rememberZoomableImageState
import me.saket.telephoto.zoomable.rememberZoomableState
import java.io.File

/** The images of one folder, opened at [index]. */
data class ViewerState(val dir: io.github.carnager.commander.vfs.Location, val images: List<io.github.carnager.commander.vfs.FileEntry>, val index: Int)

/**
 * Built-in image viewer, because a viewer app only ever gets the one file it was handed: here a
 * swipe goes to the next image of the folder, wherever the folder lives. Neighbours load ahead.
 */
@Composable
fun ImageViewer(vm: CommanderViewModel, v: ViewerState, onClose: () -> Unit) {
    val pager = rememberPagerState(initialPage = v.index) { v.images.size }
    var chrome by remember { mutableStateOf(true) }
    BackHandler(onBack = onClose)
    ImmersiveBars(visible = chrome)

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        HorizontalPager(
            state = pager,
            beyondViewportPageCount = 1,
            key = { v.images[it].name },
            modifier = Modifier.fillMaxSize(),
        ) { page ->
            val e = v.images[page]
            val loc = v.dir.child(e.name)
            val model: Any = if (loc.isLocal) File(loc.path) else RemoteImage(loc, e.modified, e.size)
            // Up to 10x for reading small print or checking focus; a double tap stops at 3x.
            val state = rememberZoomableImageState(rememberZoomableState(zoomSpec = ZoomSpec(maxZoomFactor = 10f)))
            Box(Modifier.fillMaxSize()) {
                ZoomableAsyncImage(
                    model = model,
                    contentDescription = e.name,
                    state = state,
                    onClick = { chrome = !chrome },
                    onDoubleClick = DoubleClickToZoomListener.cycle(maxZoomFactor = 3f),
                    modifier = Modifier.fillMaxSize(),
                )
                if (!state.isImageDisplayed) {
                    CircularProgressIndicator(color = Color.White, modifier = Modifier.align(Alignment.Center).size(40.dp))
                }
            }
        }

        AnimatedVisibility(visible = chrome, enter = fadeIn(), exit = fadeOut()) {
            val current = v.images[pager.currentPage.coerceIn(0, v.images.lastIndex)]
            val loc = v.dir.child(current.name)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.7f), Color.Transparent)))
                    .statusBarsPadding()
                    .padding(horizontal = 4.dp, vertical = 8.dp),
            ) {
                IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.action_close), tint = Color.White) }
                Column(Modifier.weight(1f)) {
                    Text(current.name, color = Color.White, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
                    Text("${pager.currentPage + 1} / ${v.images.size}", color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.bodySmall)
                }
                IconButton(onClick = { vm.openFile(loc, current.size, OpenMode.SHARE) }) {
                    Icon(Icons.Outlined.Share, stringResource(R.string.action_share), tint = Color.White)
                }
                IconButton(onClick = { vm.openFile(loc, current.size, OpenMode.CHOOSER) }) {
                    Icon(Icons.AutoMirrored.Outlined.OpenInNew, stringResource(R.string.action_open_with), tint = Color.White)
                }
            }
        }
    }
}

/** Hides status and navigation bars while the viewer's own bar is hidden, and restores them on exit. */
@Composable
private fun ImmersiveBars(visible: Boolean) {
    val view = LocalView.current
    val window = (view.context as? Activity)?.window ?: return
    val controller = remember(window) { WindowCompat.getInsetsController(window, view) }
    DisposableEffect(controller) {
        val lightBefore = controller.isAppearanceLightStatusBars
        controller.isAppearanceLightStatusBars = false
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        onDispose {
            controller.show(WindowInsetsCompat.Type.systemBars())
            controller.isAppearanceLightStatusBars = lightBefore
        }
    }
    LaunchedEffect(visible) {
        if (visible) controller.show(WindowInsetsCompat.Type.systemBars()) else controller.hide(WindowInsetsCompat.Type.systemBars())
    }
}
