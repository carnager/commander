package io.github.carnager.commander.ui

import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import io.github.carnager.commander.R
import android.os.Environment
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.DriveFileMove
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.ViewList
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Bookmarks
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.FolderZip
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.RestoreFromTrash
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import io.github.carnager.commander.data.Bookmark
import io.github.carnager.commander.data.ServerConfig
import io.github.carnager.commander.data.SortBy
import io.github.carnager.commander.ops.TransferState
import io.github.carnager.commander.vfs.Location

private sealed interface Dlg {
    data object NewFolder : Dlg
    data object NewFile : Dlg
    data class Rename(val name: String) : Dlg
    data class Delete(val count: Int, val first: String, val trash: Boolean) : Dlg
    data object EmptyTrash : Dlg
    data class AddBookmark(val location: Location, val name: String) : Dlg
    data class RenameBookmark(val bookmark: Bookmark) : Dlg
    data class EditServer(val server: ServerConfig?) : Dlg
    data class DeleteServer(val server: ServerConfig) : Dlg
    data class PickTarget(val move: Boolean) : Dlg
    data class Compress(val name: String) : Dlg
}

@Composable
fun BrowserScreen(vm: CommanderViewModel, onOpenFile: (UiEvent.OpenFile) -> Unit, onShare: (UiEvent.Share) -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val ui by vm.ui.collectAsStateWithLifecycle()
    val transfer by vm.transfers.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val pager = rememberPagerState(initialPage = ui.active) { ui.tabs.size }
    var places by remember { mutableStateOf(false) }
    var tabsOpen by remember { mutableStateOf(false) }
    var dialog by remember { mutableStateOf<Dlg?>(null) }

    var hasAllFiles by remember { mutableStateOf(Environment.isExternalStorageManager()) }
    LifecycleResumeEffect(Unit) {
        val now = Environment.isExternalStorageManager()
        if (now && !hasAllFiles) ui.tabs.filter { it.location.isLocal }.forEach { vm.refresh(it.id) }
        hasAllFiles = now
        onPauseOrDispose {}
    }

    LaunchedEffect(Unit) {
        vm.events.collect { e ->
            when (e) {
                is UiEvent.Message -> launch {
                    val result = snackbar.showSnackbar(
                        e.text,
                        actionLabel = e.action,
                        // An undo needs time to be noticed; plain notes can go quickly.
                        duration = if (e.action != null) androidx.compose.material3.SnackbarDuration.Long else androidx.compose.material3.SnackbarDuration.Short,
                    )
                    if (result == androidx.compose.material3.SnackbarResult.ActionPerformed) e.onAction?.invoke()
                }
                is UiEvent.OpenFile -> onOpenFile(e)
                is UiEvent.Share -> onShare(e)
            }
        }
    }
    // The pager follows the active tab (new tab, drawer, closed tab) and reports swipes back once they
    // settle. Changing state mid-gesture interrupts the swipe, so during it header and bar only *show*
    // the target tab (see `shown`). Changes that came from the pager are not echoed back to it.
    var fromPager by remember { mutableStateOf(-1) }
    LaunchedEffect(pager) {
        snapshotFlow { pager.settledPage }.collect {
            fromPager = it
            vm.setActive(it)
        }
    }
    LaunchedEffect(ui.active, ui.tabs.size) {
        if (ui.active != fromPager && pager.targetPage != ui.active) pager.animateScrollToPage(ui.active)
    }

    val nowPlaying by vm.audio.state.collectAsStateWithLifecycle()
    var playerOpen by remember { mutableStateOf(false) }

    val tab = ui.current
    val selecting = tab.selection.isNotEmpty()
    // What header and bar display: the swipe's target while it is under way, else the active tab.
    val shownIndex = if (pager.isScrollInProgress) pager.targetPage.coerceIn(0, ui.tabs.lastIndex) else ui.active
    val shown = ui.tabs[shownIndex]
    BackHandler(enabled = selecting || tab.history.isNotEmpty() || vm.parentOf(tab) != null) {
        vm.back()
    }

    Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.statusBarsPadding().height(96.dp)) {
                    if (selecting) SelectionHeader(vm, tab, onRename = { dialog = Dlg.Rename(it) }, onDialog = { dialog = it })
                    else if (shown.query != null) SearchHeader(vm, shown)
                    else Header(vm, shown, ui, onDialog = { dialog = it })
                }

                HorizontalPager(
                    state = pager,
                    key = { ui.tabs.getOrNull(it)?.id ?: it },
                    userScrollEnabled = !selecting,
                    // Neighbouring tabs stay composed, so their listings (and server connections)
                    // are ready before the swipe instead of starting with it.
                    beyondViewportPageCount = 1,
                    // A quarter of the width is enough to switch; the default half felt like bouncing back.
                    flingBehavior = PagerDefaults.flingBehavior(state = pager, snapPositionalThreshold = 0.25f),
                    modifier = Modifier.weight(1f),
                ) { page ->
                    ui.tabs.getOrNull(page)?.let { FileListPage(vm, it, ui.settings, hasAllFiles) }
                }

                transfer?.let { TransferCard(it, onCancel = vm.transfers::cancel) }

                // A flat strip under a hairline: tabs or, while selecting, the actions.
                Column(Modifier.navigationBarsPadding()) {
                    nowPlaying?.let { np ->
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
                        MiniPlayer(vm, np, onOpen = { playerOpen = true })
                    }
                    if (!selecting) ui.clipboard?.let {
                        val what = if (it.items.size == 1) stringResource(R.string.quoted_name, it.items.first().name) else pluralStringResource(R.plurals.paste_items, it.items.size, it.items.size)
                        PasteBar(what, stringResource(if (it.move) R.string.paste_to_move else R.string.paste_to_copy), stringResource(R.string.paste_here), vm.canPasteHere(), vm::paste, vm::clearClipboard)
                    }
                    if (!selecting) ui.incoming?.let {
                        val what = if (it.size == 1) stringResource(R.string.quoted_name, it.first().name) else pluralStringResource(R.plurals.props_files, it.size, it.size)
                        PasteBar(what, stringResource(R.string.incoming_shared), stringResource(R.string.incoming_save_here), vm.canSaveHere(), vm::saveIncoming, vm::clearIncoming)
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
                    Box(Modifier.fillMaxWidth().height(56.dp)) {
                        if (selecting) ActionBar(
                            vm, ui,
                            onDelete = {
                                dialog = Dlg.Delete(tab.selection.size, tab.selection.first(), vm.canTrash(tab))
                            },
                            onPickTarget = { dialog = Dlg.PickTarget(it) },
                        )
                        else TabBar(vm, ui, shownIndex, onPlaces = { places = true }, onTabs = { tabsOpen = true })
                    }
                }
            }
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 64.dp))
            ui.viewer?.let { ImageViewer(vm, it, onClose = vm::closeViewer) }
            ui.text?.let { TextViewer(vm, it, ui.settings.wrap, onClose = vm::closeText) }
        }
    }

    nowPlaying?.let { np -> if (playerOpen) PlayerSheet(vm, np, onDismiss = { playerOpen = false }) }
    if (nowPlaying == null) playerOpen = false
    if (tabsOpen) TabDrawer(vm, ui, onDismiss = { tabsOpen = false }, onNewTab = { tabsOpen = false; places = true })
    if (places) {
        PlacesSheet(
            vm,
            onDismiss = { places = false },
            onAddBookmark = { dialog = Dlg.AddBookmark(tab.location, vm.tabTitle(tab)) },
            onRenameBookmark = { dialog = Dlg.RenameBookmark(it) },
            onEditServer = { places = false; dialog = Dlg.EditServer(it) },
            onDeleteServer = { dialog = Dlg.DeleteServer(it) },
        )
    }

    ui.hostKey?.let { HostKeyDialog(it, onTrust = vm::trustHostKey, onDismiss = vm::dismissHostKey) }
    ui.properties?.let { PropertiesDialog(vm, it, onDismiss = vm::closeProperties) }
    ui.password?.let { ArchivePasswordDialog(it, onSubmit = vm::submitArchivePassword, onDismiss = vm::dismissArchivePassword) }
    ui.conflict?.let { ConflictDialog(it, onDismiss = vm::dismissConflict) }

    val close = { dialog = null }
    when (val d = dialog) {
        null -> {}
        Dlg.NewFolder -> NameDialog(stringResource(R.string.new_folder), "", stringResource(R.string.action_create_new), close) { vm.mkdir(tab.id, it); close() }
        Dlg.NewFile -> NameDialog(stringResource(R.string.new_file), "", stringResource(R.string.action_create_new), close) { vm.createFile(tab.id, it); close() }
        is Dlg.Rename -> NameDialog(stringResource(R.string.action_rename), d.name, stringResource(R.string.action_rename), close) { vm.rename(tab.id, d.name, it); close() }
        is Dlg.Delete -> DeleteDialog(d, close) { permanent -> vm.deleteSelection(permanent); close() }
        Dlg.EmptyTrash -> ConfirmDialog(
            stringResource(R.string.empty_trash_title), stringResource(R.string.empty_trash_text), stringResource(R.string.empty_trash_confirm), close,
        ) { vm.emptyTrash(); close() }
        is Dlg.AddBookmark -> NameDialog(stringResource(R.string.bookmark_title), d.name, stringResource(R.string.action_save), close) {
            vm.addBookmark(d.location, it); vm.message(ctx.getString(R.string.msg_bookmark_saved)); close()
        }
        is Dlg.RenameBookmark -> NameDialog(stringResource(R.string.bookmark_rename_title), d.bookmark.name, stringResource(R.string.action_save), close) {
            vm.renameBookmark(d.bookmark.id, it); close()
        }
        is Dlg.EditServer -> ServerEditor(vm, d.server, close)
        is Dlg.DeleteServer -> ConfirmDialog(
            stringResource(R.string.delete_server_title), stringResource(R.string.delete_server_text, d.server.name), stringResource(R.string.action_delete), close,
        ) { vm.deleteServer(d.server.id); close() }
        is Dlg.PickTarget -> TargetSheet(
            vm, ui, d.move, close,
            onTab = { vm.transferToTab(d.move, it); close() },
            onClipboard = { vm.toClipboard(d.move); close() },
        )
        is Dlg.Compress -> CompressDialog(d.name, close) { name, format, password -> vm.compress(name, format, password); close() }
    }
}

