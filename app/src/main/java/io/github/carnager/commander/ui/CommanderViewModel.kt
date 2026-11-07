package io.github.carnager.commander.ui

import io.github.carnager.commander.Res
import io.github.carnager.commander.R
import android.app.Application
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.os.Environment
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.carnager.commander.CommanderApp
import io.github.carnager.commander.RemoteDocumentsProvider
import io.github.carnager.commander.data.Bookmark
import io.github.carnager.commander.data.Protocol
import io.github.carnager.commander.data.SavedTabs
import io.github.carnager.commander.data.SecretStore
import io.github.carnager.commander.data.ServerConfig
import io.github.carnager.commander.data.Settings
import io.github.carnager.commander.data.SortBy
import io.github.carnager.commander.ops.ArchiveFormat
import io.github.carnager.commander.ops.Conflict
import io.github.carnager.commander.ops.Transfers
import io.github.carnager.commander.vfs.archiveKindOf
import io.github.carnager.commander.vfs.FileEntry
import io.github.carnager.commander.vfs.Location
import io.github.carnager.commander.vfs.UnknownHostKeyException
import io.github.carnager.commander.vfs.parentPath
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicLong

data class TabState(
    val id: Long,
    /** An empty path on a server means "its home directory", resolved on first load. */
    val location: Location,
    val history: List<Location> = emptyList(),
    val entries: List<FileEntry>? = null,
    val loading: Boolean = false,
    val error: String? = null,
    val selection: Set<String> = emptySet(),
    val root: String = "/",
    /** Null when not searching; otherwise the text typed so far. */
    val query: String? = null,
    /** Search subfolders too; results then carry their path relative to [location] as name. */
    val deep: Boolean = false,
    val results: List<FileEntry>? = null,
    val searching: Boolean = false,
)

/** What the properties dialog shows; sizes fill in while folders are being counted. */
data class Properties(
    val dir: Location,
    val items: List<FileEntry>,
    val files: Int = 0,
    val dirs: Int = 0,
    val bytes: Long = 0,
    val done: Boolean = false,
    /** Free and total bytes of the volume, for device storage. */
    val free: Pair<Long, Long>? = null,
)

/** Files waiting to be pasted into whatever folder the user opens next. */
data class Clipboard(val from: Location, val items: List<FileEntry>, val move: Boolean)

/** An archive wants a password; [retry] repeats whatever ran into it once one is given. */
data class PasswordPrompt(val archiveId: String, val archive: String, val wrong: Boolean, val retry: () -> Unit)

data class HostKeyPrompt(val error: UnknownHostKeyException, val retry: () -> Unit)

/** Some selected names already exist in the target; the user decides before anything is touched. */
data class ConflictPrompt(val names: List<String>, val proceed: (Conflict) -> Unit)

data class UiState(
    val tabs: List<TabState>,
    val active: Int,
    val settings: Settings = Settings(),
    val hostKey: HostKeyPrompt? = null,
    val conflict: ConflictPrompt? = null,
    val viewer: ViewerState? = null,
    val text: TextDoc? = null,
    val clipboard: Clipboard? = null,
    /** Items another app shared with us, waiting for the user to pick a folder. */
    val incoming: List<Transfers.Incoming>? = null,
    val properties: Properties? = null,
    val password: PasswordPrompt? = null,
) {
    val current get() = tabs[active]
}

enum class OpenMode { VIEW, CHOOSER, SHARE }

sealed interface UiEvent {
    data class Message(val text: String, val action: String? = null, val onAction: (() -> Unit)? = null) : UiEvent
    data class OpenFile(val uri: Uri, val mime: String, val mode: OpenMode) : UiEvent
    data class Share(val uris: List<Uri>, val mime: String) : UiEvent
}

class CommanderViewModel(app: Application) : AndroidViewModel(app) {
    private val c = app as CommanderApp
    val vfs = c.vfs
    val stores = c.stores
    val transfers = c.transfers
    val audio = io.github.carnager.commander.media.AudioController(app) { message(it) }

    private val ids = AtomicLong(1)
    private val loadJobs = mutableMapOf<Long, Job>()
    private val searchJobs = mutableMapOf<Long, Job>()
    private val scroll = mutableMapOf<Pair<Long, Location>, Pair<Int, Int>>()

    private val _ui = MutableStateFlow(initialState())
    val ui: StateFlow<UiState> = _ui
    private val _events = MutableSharedFlow<UiEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<UiEvent> = _events

    val servers get() = stores.servers.state
    val bookmarks get() = stores.bookmarks.state

    private fun initialState(): UiState {
        val saved = stores.tabs.value
        // Archive tabs are not restored: their scratch data is gone after a restart.
        val locations = saved.tabs.filter { it.isLocal || stores.servers.value.any { s -> s.id == it.source } }
            .ifEmpty { listOf(homeLocation()) }
        val tabs = locations.map { newTabState(it) }
        return UiState(tabs, saved.active.coerceIn(0, tabs.lastIndex), settings = stores.settings.value)
    }

