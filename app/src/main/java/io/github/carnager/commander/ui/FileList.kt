package io.github.carnager.commander.ui

import androidx.compose.ui.res.stringResource
import io.github.carnager.commander.R
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.text.format.Formatter
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.outlined.Android
import androidx.compose.material.icons.outlined.Audiotrack
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.FolderOff
import androidx.compose.material.icons.outlined.FolderZip
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material.icons.outlined.WifiOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.SubcomposeAsyncImage
import io.github.carnager.commander.vfs.FileEntry
import io.github.carnager.commander.vfs.Location
import java.io.File
import java.text.DateFormat
import java.util.Date
import io.github.carnager.commander.data.Settings as AppSettings

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileListPage(vm: CommanderViewModel, tab: TabState, settings: AppSettings, hasAllFiles: Boolean) {
    LaunchedEffect(tab.id) { vm.ensureLoaded(tab.id) }

    if (tab.location.isLocal && !hasAllFiles) {
        PermissionPrompt()
        return
    }

    val entries = remember(tab.entries, tab.query, tab.deep, tab.results, settings) { vm.visibleEntries(tab) }
    var pulled by remember { mutableStateOf(false) }
    if (!tab.loading) pulled = false

    PullToRefreshBox(
        isRefreshing = pulled,
        onRefresh = { pulled = true; vm.refresh(tab.id) },
        modifier = Modifier.fillMaxSize(),
    ) {
        when {
            tab.entries == null && tab.loading -> Centered { CircularProgressIndicator() }
            tab.entries == null && tab.error != null -> Centered {
                Icon(Icons.Outlined.WifiOff, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(16.dp))
                Text(tab.error, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(16.dp))
                OutlinedButton(onClick = { vm.refresh(tab.id) }) { Text(stringResource(R.string.action_retry)) }
            }
            else -> Column(Modifier.fillMaxSize()) {
                if ((tab.loading && !pulled) || tab.searching) LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp))
                else Spacer(Modifier.height(2.dp))
                if (tab.error != null) {
                    Text(
                        tab.error,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 4.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.errorContainer)
                            .padding(12.dp, 8.dp),
                    )
                }
                if (entries.isEmpty() && !tab.loading && !tab.searching) {
                    Centered {
                        val searching = !tab.query.isNullOrBlank()
                        Icon(if (searching) Icons.Outlined.SearchOff else Icons.Outlined.FolderOff, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.outline)
                        Spacer(Modifier.height(12.dp))
                        Text(
                            when {
                                searching && tab.deep && tab.query!!.trim().length < 2 -> stringResource(R.string.list_min_two_chars)
                                searching -> stringResource(R.string.list_no_results)
                                vm.isTrash(tab) -> stringResource(R.string.list_trash_empty)
                                else -> stringResource(R.string.list_empty_folder)
                            },
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    Listing(vm, tab, entries, settings.grid)
                }
            }
        }
    }
}