/** Trash is the default where there is one; deleting for good is the deliberate second choice. */
@Composable
private fun DeleteDialog(d: Dlg.Delete, onDismiss: () -> Unit, onDelete: (permanent: Boolean) -> Unit) {
    val what = if (d.count == 1) stringResource(R.string.quoted_name, d.first) else pluralStringResource(R.plurals.props_entries, d.count, d.count)
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(if (d.trash) Icons.Outlined.Delete else Icons.Outlined.DeleteForever, null) },
        title = { Text(stringResource(if (d.trash) R.string.delete_dialog_trash_title else R.string.delete_dialog_permanent_title)) },
        text = {
            Text(
                stringResource(if (d.trash) R.string.delete_dialog_trash_text else R.string.delete_dialog_permanent_text, what)
            )
        },
        confirmButton = {
            TextButton(onClick = { onDelete(!d.trash) }) { Text(stringResource(if (d.trash) R.string.delete_dialog_trash_button else R.string.action_delete)) }
        },
        dismissButton = {
            Row {
                if (d.trash) TextButton(onClick = { onDelete(true) }) { Text(stringResource(R.string.delete_dialog_permanent_button)) }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
            }
        },
    )
}

@Composable
private fun Header(vm: CommanderViewModel, tab: TabState, ui: UiState, onDialog: (Dlg) -> Unit) {
    val trash = vm.isTrash(tab)
    val visible = remember(tab.entries, ui.settings) { vm.visibleEntries(tab) }
    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().height(48.dp)) {
            Breadcrumbs(vm, tab, Modifier.weight(1f))
            IconButton(onClick = { vm.openSearch(tab.id) }) { Icon(Icons.Outlined.Search, stringResource(R.string.action_search)) }
            IconButton(onClick = { vm.updateSettings { it.copy(grid = !it.grid) } }) {
                Icon(
                    if (ui.settings.grid) Icons.AutoMirrored.Outlined.ViewList else Icons.Outlined.GridView,
                    stringResource(if (ui.settings.grid) R.string.view_list else R.string.view_grid),
                )
            }
            HeaderMenu(vm, tab, ui, trash, onDialog)
        }
        Row(
            verticalAlignment = Alignment.Bottom,
            modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 4.dp),
        ) {
            Text(
                vm.tabTitle(tab),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(end = 12.dp),
            )
            if (tab.entries != null) {
                val dirs = visible.count { it.isDir }
                val files = visible.size - dirs
                Text(
                    listOfNotNull(
                        dirs.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.props_folders, it, it) },
                        files.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.props_files, it, it) },
                    ).joinToString(" · ").ifEmpty { stringResource(R.string.header_empty) },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }
        }
    }
}