    private fun homeLocation() = Location(Location.LOCAL, Environment.getExternalStorageDirectory().absolutePath)

    private fun newTabState(loc: Location) =
        TabState(ids.getAndIncrement(), loc, root = if (loc.isLocal) vfs.local.rootOf(loc.path) else "/")

    // ---- Tabs ----

    fun setActive(index: Int) = _ui.update {
        if (index == it.active || index !in it.tabs.indices) it
        else it.copy(active = index)
    }.also { persist() }

    fun openTab(loc: Location) {
        val tab = newTabState(loc)
        _ui.update { it.copy(tabs = it.tabs + tab, active = it.tabs.size) }
        persist()
        load(tab.id)
    }

    fun closeTab(id: Long) {
        val s = _ui.value
        if (s.tabs.size == 1) return
        val idx = s.tabs.indexOfFirst { it.id == id }
        if (idx < 0) return
        loadJobs.remove(id)?.cancel()
        val tabs = s.tabs.filterNot { it.id == id }
        val active = when {
            idx < s.active -> s.active - 1
            idx == s.active -> s.active.coerceAtMost(tabs.lastIndex)
            else -> s.active
        }
        _ui.value = s.copy(tabs = tabs, active = active)
        persist()
    }

    private fun persist() {
        val s = _ui.value
        viewModelScope.launch(Dispatchers.IO) { stores.tabs.update { SavedTabs(s.tabs.map { it.location }, s.active) } }
    }

    private fun updateTab(id: Long, f: (TabState) -> TabState) =
        _ui.update { s -> s.copy(tabs = s.tabs.map { if (it.id == id) f(it) else it }) }

    private fun tab(id: Long) = _ui.value.tabs.firstOrNull { it.id == id }

    // ---- Navigation ----

    fun ensureLoaded(id: Long) {
        val t = tab(id) ?: return
        if (t.entries == null && !t.loading && t.error == null) load(id)
    }

    fun refresh(id: Long) = load(id)

    fun navigate(id: Long, loc: Location, pushHistory: Boolean = true) {
        val t = tab(id) ?: return
        if (loc == t.location) return load(id)
        updateTab(id) {
            it.copy(
                location = loc,
                history = if (pushHistory) (it.history + it.location).takeLast(50) else it.history,
                entries = vfs.cache[loc],
                selection = emptySet(),
                query = null,
                deep = false,
                results = null,
                searching = false,
                error = null,
                root = if (loc.isLocal) vfs.local.rootOf(loc.path) else "/",
            )
        }
        persist()
        load(id)
    }

    fun open(id: Long, entry: FileEntry) {
        val t = tab(id) ?: return
        val loc = t.location.child(entry.name)
        when {
            entry.isDir -> navigate(id, loc)
            kindOf(entry) == Kind.IMAGE -> {
                val images = visibleEntries(t).filter { kindOf(it) == Kind.IMAGE }
                _ui.update { it.copy(viewer = ViewerState(t.location, images, images.indexOfFirst { e -> e.name == entry.name }.coerceAtLeast(0))) }
            }
            archiveKindOf(entry.name) != null -> withLocalCopy(loc, entry.size) { file -> navigate(id, Location(vfs.openArchive(loc, file, archiveScratch), "/")) }
            kindOf(entry) == Kind.AUDIO -> {
                val tracks = visibleEntries(t).filter { kindOf(it) == Kind.AUDIO }
                audio.play(t.location, tracks, tracks.indexOfFirst { it.name == entry.name }.coerceAtLeast(0))
            }
            looksLikeText(entry) && entry.size <= TEXT_VIEW_MAX -> openText(loc, entry)
            else -> openFile(loc, entry.size, OpenMode.VIEW)
        }
    }

    /** Where "up" leads: the parent directory, or out of an archive into the folder holding it. */
    fun parentOf(t: TabState): Location? = when {
        isTrash(t) -> transfers.trash.ownerOf(t.location)
        t.location.path.isNotEmpty() && t.location.path != t.root -> t.location.copy(path = parentPath(t.location.path))
        vfs.isArchive(t.location.source) -> vfs.archiveOrigin(t.location.source)?.let { it.copy(path = parentPath(it.path)) }
        else -> null
    }

    fun up(id: Long) {
        val t = tab(id) ?: return
        parentOf(t)?.let { navigate(id, it) }
    }

