package io.github.carnager.commander.ui

import androidx.compose.ui.res.stringResource
import io.github.carnager.commander.R
import android.os.Environment
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AddToPhotos
import androidx.compose.material.icons.outlined.Bookmark
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.SdStorage
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.carnager.commander.data.Bookmark
import io.github.carnager.commander.data.ServerConfig
import io.github.carnager.commander.vfs.Location

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlacesSheet(
    vm: CommanderViewModel,
    onDismiss: () -> Unit,
    onAddBookmark: () -> Unit,
    onRenameBookmark: (Bookmark) -> Unit,
    onEditServer: (ServerConfig?) -> Unit,
    onDeleteServer: (ServerConfig) -> Unit,
) {
    val servers by vm.servers.collectAsStateWithLifecycle()
    val bookmarks by vm.bookmarks.collectAsStateWithLifecycle()
    val volumes = remember { vm.volumes() }
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    fun go(loc: Location, newTab: Boolean) {
        vm.openPlace(loc, newTab)
        onDismiss()
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet) {
        LazyColumn(Modifier.fillMaxWidth().navigationBarsPadding()) {
            item {
                PlaceRow(Icons.Outlined.AddToPhotos, stringResource(R.string.places_current_in_new_tab), null,
                    onClick = { go(vm.ui.value.current.location, true) })
            }

            item { SectionHeader(stringResource(R.string.places_device)) }
            items(volumes) { (label, path) ->
                val internal = path == Environment.getExternalStorageDirectory().absolutePath
                val ctx = androidx.compose.ui.platform.LocalContext.current
                val space = remember(path) {
                    runCatching {
                        val st = android.os.StatFs(path)
                        ctx.getString(
                            R.string.storage_free_of,
                            android.text.format.Formatter.formatShortFileSize(ctx, st.availableBytes),
                            android.text.format.Formatter.formatShortFileSize(ctx, st.totalBytes),
                        )
                    }.getOrNull()
                }
                PlaceRow(
                    if (internal) Icons.Outlined.PhoneAndroid else Icons.Outlined.SdStorage, label, space,
                    onClick = { go(Location(Location.LOCAL, path), false) },
                    onNewTab = { go(Location(Location.LOCAL, path), true) },
                )
            }
            item {
                val dl = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).absolutePath
                PlaceRow(Icons.Outlined.Download, stringResource(R.string.places_downloads), null,
                    onClick = { go(Location(Location.LOCAL, dl), false) },
                    onNewTab = { go(Location(Location.LOCAL, dl), true) })
            }
            item {
                PlaceRow(Icons.Outlined.Delete, stringResource(R.string.places_trash), null,
                    onClick = { vm.openDeviceTrash(false); onDismiss() },
                    onNewTab = { vm.openDeviceTrash(true); onDismiss() })
            }

            item { SectionHeader(stringResource(R.string.places_bookmarks), stringResource(R.string.action_add), onAddBookmark) }
            if (bookmarks.isEmpty()) item { Hint(stringResource(R.string.places_bookmarks_empty)) }
            items(bookmarks, key = { it.id }) { b ->
                PlaceRow(
                    Icons.Outlined.Bookmark, b.name,
                    vm.vfs.displayName(b.location.source) + " · " + b.location.path,
                    onClick = { go(b.location, false) },
                    onNewTab = { go(b.location, true) },
                    menu = listOf(stringResource(R.string.action_rename) to { onRenameBookmark(b) }, stringResource(R.string.action_remove) to { vm.removeBookmark(b.id) }),
                )
            }

            item { SectionHeader(stringResource(R.string.places_servers), stringResource(R.string.action_add)) { onEditServer(null) } }
            if (servers.isEmpty()) item { Hint(stringResource(R.string.places_servers_empty)) }
            items(servers, key = { it.id }) { s ->
                val guest = stringResource(R.string.places_guest)
                PlaceRow(
                    Icons.Outlined.Dns, s.name, "${s.protocol.label} · ${s.user.ifBlank { guest }}@${s.host}",
                    onClick = { go(vm.serverLocation(s), false) },
                    onNewTab = { go(vm.serverLocation(s), true) },
                    menu = listOf(stringResource(R.string.action_edit) to { onEditServer(s) }, stringResource(R.string.action_delete) to { onDeleteServer(s) }),
                )
            }
            item { Spacer(Modifier.padding(8.dp)) }
        }
    }
}

@Composable
private fun SectionHeader(title: String, action: String? = null, onAction: () -> Unit = {}) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp, top = 12.dp),
    ) {
        Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
        if (action != null) {
            TextButton(onClick = onAction) {
                Icon(Icons.Outlined.Add, null, Modifier.padding(end = 4.dp))
                Text(action)
            }
        }
    }
}

@Composable
private fun Hint(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
    )
}

/** Tap opens here, ↗ opens in a new tab; long-press shows [menu] (edit, delete …) where there is one. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PlaceRow(
    icon: ImageVector,
    title: String,
    subtitle: String?,
    onClick: () -> Unit,
    onNewTab: (() -> Unit)? = null,
    menu: List<Pair<String, () -> Unit>> = emptyList(),
) {
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(onClick = onClick, onLongClick = if (menu.isEmpty()) null else ({ open = true }))
                .padding(start = 24.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
        ) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(20.dp))
            Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
                Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
                if (subtitle != null) {
                    Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
            }
            if (onNewTab != null) {
                IconButton(onClick = onNewTab) {
                    Icon(Icons.AutoMirrored.Outlined.OpenInNew, stringResource(R.string.action_open_in_new_tab), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            menu.forEach { (label, action) ->
                DropdownMenuItem(text = { Text(label) }, onClick = { open = false; action() })
            }
        }
    }
}