/** Replaces the header while searching: the field filters as you type, the chip widens it to subfolders. */
@Composable
private fun SearchHeader(vm: CommanderViewModel, tab: TabState) {
    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
    LaunchedEffect(tab.id) { focus.requestFocus() }
    Column(Modifier.fillMaxSize().padding(horizontal = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().height(52.dp)) {
            IconButton(onClick = { vm.closeSearch(tab.id) }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.search_close)) }
            androidx.compose.foundation.text.BasicTextField(
                value = tab.query.orEmpty(),
                onValueChange = { vm.setQuery(tab.id, it) },
                singleLine = true,
                textStyle = MaterialTheme.typography.titleLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search),
                decorationBox = { inner ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (tab.query.isNullOrEmpty()) {
                            Text(stringResource(R.string.search_placeholder, vm.tabTitle(tab)), style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.outline, maxLines = 1)
                        }
                        inner()
                    }
                },
                modifier = Modifier.weight(1f).focusRequester(focus),
            )
            if (!tab.query.isNullOrEmpty()) {
                IconButton(onClick = { vm.setQuery(tab.id, "") }) { Icon(Icons.Outlined.Close, stringResource(R.string.search_clear)) }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 16.dp)) {
            androidx.compose.material3.FilterChip(
                selected = tab.deep,
                onClick = { vm.setDeep(tab.id, !tab.deep) },
                label = { Text(stringResource(R.string.search_subfolders)) },
                leadingIcon = if (tab.deep) ({ Icon(Icons.Outlined.Check, null, Modifier.size(16.dp)) }) else null,
            )
            Spacer(Modifier.weight(1f))
            val hits = vm.visibleEntries(tab).size
            if (!tab.query.isNullOrBlank()) {
                Text(
                    pluralStringResource(R.plurals.search_hits, hits, hits).let { if (tab.searching) stringResource(R.string.search_searching, it) else it },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun Breadcrumbs(vm: CommanderViewModel, tab: TabState, modifier: Modifier) {
    // Reverse scrolling anchors the row at its end, so the deepest crumbs stay visible.
    val scroll = remember(tab.location) { ScrollState(0) }
    val trash = vm.isTrash(tab)
    val segments = remember(tab.location, tab.root) {
        val rel = tab.location.path.removePrefix(tab.root).trim('/')
        if (trash || rel.isEmpty()) emptyList() else rel.split('/')
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.horizontalScroll(scroll, reverseScrolling = true).padding(start = 8.dp),
    ) {
        Crumb(
            if (trash) vm.rootLabel(tab) + " · " + stringResource(R.string.places_trash) else vm.rootLabel(tab),
            icon = if (trash) Icons.Outlined.Delete else sourceIcon(vm, tab.location),
        ) { if (!trash) vm.navigate(tab.id, tab.location.copy(path = tab.root)) }
        var path = tab.root.trimEnd('/')
        segments.forEach { seg ->
            path = "$path/$seg"
            val target = path
            Icon(
                Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.outline,
            )
            Crumb(seg) { vm.navigate(tab.id, tab.location.copy(path = target)) }
        }
    }
}

@Composable
private fun Crumb(label: String, icon: ImageVector? = null, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .combinedClickableCompat(onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        if (icon != null) {
            Icon(icon, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(6.dp))
        }
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
    }
}

@Composable
private fun HeaderMenu(vm: CommanderViewModel, tab: TabState, ui: UiState, trash: Boolean, onDialog: (Dlg) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val archive = vm.vfs.isArchive(tab.location.source)
    Box {
        IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, stringResource(R.string.menu_more)) }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            if (trash) {
                DropdownMenuItem(text = { Text(stringResource(R.string.menu_empty_trash)) }, onClick = { menu = false; onDialog(Dlg.EmptyTrash) })
            } else if (!archive) {
                DropdownMenuItem(text = { Text(stringResource(R.string.new_folder)) }, onClick = { menu = false; onDialog(Dlg.NewFolder) })
                DropdownMenuItem(text = { Text(stringResource(R.string.new_file)) }, onClick = { menu = false; onDialog(Dlg.NewFile) })
                DropdownMenuItem(text = { Text(stringResource(R.string.menu_add_bookmark)) }, onClick = {
                    menu = false; onDialog(Dlg.AddBookmark(tab.location, vm.tabTitle(tab)))
                })
                if (vm.canTrash(tab)) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.menu_open_trash)) }, onClick = { menu = false; vm.openTrash(tab.location, inNewTab = false) })
                }
            }
            DropdownMenuItem(text = { Text(stringResource(R.string.menu_refresh)) }, onClick = { menu = false; vm.refresh(tab.id) })
            if (!trash) DropdownMenuItem(text = { Text(stringResource(R.string.menu_properties)) }, onClick = { menu = false; vm.showProperties() })
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text(stringResource(R.string.menu_hidden_files)) },
                trailingIcon = { Checkbox(ui.settings.showHidden, null) },
                onClick = { vm.updateSettings { it.copy(showHidden = !it.showHidden) } },
            )
            listOf(SortBy.NAME to R.string.sort_by_name, SortBy.DATE to R.string.sort_by_date, SortBy.SIZE to R.string.sort_by_size).forEach { (s, label) ->
                DropdownMenuItem(
                    text = { Text(stringResource(label)) },
                    trailingIcon = { RadioButton(ui.settings.sortBy == s, null) },
                    onClick = { vm.updateSettings { it.copy(sortBy = s) } },
                )
            }
            DropdownMenuItem(
                text = { Text(stringResource(R.string.sort_descending)) },
                trailingIcon = { Checkbox(ui.settings.descending, null) },
                onClick = { vm.updateSettings { it.copy(descending = !it.descending) } },
            )
            if (ui.tabs.size > 1) {
                HorizontalDivider()
                DropdownMenuItem(text = { Text(stringResource(R.string.tab_close)) }, onClick = { menu = false; vm.closeTab(tab.id) })
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
private fun Modifier.combinedClickableCompat(onClick: () -> Unit, onLongClick: (() -> Unit)? = null) =
    combinedClickable(onClick = onClick, onLongClick = onLongClick)

@Composable
private fun SelectionHeader(vm: CommanderViewModel, tab: TabState, onRename: (String) -> Unit, onDialog: (Dlg) -> Unit) {
    val single = tab.entries?.firstOrNull { it.name == tab.selection.singleOrNull() }
    val readOnly = vm.vfs.isArchive(tab.location.source) || vm.isTrash(tab)
    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().height(48.dp)) {
            IconButton(onClick = { vm.clearSelection(tab.id) }) { Icon(Icons.Outlined.Close, stringResource(R.string.selection_clear)) }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { vm.selectAll(tab.id) }) { Icon(Icons.Outlined.SelectAll, stringResource(R.string.selection_all)) }
            var menu by remember { mutableStateOf(false) }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, stringResource(R.string.menu_more)) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.menu_properties)) }, onClick = { menu = false; vm.showProperties() })
                    DropdownMenuItem(text = { Text(stringResource(R.string.selection_invert)) }, onClick = { menu = false; vm.invertSelection(tab.id) })
                    if (single != null && !readOnly) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.action_rename)) }, onClick = { menu = false; onRename(single.name) })
                    }
                    if (single != null && !readOnly && vm.selectionIsArchive(tab)) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.menu_extract_here)) }, onClick = { menu = false; vm.extractHere() })
                    }
                    if (!readOnly) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.menu_create_archive)) }, onClick = {
                            menu = false
                            onDialog(Dlg.Compress(single?.let { if (it.isDir) it.name else archiveStem(it.name) } ?: vm.tabTitle(tab)))
                        })
                    }
                    if (single != null) {
                        if (single.isDir && !readOnly) {
                            DropdownMenuItem(text = { Text(stringResource(R.string.menu_bookmark_folder)) }, onClick = {
                                menu = false; vm.clearSelection(tab.id)
                                onDialog(Dlg.AddBookmark(tab.location.child(single.name), single.name))
                            })
                            DropdownMenuItem(text = { Text(stringResource(R.string.action_open_in_new_tab)) }, onClick = {
                                menu = false; vm.clearSelection(tab.id); vm.openTab(tab.location.child(single.name))
                            })
                        } else if (!single.isDir) {
                            DropdownMenuItem(text = { Text(stringResource(R.string.menu_open_with)) }, onClick = { menu = false; vm.openSelection(OpenMode.CHOOSER) })
                        }
                    }
                }
            }
        }
        Text(
            pluralStringResource(R.plurals.selection_count, tab.selection.size, tab.selection.size),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 20.dp, top = 4.dp),
        )
    }
}