    /** System back: history first, then parent directory. Returns false when there is nowhere to go. */
    fun back(): Boolean {
        val t = _ui.value.current
        if (t.selection.isNotEmpty()) {
            clearSelection(t.id); return true
        }
        if (t.query != null) {
            closeSearch(t.id); return true
        }
        t.history.lastOrNull()?.let { prev ->
            updateTab(t.id) { it.copy(history = it.history.dropLast(1)) }
            navigate(t.id, prev, pushHistory = false)
            return true
        }
        parentOf(t)?.let {
            navigate(t.id, it, pushHistory = false)
            return true
        }
        return false
    }

    private fun load(id: Long) {
        loadJobs.remove(id)?.cancel()
        updateTab(id) { it.copy(loading = true, error = null, entries = it.entries ?: vfs.cache[it.location]) }
        loadJobs[id] = viewModelScope.launch {
            try {
                var loc = tab(id)?.location ?: return@launch
                if (loc.path.isEmpty()) {
                    loc = loc.copy(path = vfs.home(loc.source))
                    updateTab(id) { it.copy(location = loc) }
                    persist()
                }
                val entries = vfs[loc.source].list(loc.path)
                vfs.cache[loc] = entries
                updateTab(id) { if (it.location == loc) it.copy(entries = entries, loading = false) else it }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                val msg = describe(e) { load(id) }
                updateTab(id) { it.copy(loading = false, error = msg) }
            }
        }
    }

    fun saveScroll(id: Long, loc: Location, index: Int, offset: Int) {
        scroll[id to loc] = index to offset
    }

    fun savedScroll(id: Long, loc: Location) = scroll[id to loc] ?: (0 to 0)

    // ---- Places ----

    fun openPlace(loc: Location, inNewTab: Boolean) {
        if (inNewTab) openTab(loc) else navigate(_ui.value.current.id, loc)
    }

    fun serverLocation(server: ServerConfig) = Location(server.id, server.startPath)

    fun volumes() = vfs.local.volumes()

    fun addBookmark(loc: Location, name: String) =
        stores.bookmarks.update { it + Bookmark(name = name, location = loc) }

    fun renameBookmark(id: String, name: String) =
        stores.bookmarks.update { list -> list.map { if (it.id == id) it.copy(name = name) else it } }

    fun removeBookmark(id: String) = stores.bookmarks.update { list -> list.filterNot { it.id == id } }

    /** Secrets: null keeps the stored value, "" clears it. */
    fun saveServer(config: ServerConfig, password: String?, privateKey: String?, passphrase: String?) {
        stores.servers.update { list ->
            if (list.any { it.id == config.id }) list.map { if (it.id == config.id) config else it } else list + config
        }
        password?.let { stores.secrets.put(SecretStore.password(config.id), it) }
        privateKey?.let { stores.secrets.put(SecretStore.privateKey(config.id), it) }
        passphrase?.let { stores.secrets.put(SecretStore.passphrase(config.id), it) }
        vfs.invalidate(config.id)
        RemoteDocumentsProvider.notifyRootsChanged(getApplication())
    }

    fun hasSecret(key: String) = stores.secrets.get(key) != null

    fun deleteServer(id: String) {
        val server = stores.servers.value.firstOrNull { it.id == id } ?: return
        stores.servers.update { list -> list.filterNot { it.id == id } }
        stores.bookmarks.update { list -> list.filterNot { it.location.source == id } }
        stores.secrets.removePrefix("$id.")
        stores.knownHosts.remove(server.host, server.port)
        vfs.invalidate(id)
        RemoteDocumentsProvider.notifyRootsChanged(getApplication())
        _ui.value.tabs.filter { it.location.source == id }.forEach {
            if (_ui.value.tabs.size > 1) closeTab(it.id) else navigate(it.id, homeLocation())
        }
    }

    fun forgetHostKey(server: ServerConfig) = stores.knownHosts.remove(server.host, server.port)

    fun trustHostKey() {
        val p = _ui.value.hostKey ?: return
        stores.knownHosts.put(p.error.host, p.error.port, p.error.keyType, p.error.keyBlob)
        _ui.update { it.copy(hostKey = null) }
        p.retry()
    }

    fun dismissHostKey() = _ui.update { it.copy(hostKey = null) }

    // ---- Selection & operations ----

    fun toggleSelect(id: Long, name: String) = updateTab(id) {
        it.copy(selection = if (name in it.selection) it.selection - name else it.selection + name)
    }

    fun selectAll(id: Long) = updateTab(id) { t ->
        t.copy(selection = visibleEntries(t).map { it.name }.toSet())
    }

    fun clearSelection(id: Long) = updateTab(id) { it.copy(selection = emptySet()) }

    fun invertSelection(id: Long) = updateTab(id) { t ->
        t.copy(selection = visibleEntries(t).map { it.name }.toSet() - t.selection)
    }

    fun createFile(id: Long, name: String) = operation(id) { loc ->
        val fs = vfs[loc.source]
        if (fs.stat(loc.child(name).path) != null) throw java.io.IOException(Res.str(R.string.err_already_exists, name))
        fs.openWrite(loc.child(name).path).close()
    }