@Composable
private fun Listing(vm: CommanderViewModel, tab: TabState, entries: List<FileEntry>, grid: Boolean) {
    val saved = remember(tab.id, tab.location, grid) { vm.savedScroll(tab.id, tab.location) }
    val onClick = { e: FileEntry -> if (tab.selection.isNotEmpty()) vm.toggleSelect(tab.id, e.name) else vm.open(tab.id, e) }
    // Deep-search hits live in subfolders; operations on them would need their own folders, so the
    // results are for finding and opening only.
    val onLong = { e: FileEntry -> if (!tab.deep || tab.results == null) vm.toggleSelect(tab.id, e.name) }
    // Server thumbnails cost a transfer each, so they only appear where they are the point: the grid.
    val thumbs = tab.location.isLocal || grid

    if (grid) {
        val state = remember(tab.id, tab.location) { LazyGridState(saved.first, saved.second) }
        // Search results are a different list: start them at the top, not at the folder's scroll position.
        LaunchedEffect(tab.query, tab.deep) { if (tab.query != null) state.scrollToItem(0) }
        DisposableEffect(tab.id, tab.location) {
            onDispose { vm.saveScroll(tab.id, tab.location, state.firstVisibleItemIndex, state.firstVisibleItemScrollOffset) }
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(104.dp),
            state = state,
            contentPadding = PaddingValues(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(entries, key = { it.name }) { e ->
                GridCell(e, tab.location, e.name in tab.selection, thumbs, { onClick(e) }, { onLong(e) })
            }
        }
    } else {
        val state = remember(tab.id, tab.location) { LazyListState(saved.first, saved.second) }
        LaunchedEffect(tab.query, tab.deep) { if (tab.query != null) state.scrollToItem(0) }
        DisposableEffect(tab.id, tab.location) {
            onDispose { vm.saveScroll(tab.id, tab.location, state.firstVisibleItemIndex, state.firstVisibleItemScrollOffset) }
        }
        LazyColumn(state = state, contentPadding = PaddingValues(vertical = 4.dp), modifier = Modifier.fillMaxSize()) {
            items(entries, key = { it.name }) { e ->
                FileRow(e, tab.location, e.name in tab.selection, thumbs, { onClick(e) }, { onLong(e) })
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileRow(entry: FileEntry, dir: Location, selected: Boolean, thumbs: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    val ctx = LocalContext.current
    val colors = MaterialTheme.colorScheme
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 1.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(if (selected) colors.secondaryContainer else Color.Transparent)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 8.dp, vertical = 8.dp),
    ) {
        Box {
            FileTile(entry, dir, thumbs, 44.dp, RoundedCornerShape(12.dp))
            if (selected) SelectedMark(Modifier.align(Alignment.BottomEnd))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                entry.name.substringAfterLast('/'),
                maxLines = 1,
                overflow = TextOverflow.MiddleEllipsis,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (entry.isDir) FontWeight.Medium else FontWeight.Normal,
            )
            val date = if (entry.modified > 0) DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(entry.modified)) else ""
            // Search hits from subfolders say where they are.
            val folder = entry.name.substringBeforeLast('/', "")
            val meta = listOfNotNull(
                folder.ifEmpty { null },
                if (entry.isDir) null else Formatter.formatShortFileSize(ctx, entry.size),
                date.ifEmpty { null },
            ).joinToString("  ·  ")
            if (meta.isNotEmpty()) {
                Text(meta, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GridCell(entry: FileEntry, dir: Location, selected: Boolean, thumbs: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(18.dp))
            .background(if (selected) colors.secondaryContainer else Color.Transparent)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(6.dp),
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f)) {
            FileTile(entry, dir, thumbs, null, RoundedCornerShape(14.dp))
            if (selected) SelectedMark(Modifier.align(Alignment.TopEnd).padding(6.dp))
        }
        Spacer(Modifier.height(6.dp))
        Text(
            entry.name.substringAfterLast('/'),
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            // Cut in the middle so the extension, often the telling part, stays visible.
            overflow = TextOverflow.MiddleEllipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
        )
    }
}

@Composable
private fun SelectedMark(modifier: Modifier) {
    Icon(
        Icons.Filled.CheckCircle,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.primary,
        modifier = modifier.size(20.dp).background(MaterialTheme.colorScheme.surface, CircleShape),
    )
}

/**
 * The square in front of every entry: a thumbnail for images and videos, otherwise a tinted
 * icon whose colour tells the file type at a glance. [size] null means "fill the parent".
 */
@Composable
private fun FileTile(entry: FileEntry, dir: Location, thumbs: Boolean, size: Dp?, shape: RoundedCornerShape) {
    val kind = remember(entry.name, entry.isDir) { kindOf(entry) }
    val tint = kindColor(kind)
    val base = (if (size != null) Modifier.size(size) else Modifier.fillMaxSize()).clip(shape)
    val iconTile: @Composable () -> Unit = {
        Box(base.background(tint.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
            Icon(kind.icon, null, tint = tint, modifier = if (size != null) Modifier.size(size * 0.55f) else Modifier.fillMaxSize(0.42f))
        }
    }
    val model: Any? = when {
        !thumbs || (kind != Kind.IMAGE && kind != Kind.VIDEO) -> null
        dir.isLocal -> File(dir.child(entry.name).path)
        kind == Kind.IMAGE -> RemoteThumb(dir.child(entry.name), entry.modified, entry.size)
        else -> null
    }
    if (model == null) {
        iconTile()
        return
    }
    Box(base) {
        SubcomposeAsyncImage(
            model = model,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            loading = { iconTile() },
            error = { iconTile() },
            modifier = Modifier.fillMaxSize(),
        )
        if (kind == Kind.VIDEO) {
            Icon(
                Icons.Outlined.PlayCircle, null, tint = Color.White,
                modifier = Modifier.align(Alignment.Center).fillMaxSize(0.4f),
            )
        }
    }
}

@Composable
private fun PermissionPrompt() {
    val ctx = LocalContext.current
    Centered {
        Icon(Icons.Outlined.Lock, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.permission_title), style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.permission_text),
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        Button(onClick = {
            ctx.startActivity(
                Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${ctx.packageName}"))
            )
        }) { Text(stringResource(R.string.permission_allow)) }
    }
}

@Composable
fun Centered(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) { content() }
}

enum class Kind(val icon: ImageVector) {
    DIR(Icons.Filled.Folder),
    IMAGE(Icons.Outlined.Image),
    VIDEO(Icons.Outlined.Movie),
    AUDIO(Icons.Outlined.Audiotrack),
    PDF(Icons.Outlined.PictureAsPdf),
    ARCHIVE(Icons.Outlined.FolderZip),
    TEXT(Icons.Outlined.Description),
    CODE(Icons.Outlined.Code),
    APK(Icons.Outlined.Android),
    OTHER(Icons.AutoMirrored.Outlined.InsertDriveFile),
}

/**
 * Fixed hues per type, independent of the wallpaper palette: with a grey Material You scheme the
 * list would otherwise be all grey. Mid-tone values that read on both light and dark surfaces.
 */
@Composable
fun kindColor(kind: Kind): Color = when (kind) {
    Kind.DIR -> Color(0xFF5E9CEF)
    Kind.IMAGE -> Color(0xFF26A69A)
    Kind.VIDEO -> Color(0xFFEC407A)
    Kind.AUDIO -> Color(0xFFFF9800)
    Kind.PDF -> Color(0xFFE53935)
    Kind.ARCHIVE -> Color(0xFFFFB300)
    Kind.TEXT -> Color(0xFF42A5F5)
    Kind.CODE -> Color(0xFF7E57C2)
    Kind.APK -> Color(0xFF66BB6A)
    Kind.OTHER -> MaterialTheme.colorScheme.onSurfaceVariant
}

private val archiveExt = setOf("zip", "7z", "rar", "tar", "gz", "tgz", "bz2", "xz", "zst", "jar", "cbz")
private val codeExt = setOf(
    "kt", "kts", "java", "py", "js", "ts", "c", "h", "cpp", "hpp", "rs", "go", "sh", "bash", "zsh", "fish", "lua", "rb", "pl", "php",
    "sql", "json", "xml", "html", "css", "yml", "yaml", "toml", "ini", "conf", "cfg", "gradle", "diff", "patch", "service", "desktop", "vim",
)
private val textExt = setOf("md", "txt", "log", "csv", "tsv", "srt", "nfo", "rst", "org", "tex", "env", "properties")

fun kindOf(e: FileEntry): Kind {
    if (e.isDir) return Kind.DIR
    val ext = e.name.substringAfterLast('.', "").lowercase()
    val mime = mimeOf(e.name)
    return when {
        mime.startsWith("image/") -> Kind.IMAGE
        mime.startsWith("video/") -> Kind.VIDEO
        mime.startsWith("audio/") -> Kind.AUDIO
        ext == "pdf" -> Kind.PDF
        ext == "apk" -> Kind.APK
        ext in archiveExt -> Kind.ARCHIVE
        ext in codeExt -> Kind.CODE
        ext in textExt -> Kind.TEXT
        mime.startsWith("text/") -> Kind.TEXT
        else -> Kind.OTHER
    }
}