@Composable
private fun ActionBar(vm: CommanderViewModel, ui: UiState, onDelete: () -> Unit, onPickTarget: (Boolean) -> Unit) {
    val tab = ui.current
    if (vm.isTrash(tab)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceEvenly,
            modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp),
        ) {
            ActionButton(Icons.Outlined.RestoreFromTrash, stringResource(R.string.action_restore), null, Modifier.weight(1f), onClick = vm::restoreSelection)
            ActionButton(Icons.Outlined.DeleteForever, stringResource(R.string.action_delete_permanently), null, Modifier.weight(1f), onClick = onDelete)
        }
        return
    }
    // Inside an archive, copying out is extracting; nothing can be moved or deleted there.
    val readOnly = vm.vfs.isArchive(tab.location.source)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceEvenly,
        modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp),
    ) {
        // Both ask where to: another tab or the clipboard.
        (if (readOnly) listOf(false) else listOf(false, true)).forEach { move ->
            ActionButton(
                if (move) Icons.AutoMirrored.Outlined.DriveFileMove else Icons.Outlined.ContentCopy,
                stringResource(if (move) R.string.action_move else if (readOnly) R.string.action_extract else R.string.action_copy),
                null,
                Modifier.weight(1f),
            ) { onPickTarget(move) }
        }
        IconButton(onClick = vm::shareSelection) { Icon(Icons.Outlined.Share, stringResource(R.string.action_share)) }
        if (!readOnly) IconButton(onClick = onDelete) { Icon(Icons.Outlined.Delete, stringResource(R.string.action_delete)) }
    }
}