    // ---- Properties ----

    private var measureJob: Job? = null

    /** Opens the properties of the selection (or of the folder itself when nothing is selected). */
    fun showProperties() {
        val t = _ui.value.current
        val selected = selectedEntries(t)
        val (dir, items) = if (selected.isNotEmpty()) t.location to selected
        else t.location.copy(path = parentPath(t.location.path)) to listOf(FileEntry(t.location.name, true, 0, 0))
        val free = if (t.location.isLocal) runCatching {
            val st = android.os.StatFs(t.location.path)
            st.availableBytes to st.totalBytes
        }.getOrNull() else null
        _ui.update { it.copy(properties = Properties(dir, items, free = free)) }
        measureJob?.cancel()
        measureJob = viewModelScope.launch {
            transfers.measure(dir, items) { f, d, b ->
                _ui.update { u -> u.copy(properties = u.properties?.copy(files = f, dirs = d, bytes = b)) }
            }
            _ui.update { it.copy(properties = it.properties?.copy(done = true)) }
        }
    }

    fun closeProperties() {
        measureJob?.cancel()
        _ui.update { it.copy(properties = null) }
    }

    fun visibleEntries(t: TabState): List<FileEntry> {
        val q = t.query?.trim().orEmpty()
        val base = when {
            t.deep && t.results != null -> t.results
            q.isNotEmpty() -> t.entries.orEmpty().filter { it.name.contains(q, ignoreCase = true) }
            else -> t.entries.orEmpty()
        }
        return sortEntries(base, _ui.value.settings)
    }

    // ---- Search ----

    fun openSearch(id: Long) = updateTab(id) { it.copy(query = "", selection = emptySet()) }

    fun closeSearch(id: Long) {
        searchJobs.remove(id)?.cancel()
        updateTab(id) { it.copy(query = null, deep = false, results = null, searching = false) }
    }

    fun setQuery(id: Long, q: String) {
        updateTab(id) { it.copy(query = q) }
        if (tab(id)?.deep == true) searchDeep(id)
    }

    fun setDeep(id: Long, deep: Boolean) {
        updateTab(id) { it.copy(deep = deep, results = null, selection = emptySet()) }
        searchDeep(id)
    }

    /**
     * Walks all subfolders breadth first (nearest matches come first) and streams matches into the
     * tab while it runs. Restarted on every keystroke after a short pause; capped at 2000 hits.
     */
    private fun searchDeep(id: Long) {
        searchJobs.remove(id)?.cancel()
        val t = tab(id) ?: return
        val q = t.query?.trim().orEmpty()
        if (!t.deep || q.length < 2) {
            updateTab(id) { it.copy(results = null, searching = false) }
            return
        }
        val showHidden = _ui.value.settings.showHidden
        searchJobs[id] = viewModelScope.launch {
            delay(300)
            updateTab(id) { it.copy(results = emptyList(), searching = true) }
            val fs = vfs[t.location.source]
            val found = mutableListOf<FileEntry>()
            val pending = ArrayDeque(listOf(""))
            var published = 0L
            try {
                while (pending.isNotEmpty() && found.size < 2000) {
                    val rel = pending.removeFirst()
                    val path = if (rel.isEmpty()) t.location.path else t.location.child(rel).path
                    val entries = try {
                        fs.list(path)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Throwable) {
                        continue // unreadable folder: skip it, keep searching
                    }
                    for (e in entries) {
                        if (e.hidden && !showHidden) continue
                        val r = if (rel.isEmpty()) e.name else "$rel/${e.name}"
                        if (e.name.contains(q, ignoreCase = true)) found += e.copy(name = r)
                        if (e.isDir && !e.isLink) pending += r
                    }
                    val now = System.currentTimeMillis()
                    if (now - published > 250) {
                        published = now
                        val snapshot = found.toList()
                        updateTab(id) { it.copy(results = snapshot) }
                    }
                }
            } finally {
                val snapshot = found.toList()
                updateTab(id) { if (it.query?.trim() == q) it.copy(results = snapshot, searching = false) else it }
            }
        }
    }

    private fun selectedEntries(t: TabState) = t.entries.orEmpty().filter { it.name in t.selection }

    /** Copies or moves the current selection into the directory open in tab [targetId]. */
    fun transferToTab(move: Boolean, targetId: Long) {
        val src = _ui.value.current
        val dst = tab(targetId) ?: return
        val items = selectedEntries(src)
        if (items.isEmpty()) return
        clearSelection(src.id)
        transfer(src.location, items, dst.location, move)
    }

    /** Puts the selection on the clipboard; it is pasted wherever the user goes next. */
    fun toClipboard(move: Boolean) {
        val src = _ui.value.current
        val items = selectedEntries(src)
        if (items.isEmpty()) return
        clearSelection(src.id)
        _ui.update { it.copy(clipboard = Clipboard(src.location, items, move)) }
    }