@Composable
private fun ActionButton(
    icon: ImageVector,
    label: String,
    sub: String?,
    modifier: Modifier,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .combinedClickableCompat(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(10.dp))
        Column {
            Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1)
            if (sub != null) {
                Text(
                    sub,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * The bottom bar shows only the current tab; all tabs live in a drawer behind it. Swiping the
 * file list still steps through tabs, the dots show where you are.
 */
@Composable
private fun TabBar(vm: CommanderViewModel, ui: UiState, shownIndex: Int, onPlaces: () -> Unit, onTabs: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val tab = ui.tabs[shownIndex]
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxSize().padding(horizontal = 4.dp)) {
        IconButton(onClick = onPlaces) { Icon(Icons.Outlined.Bookmarks, stringResource(R.string.tabbar_places)) }
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .padding(vertical = 4.dp)
                .clip(RoundedCornerShape(16.dp))
                .combinedClickableCompat(onTabs),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(if (vm.isTrash(tab)) Icons.Outlined.Delete else sourceIcon(vm, tab.location), null, Modifier.size(16.dp), tint = colors.onSurface)
                Spacer(Modifier.width(6.dp))
                Text(
                    vm.tabTitle(tab),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.MiddleEllipsis,
                )
            }
            if (ui.tabs.size > 1) {
                Spacer(Modifier.height(5.dp))
                if (ui.tabs.size <= 8) {
                    Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        ui.tabs.indices.forEach { i ->
                            Box(
                                Modifier
                                    .size(if (i == shownIndex) 7.dp else 5.dp)
                                    .clip(CircleShape)
                                    .background(if (i == shownIndex) colors.primary else colors.outline)
                                    .align(Alignment.CenterVertically)
                            )
                        }
                    }
                } else {
                    Text("${shownIndex + 1} / ${ui.tabs.size}", style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                }
            }
        }
        // The tab count in a little box, as browsers do; opens the drawer too.
        IconButton(onClick = onTabs) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(24.dp)
                    .border(2.dp, colors.onSurfaceVariant, RoundedCornerShape(6.dp)),
            ) {
                Text("${ui.tabs.size}", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = colors.onSurfaceVariant)
            }
        }
    }
}

/** All open tabs with their full paths: switch, close, duplicate (long-press), or open a new one. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun TabDrawer(vm: CommanderViewModel, ui: UiState, onDismiss: () -> Unit, onNewTab: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    androidx.compose.material3.ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        androidx.compose.foundation.lazy.LazyColumn(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 8.dp)) {
            item {
                Text(
                    stringResource(R.string.tab_drawer_title),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(start = 24.dp, bottom = 8.dp),
                )
            }
            itemsIndexed(ui.tabs, key = { _, t -> t.id }) { i, t ->
                val active = i == ui.active
                var menu by remember { mutableStateOf(false) }
                Box {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 2.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(if (active) colors.secondaryContainer else androidx.compose.ui.graphics.Color.Transparent)
                            .combinedClickableCompat(onClick = { vm.setActive(i); onDismiss() }, onLongClick = { menu = true })
                            .padding(start = 12.dp, top = 10.dp, bottom = 10.dp),
                    ) {
                        Icon(if (vm.isTrash(t)) Icons.Outlined.Delete else sourceIcon(vm, t.location), null, tint = colors.onSurfaceVariant)
                        Spacer(Modifier.width(16.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                vm.tabTitle(t),
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                                maxLines = 1,
                                overflow = TextOverflow.MiddleEllipsis,
                            )
                            Text(
                                vm.rootLabel(t) + " · " + t.location.path,
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.StartEllipsis,
                            )
                        }
                        if (ui.tabs.size > 1) {
                            IconButton(onClick = { vm.closeTab(t.id) }) { Icon(Icons.Outlined.Close, stringResource(R.string.tab_close), tint = colors.onSurfaceVariant) }
                        } else Spacer(Modifier.width(12.dp))
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.tab_duplicate)) }, onClick = { menu = false; vm.openTab(t.location); onDismiss() })
                    }
                }
            }
            item {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .combinedClickableCompat(onNewTab)
                        .padding(start = 12.dp, top = 14.dp, bottom = 14.dp),
                ) {
                    Icon(Icons.Outlined.Add, null, tint = colors.primary)
                    Spacer(Modifier.width(16.dp))
                    Text(stringResource(R.string.tab_new), style = MaterialTheme.typography.bodyLarge, color = colors.primary)
                }
            }
        }
    }
}

@Composable
private fun TransferCard(t: TransferState, onCancel: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        Column(Modifier.padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    val count = if (t.filesTotal > 1) " · ${t.filesDone}/${t.filesTotal}" else ""
                    val waiting = if (t.queued > 0) " · " + pluralStringResource(R.plurals.transfer_waiting, t.queued, t.queued) else ""
                    Text(t.title + count + waiting, style = MaterialTheme.typography.labelLarge)
                    Text(t.current, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                TextButton(onClick = onCancel) { Text(stringResource(R.string.action_cancel)) }
            }
            Spacer(Modifier.height(4.dp))
            val p = t.progress
            if (p != null) LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth().padding(end = 12.dp))
            else LinearProgressIndicator(Modifier.fillMaxWidth().padding(end = 12.dp))
        }
    }
}

fun sourceIcon(vm: CommanderViewModel, loc: Location): ImageVector = when {
    loc.isLocal -> Icons.Outlined.PhoneAndroid
    vm.vfs.isArchive(loc.source) -> Icons.Outlined.FolderZip
    else -> Icons.Outlined.Dns
}

/** Shown while files wait on the clipboard: paste into the folder currently open, or drop them. */
@Composable
private fun PasteBar(what: String, detail: String, action: String, canPaste: Boolean, onPaste: () -> Unit, onClear: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .padding(start = 16.dp, end = 4.dp),
    ) {
        Icon(Icons.Outlined.ContentPaste, null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
            Text(what, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f),
            )
        }
        TextButton(onClick = onPaste, enabled = canPaste) { Text(action) }
        IconButton(onClick = onClear) { Icon(Icons.Outlined.Close, stringResource(R.string.action_discard)) }
    }
}