    fun clearClipboard() = _ui.update { it.copy(clipboard = null) }

    // ---- Receiving shares ----

    /** Takes what a share intent brought: files as content URIs, or plain text saved as a note. */
    fun receive(intent: Intent) {
        val app = getApplication<Application>()
        val uris = when (intent.action) {
            Intent.ACTION_SEND -> listOfNotNull(intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java))
            Intent.ACTION_SEND_MULTIPLE -> intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
            else -> return
        }
        val text = intent.getStringExtra(Intent.EXTRA_TEXT)
        viewModelScope.launch(Dispatchers.IO) {
            val items = uris.map { uri ->
                var name = uri.lastPathSegment?.substringAfterLast('/') ?: Res.str(R.string.incoming_default_name)
                var size = -1L
                runCatching {
                    app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                        if (c.moveToFirst()) {
                            c.getString(0)?.let { name = it }
                            if (!c.isNull(1)) size = c.getLong(1)
                        }
                    }
                }
                Transfers.Incoming(name, size) { app.contentResolver.openInputStream(uri) ?: throw java.io.IOException(Res.str(R.string.err_not_readable, name)) }
            }.ifEmpty {
                if (text.isNullOrEmpty()) return@launch
                val stamp = java.text.SimpleDateFormat("yyyy-MM-dd HH-mm", java.util.Locale.ROOT).format(java.util.Date())
                val bytes = text.toByteArray()
                listOf(Transfers.Incoming(Res.str(R.string.note_file_name, stamp), bytes.size.toLong()) { bytes.inputStream() })
            }
            _ui.update { it.copy(incoming = items) }
        }
    }

    fun clearIncoming() = _ui.update { it.copy(incoming = null) }

    fun saveIncoming() {
        val items = _ui.value.incoming ?: return
        val to = _ui.value.current.location
        clearIncoming()
        transfers.receive(items, to) { e -> afterOperation(e, Res.plural(R.plurals.msg_saved, items.size, items.size), to) }
    }

    /** Whether the clipboard can be pasted into the current tab's folder. */
    fun canPasteHere(): Boolean {
        val s = _ui.value
        val c = s.clipboard ?: return false
        val t = s.current
        return t.location != c.from && !vfs.isArchive(t.location.source) && !isTrash(t) && t.entries != null
    }

    fun canSaveHere(): Boolean {
        val t = _ui.value.current
        return !vfs.isArchive(t.location.source) && !isTrash(t) && t.entries != null
    }

    fun paste() {
        val c = _ui.value.clipboard ?: return
        clearClipboard()
        transfer(c.from, c.items, _ui.value.current.location, c.move)
    }

    private fun transfer(from: Location, items: List<FileEntry>, to: Location, move: Boolean) {
        viewModelScope.launch {
            // Always ask the target itself; a cached listing may be stale and would silently skip files.
            val existing = try {
                vfs[to.source].list(to.path).also { vfs.cache[to] = it }.map { it.name }.toSet()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                describe(e) { transfer(from, items, to, move) }?.let(::message)
                return@launch
            }
            val clashes = items.map { it.name }.filter { it in existing }

            fun start(conflict: Conflict) {
                val skipped = if (conflict == Conflict.SKIP) clashes.size else 0
                val verb = when {
                    move -> Res.str(R.string.msg_moved)
                    vfs.isArchive(from.source) -> Res.str(R.string.msg_extracted)
                    else -> Res.str(R.string.msg_copied)
                }
                val done = if (skipped > 0) Res.plural(R.plurals.msg_with_skipped, skipped, verb, skipped) else verb
                transfers.copy(from, items, to, move, conflict) { e ->
                    afterOperation(e, done, from, to, retry = { transfer(from, items, to, move) })
                }
            }
            if (clashes.isEmpty()) start(Conflict.SKIP)
            else _ui.update { it.copy(conflict = ConflictPrompt(clashes) { c -> _ui.update { u -> u.copy(conflict = null) }; start(c) }) }
        }
    }

    fun dismissConflict() = _ui.update { it.copy(conflict = null) }

    fun closeViewer() = _ui.update { it.copy(viewer = null) }

    override fun onCleared() {
        audio.release()
    }

    // ---- Text ----

    /**
     * Opens [loc] in the text viewer. Content that turns out to be binary goes to the system's
     * apps instead, as any other file would.
     */
    fun openText(loc: Location, entry: FileEntry) {
        _ui.update { it.copy(text = TextDoc(loc, entry, readOnly = vfs.isArchive(loc.source))) }
        viewModelScope.launch {
            try {
                val bytes = withContext(Dispatchers.IO) { vfs[loc.source].openRead(loc.path).use { readUpTo(it, TEXT_VIEW_MAX + 1) } }
                if (_ui.value.text?.loc != loc) return@launch
                if (bytes.size > TEXT_VIEW_MAX || isBinary(bytes)) {
                    closeText()
                    return@launch openFile(loc, entry.size, OpenMode.VIEW)
                }
                val decoded = decodeText(bytes)
                _ui.update { u ->
                    val d = u.text?.takeIf { it.loc == loc } ?: return@update u
                    u.copy(text = d.copy(text = decoded.text, charset = decoded.charset, crlf = decoded.crlf, bom = decoded.bom))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                closeText()
                describe(e) { openText(loc, entry) }?.let(::message)
            }
        }
    }

    fun closeText() = _ui.update { it.copy(text = null) }

    /** Writes [content] back in the file's own encoding and line ends, replacing it safely. */
    fun saveText(doc: TextDoc, content: String) {
        val body = if (doc.crlf) content.replace("\n", "\r\n") else content
        val bytes = (if (doc.bom) byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) else ByteArray(0)) +
            body.toByteArray(java.nio.charset.Charset.forName(doc.charset))
        val temp = File(getApplication<Application>().cacheDir, "edit/${System.nanoTime()}")
        temp.parentFile?.mkdirs()
        temp.writeBytes(bytes)
        _ui.update { u -> if (u.text?.loc == doc.loc) u.copy(text = u.text.copy(saving = true)) else u }
        transfers.upload(temp, doc.loc) { e ->
            temp.delete()
            _ui.update { u ->
                val d = u.text?.takeIf { it.loc == doc.loc } ?: return@update u
                u.copy(text = if (e == null) d.copy(text = content, saving = false) else d.copy(saving = false))
            }
            afterOperation(e, Res.str(R.string.msg_text_saved, doc.loc.name), doc.loc.copy(path = parentPath(doc.loc.path)), retry = { saveText(doc, content) })
        }
    }

    fun deleteSelection(permanent: Boolean) {
        val t = _ui.value.current
        val items = selectedEntries(t)
        clearSelection(t.id)
        if (permanent) transfers.delete(t.location, items) { e -> afterOperation(e, Res.str(R.string.msg_deleted), t.location) }
        else {
            var undo: Pair<Location, List<FileEntry>>? = null
            transfers.moveToTrash(t.location, items, onTrashed = { files, moved -> undo = files to moved }) { e ->
                reloadShowing(t.location)
                val (files, moved) = undo ?: (null to emptyList())
                when {
                    e is CancellationException -> message(Res.str(R.string.msg_cancelled))
                    e != null -> describe(e) {}?.let(::message)
                    files != null && moved.isNotEmpty() -> _events.tryEmit(
                        UiEvent.Message(
                            Res.plural(R.plurals.msg_moved_to_trash, moved.size, moved.size),
                            Res.str(R.string.action_undo),
                        ) { transfers.restore(files, moved) { err -> afterOperation(err, Res.str(R.string.msg_restored), t.location, files) } }
                    )
                }
            }
        }
    }

    // ---- Trash ----

    fun isTrash(t: TabState) = transfers.trash.isTrashFiles(t.location)

    /** Whether deleting here can go to a trash: device storage, and SFTP (inside the home directory). */
    fun canTrash(t: TabState): Boolean = !isTrash(t) && when {
        t.location.isLocal -> vfs.local.rootOf(t.location.path) != "/"
        else -> stores.servers.value.firstOrNull { it.id == t.location.source }?.protocol == Protocol.SFTP
    }

    fun restoreSelection() {
        val t = _ui.value.current
        val items = selectedEntries(t)
        clearSelection(t.id)
        transfers.restore(t.location, items) { e -> afterOperation(e, Res.str(R.string.msg_restored), t.location) }
    }

    fun emptyTrash() {
        val t = _ui.value.current
        val items = t.entries.orEmpty()
        if (items.isEmpty()) return
        transfers.delete(t.location, items) { e -> afterOperation(e, Res.str(R.string.msg_trash_emptied), t.location) }
    }

    /** Opens the trash belonging to [from] (a volume or an SFTP home). */
    fun openTrash(from: Location, inNewTab: Boolean) {
        viewModelScope.launch {
            try {
                val root = transfers.trash.rootFor(from) ?: return@launch message(Res.str(R.string.err_no_trash_here))
                val files = root.child("files")
                if (vfs[files.source].stat(files.path) == null) return@launch message(Res.str(R.string.msg_trash_empty))
                openPlace(files, inNewTab)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                describe(e) {}?.let(::message)
            }
        }
    }

    fun openDeviceTrash(inNewTab: Boolean) = openTrash(homeLocation(), inNewTab)

    fun rename(id: Long, old: String, new: String) = operation(id) { loc ->
        vfs[loc.source].rename(loc.child(old).path, loc.child(new).path)
        if (loc.isLocal) c.rescan(listOf(loc.child(old).path, loc.child(new).path))
    }

    fun mkdir(id: Long, name: String) = operation(id) { loc -> vfs[loc.source].mkdir(loc.child(name).path) }

    private fun operation(id: Long, block: suspend (Location) -> Unit) {
        val loc = tab(id)?.location ?: return
        viewModelScope.launch {
            try {
                block(loc)
                clearSelection(id)
                reloadShowing(loc)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                describe(e) { operation(id, block) }?.let(::message)
            }
        }
    }

    private fun afterOperation(e: Throwable?, doneText: String, vararg touched: Location, retry: () -> Unit = {}) {
        touched.forEach { reloadShowing(it) }
        when (e) {
            null -> message(doneText)
            is CancellationException -> message(Res.str(R.string.msg_cancelled))
            else -> describe(e, retry)?.let(::message)
        }
    }

    fun submitArchivePassword(password: String) {
        val p = _ui.value.password ?: return
        (vfs[p.archiveId] as? io.github.carnager.commander.vfs.ArchiveFs)?.password = password.toCharArray()
        vfs.cache.keys.removeAll { it.source == p.archiveId }
        _ui.update { it.copy(password = null) }
        p.retry()
    }

    fun dismissArchivePassword() = _ui.update { it.copy(password = null) }

    private fun reloadShowing(loc: Location) {
        vfs.cache.remove(loc)
        _ui.value.tabs.filter { it.location == loc }.forEach { load(it.id) }
    }

    // ---- Opening files ----

    /** Local files go out via FileProvider; remote and archive files are streamed by our DocumentsProvider. */
    fun openFile(loc: Location, size: Long, mode: OpenMode) {
        val app = getApplication<Application>()
        val uri = if (loc.isLocal) FileProvider.getUriForFile(app, "${app.packageName}.files", File(loc.path))
        else RemoteDocumentsProvider.uriFor(app.packageName, loc)
        _events.tryEmit(UiEvent.OpenFile(uri, mimeOf(loc.name), mode))
    }

    private val archiveScratch get() = File(getApplication<Application>().cacheDir, "archives")

    /**
     * Runs [then] with a device file for [loc]: the file itself when local, otherwise a copy fetched
     * into the cache. Archives need this, since their formats want random access to a real file.
     */
    private fun withLocalCopy(loc: Location, size: Long, then: (File) -> Unit) {
        if (loc.isLocal) return then(File(loc.path))
        val target = File(getApplication<Application>().cacheDir, "open/${loc.source.replace(':', '_')}${loc.path}")
        transfers.download(loc, size, target) { e ->
            when (e) {
                null -> viewModelScope.launch { then(target) }
                is CancellationException -> target.delete()
                else -> describe(e) { withLocalCopy(loc, size, then) }?.let(::message)
            }
        }
    }

    // ---- Archives ----

    /**
     * Unpacks the selected archive next to it. An archive that already holds a single top-level
     * folder is unpacked as is; anything else goes into a new folder named after the archive.
     */
    fun extractHere() {
        val t = _ui.value.current
        val e = selectedEntries(t).singleOrNull() ?: return
        val loc = t.location.child(e.name)
        clearSelection(t.id)
        withLocalCopy(loc, e.size) { file -> extract(t.location, loc, e, vfs.openArchive(loc, file, archiveScratch)) }
    }

    private fun extract(dir: Location, archiveLoc: Location, e: FileEntry, arc: String) {
        viewModelScope.launch {
            try {
                val dst = vfs[dir.source]
                val root = vfs[arc].list("/")
                val single = root.singleOrNull()?.takeIf { it.isDir }
                val folder = single?.name ?: archiveStem(e.name)
                if (dst.stat(dir.child(folder).path) != null) return@launch message(Res.str(R.string.err_already_exists, folder))
                val (target, items) = if (single != null) dir to root else {
                    dst.mkdir(dir.child(folder).path)
                    dir.child(folder) to root
                }
                transfers.copy(Location(arc, "/"), items, target, move = false, conflict = Conflict.SKIP) { err ->
                    afterOperation(err, Res.str(R.string.msg_extracted_to, folder), dir, retry = {
                        // The failed attempt left a half-made folder (ours: it did not exist before) that would block the retry.
                        viewModelScope.launch {
                            runCatching { vfs[dir.source].deleteRecursive(dir.child(folder).path, true) }
                            extract(dir, archiveLoc, e, arc)
                        }
                    })
                }
            } catch (err: CancellationException) {
                throw err
            } catch (err: Throwable) {
                describe(err) { extract(dir, archiveLoc, e, arc) }?.let(::message)
            }
        }
    }

    /** Packs the selection into [name] in the current folder. */
    fun compress(name: String, format: ArchiveFormat, password: String = "") {
        val t = _ui.value.current
        val items = selectedEntries(t)
        if (items.isEmpty()) return
        clearSelection(t.id)
        transfers.compress(t.location, items, t.location, name, format, password.ifEmpty { null }?.toCharArray()) { e ->
            afterOperation(e, Res.str(R.string.msg_created, name), t.location)
        }
    }

    fun selectionIsArchive(t: TabState) = t.selection.singleOrNull()?.let { archiveKindOf(it) } != null

    /**
     * Hands the selected files to another app. Remote and archive files go out as streams from our
     * provider, so nothing is downloaded up front. Folders are skipped: apps expect files.
     */
    fun shareSelection() {
        val t = _ui.value.current
        val files = selectedEntries(t).filter { !it.isDir }
        if (files.isEmpty()) return message(Res.str(R.string.msg_cant_share_folders))
        clearSelection(t.id)
        val app = getApplication<Application>()
        val uris = files.map { e ->
            val loc = t.location.child(e.name)
            if (loc.isLocal) FileProvider.getUriForFile(app, "${app.packageName}.files", File(loc.path))
            else RemoteDocumentsProvider.uriFor(app.packageName, loc)
        }
        // The most specific type that covers everything, so share targets can filter sensibly.
        val mimes = files.map { mimeOf(it.name) }.distinct()
        val mime = when {
            mimes.size == 1 -> mimes.single()
            mimes.map { it.substringBefore('/') }.distinct().size == 1 -> mimes.first().substringBefore('/') + "/*"
            else -> "*/*"
        }
        _events.tryEmit(UiEvent.Share(uris, mime))
    }

    fun openSelection(mode: OpenMode) {
        val t = _ui.value.current
        val e = selectedEntries(t).singleOrNull()?.takeIf { !it.isDir } ?: return
        clearSelection(t.id)
        openFile(t.location.child(e.name), e.size, mode)
    }

    // ---- Settings ----

    fun updateSettings(f: (Settings) -> Settings) {
        stores.settings.update(f)
        _ui.update { it.copy(settings = stores.settings.value) }
    }

    // ---- Helpers ----

    fun message(text: String) {
        _events.tryEmit(UiEvent.Message(text))
    }

    /** Turns an error into a user-facing message, or into a host-key prompt (then returns null). */
    private fun describe(e: Throwable, retry: () -> Unit): String? {
        val archive = generateSequence(e) { it.cause }.filterIsInstance<io.github.carnager.commander.vfs.ArchivePasswordException>().firstOrNull()
        if (archive != null) {
            _ui.update { it.copy(password = PasswordPrompt(archive.archiveId, archive.archive, archive.wrong, retry)) }
            return null
        }
        if (e is UnknownHostKeyException) {
            _ui.update { it.copy(hostKey = HostKeyPrompt(e, retry)) }
            return Res.str(R.string.msg_host_key_not_confirmed)
        }
        var root: Throwable = e
        while (root.cause != null && root.cause !== root && root.message.isNullOrBlank()) root = root.cause!!
        return root.message?.takeIf { it.isNotBlank() } ?: root.javaClass.simpleName
    }

    fun tabTitle(t: TabState): String = when {
        isTrash(t) -> Res.str(R.string.places_trash)
        vfs.isArchive(t.location.source) && t.location.path == "/" -> vfs.displayName(t.location.source)
        t.location.path.isEmpty() || t.location.path == t.root ->
            if (t.location.isLocal) volumes().firstOrNull { it.second == t.root }?.first ?: "/" else vfs.displayName(t.location.source)
        else -> t.location.name
    }

    fun rootLabel(t: TabState): String =
        if (t.location.isLocal) volumes().firstOrNull { it.second == t.root }?.first ?: "/" else vfs.displayName(t.location.source)
}

fun sortEntries(entries: List<FileEntry>, s: Settings): List<FileEntry> {
    val visible = if (s.showHidden) entries else entries.filterNot { it.hidden }
    val byKey: Comparator<FileEntry> = when (s.sortBy) {
        SortBy.NAME -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.name }
        SortBy.SIZE -> compareBy<FileEntry> { it.size }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }
        SortBy.DATE -> compareBy<FileEntry> { it.modified }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }
    }
    val ordered = if (s.descending) byKey.reversed() else byKey
    return visible.sortedWith(compareBy<FileEntry> { !it.isDir }.then(ordered))
}

fun mimeOf(name: String): String =
    MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase()) ?: "application/octet-stream"

/** "photos.tar.gz" → "photos": the folder name an archive extracts into. */
fun archiveStem(name: String): String {
    val n = name.lowercase()
    val ext = listOf(".tar.gz", ".tar.bz2", ".tar.xz").firstOrNull { n.endsWith(it) }
        ?: if ('.' in name) "." + n.substringAfterLast('.') else ""
    return name.dropLast(ext.length).ifEmpty { name }
}
