package io.github.carnager.commander.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.rememberScrollableState
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.automirrored.outlined.WrapText
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.carnager.commander.R
import io.github.carnager.commander.vfs.FileEntry
import io.github.carnager.commander.vfs.Location

/**
 * A text file opened in the built-in viewer. [text] is null while loading; it holds the content
 * with "\n" line ends, [crlf] and [bom] say how to write it back so a save changes only what was edited.
 */
data class TextDoc(
    val loc: Location,
    val entry: FileEntry,
    val text: String? = null,
    val charset: String = "UTF-8",
    val crlf: Boolean = false,
    val bom: Boolean = false,
    val readOnly: Boolean = false,
    val saving: Boolean = false,
)

/** Larger files are not loaded in full into an editable field; they can still be viewed. */
const val TEXT_EDIT_MAX = 1 shl 20

private const val TAB = "    "
private const val MAX_MATCHES = 10_000

/**
 * Viewer with line numbers, search and optional wrapping; "edit" turns it into an editor that
 * saves back to wherever the file lives, through the transfer queue (safe replace, also remote).
 */
@Composable
fun TextViewer(vm: CommanderViewModel, doc: TextDoc, wrap: Boolean, onClose: () -> Unit) {
    var editing by remember(doc.loc) { mutableStateOf(false) }
    var searching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var current by remember { mutableIntStateOf(0) }
    var unsavedPrompt by remember { mutableStateOf<(() -> Unit)?>(null) }
    val text = doc.text
    // The editor's content; reset whenever a (re)load or a save gives a new baseline.
    val field = rememberTextFieldState()
    LaunchedEffect(text) { if (text != null) field.edit { replace(0, length, text) } }
    val dirty by remember(text) { derivedStateOf { text != null && !field.text.contentEquals(text) } }

    fun leave(then: () -> Unit) {
        if (editing && dirty) unsavedPrompt = then else then()
    }
    BackHandler {
        when {
            searching -> searching = false
            editing -> leave { editing = false; text?.let { t -> field.edit { replace(0, length, t) } } }
            else -> onClose()
        }
    }

    // Search runs over the display lines (tabs expanded) of the viewer, or the raw editor text.
    val lines = remember(text) { text?.let(::displayLines) ?: emptyList() }
    val matches = remember(lines, query, searching) {
        if (!searching || query.isEmpty()) emptyList() else findMatches(lines, query)
    }
    LaunchedEffect(matches) { current = 0 }

    Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp)) {
                IconButton(onClick = { leave(onClose) }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.action_close)) }
                Column(Modifier.weight(1f).padding(start = 4.dp)) {
                    Text(
                        doc.loc.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.MiddleEllipsis,
                    )
                    if (text != null) {
                        val info = listOfNotNull(
                            pluralStringResource(R.plurals.text_lines, lines.size, lines.size),
                            doc.charset,
                            if (doc.crlf) "CRLF" else null,
                            if (doc.readOnly) stringResource(R.string.text_read_only) else null,
                            if (dirty) stringResource(R.string.text_modified) else null,
                        ).joinToString(" · ")
                        Text(info, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    }
                }
                if (text != null) {
                    if (!editing) IconButton(onClick = { searching = !searching }) { Icon(Icons.Outlined.Search, stringResource(R.string.action_search)) }
                    IconButton(onClick = { vm.updateSettings { it.copy(wrap = !it.wrap) } }) {
                        Icon(
                            Icons.AutoMirrored.Outlined.WrapText,
                            stringResource(R.string.text_wrap),
                            tint = if (wrap) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    when {
                        editing -> IconButton(onClick = { vm.saveText(doc, field.text.toString()) }, enabled = dirty && !doc.saving) {
                            if (doc.saving) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            else Icon(Icons.Outlined.Save, stringResource(R.string.action_save))
                        }
                        !doc.readOnly && text.length <= TEXT_EDIT_MAX ->
                            IconButton(onClick = { searching = false; editing = true }) { Icon(Icons.Outlined.Edit, stringResource(R.string.action_edit)) }
                    }
                }
                var menu by remember { mutableStateOf(false) }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, stringResource(R.string.menu_more)) }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.action_open_with)) },
                            leadingIcon = { Icon(Icons.AutoMirrored.Outlined.OpenInNew, null) },
                            onClick = { menu = false; leave { vm.openFile(doc.loc, doc.entry.size, OpenMode.CHOOSER) } },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.action_share)) },
                            leadingIcon = { Icon(Icons.Outlined.Share, null) },
                            onClick = { menu = false; vm.openFile(doc.loc, doc.entry.size, OpenMode.SHARE) },
                        )
                    }
                }
            }
            if (searching && !editing) {
                FindBar(
                    query = query,
                    onQuery = { query = it },
                    count = matches.size,
                    current = current,
                    onStep = { d -> if (matches.isNotEmpty()) current = (current + d).mod(matches.size) },
                    onClose = { searching = false },
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when {
                    text == null -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                    editing -> TextEditArea(field, wrap)
                    else -> TextLines(lines, wrap, matches, query.length, current)
                }
            }
        }
    }

    unsavedPrompt?.let { then ->
        AlertDialog(
            onDismissRequest = { unsavedPrompt = null },
            title = { Text(stringResource(R.string.text_unsaved_title)) },
            text = { Text(stringResource(R.string.text_unsaved_text, doc.loc.name)) },
            confirmButton = {
                TextButton(onClick = { unsavedPrompt = null; vm.saveText(doc, field.text.toString()); then() }) { Text(stringResource(R.string.action_save)) }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { unsavedPrompt = null }) { Text(stringResource(R.string.action_cancel)) }
                    TextButton(onClick = {
                        unsavedPrompt = null
                        text?.let { t -> field.edit { replace(0, length, t) } }
                        then()
                    }) { Text(stringResource(R.string.action_discard)) }
                }
            },
        )
    }
}

@Composable
private fun FindBar(query: String, onQuery: (String) -> Unit, count: Int, current: Int, onStep: (Int) -> Unit, onClose: () -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().height(48.dp).padding(start = 16.dp, end = 4.dp)) {
        BasicTextField(
            value = query,
            onValueChange = onQuery,
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { onStep(1) }),
            decorationBox = { inner ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (query.isEmpty()) Text(stringResource(R.string.text_find), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.outline)
                    inner()
                }
            },
            modifier = Modifier.weight(1f).focusRequester(focus),
        )
        if (query.isNotEmpty()) {
            Text(
                if (count == 0) stringResource(R.string.text_no_matches) else "${current + 1} / $count",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
        }
        IconButton(onClick = { onStep(-1) }, enabled = count > 0) { Icon(Icons.Outlined.KeyboardArrowUp, stringResource(R.string.text_previous)) }
        IconButton(onClick = { onStep(1) }, enabled = count > 0) { Icon(Icons.Outlined.KeyboardArrowDown, stringResource(R.string.text_next)) }
        IconButton(onClick = onClose) { Icon(Icons.Outlined.Close, stringResource(R.string.search_close)) }
    }
}

@Composable
private fun codeStyle() = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontSize = 13.sp,
    lineHeight = 19.sp,
    color = MaterialTheme.colorScheme.onSurface,
)

/** One match: line index and column in the display line. */
private data class Match(val line: Int, val col: Int)

/**
 * The read-only view: one list item per line, so even long files scroll smoothly. Without
 * wrapping, all lines shift sideways together while the numbers stay put.
 */
@Composable
private fun TextLines(lines: List<String>, wrap: Boolean, matches: List<Match>, matchLength: Int, current: Int) {
    val style = codeStyle()
    val numberStyle = style.copy(color = MaterialTheme.colorScheme.outline, textAlign = TextAlign.End)
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val charW = remember(style, density) { measurer.measure("0", style).size.width.toFloat() }
    val digits = lines.size.toString().length.coerceAtLeast(2)
    val gutter = with(density) { (charW * digits).toDp() } + 20.dp
    val gutterPx = with(density) { gutter.toPx() }
    val gutterColor = MaterialTheme.colorScheme.surfaceContainerLow
    val list = rememberLazyListState()
    val longest = remember(lines) { lines.maxOfOrNull { it.length } ?: 0 }
    val byLine = remember(matches) { matches.withIndex().groupBy({ it.value.line }, { it.index to it.value.col }) }
    val hit = SpanStyle(background = MaterialTheme.colorScheme.primary.copy(alpha = 0.25f))
    val hitNow = SpanStyle(background = MaterialTheme.colorScheme.primary, color = MaterialTheme.colorScheme.onPrimary)

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val viewport = constraints.maxWidth - gutterPx
        val maxX = (longest * charW + with(density) { 24.dp.toPx() } - viewport).coerceAtLeast(0f)
        var x by remember(wrap) { mutableFloatStateOf(0f) }
        LaunchedEffect(maxX) { x = x.coerceIn(0f, maxX) }
        val sideways = rememberScrollableState { d ->
            val before = x
            x = (x - d).coerceIn(0f, maxX)
            before - x
        }
        // Jump to the current match: its line near the top third, and sideways into view.
        LaunchedEffect(matches, current) {
            val m = matches.getOrNull(current) ?: return@LaunchedEffect
            list.animateScrollToItem((m.line - 3).coerceAtLeast(0))
            if (!wrap) {
                val at = m.col * charW
                if (at < x || at > x + viewport * 0.8f) x = (at - viewport / 3).coerceIn(0f, maxX)
            }
        }
        SelectionContainer {
            LazyColumn(
                state = list,
                modifier = Modifier
                    .fillMaxSize()
                    .drawBehind { drawRect(gutterColor, size = Size(gutterPx, size.height)) }
                    .then(if (wrap) Modifier else Modifier.scrollable(sideways, Orientation.Horizontal)),
            ) {
                items(lines.size) { i ->
                    Row(Modifier.fillMaxWidth()) {
                        DisableSelection {
                            Text("${i + 1}", style = numberStyle, maxLines = 1, modifier = Modifier.width(gutter).padding(end = 10.dp))
                        }
                        val line = lines[i]
                        val content: AnnotatedString = byLine[i]?.let { found -> highlight(line, found, matchLength, current, hit, hitNow) }
                            ?: AnnotatedString(line)
                        if (wrap) {
                            Text(content, style = style, modifier = Modifier.weight(1f).padding(start = 8.dp, end = 12.dp))
                        } else {
                            Box(Modifier.weight(1f).clipToBounds()) {
                                Text(
                                    content,
                                    style = style,
                                    softWrap = false,
                                    maxLines = 1,
                                    modifier = Modifier
                                        .wrapContentWidth(Alignment.Start, unbounded = true)
                                        .padding(start = 8.dp, end = 12.dp)
                                        .graphicsLayer { translationX = -x },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Marks the matches in [line]: (index among all matches, column) each, [len] characters long. */
private fun highlight(line: String, found: List<Pair<Int, Int>>, len: Int, current: Int, hit: SpanStyle, hitNow: SpanStyle) = buildAnnotatedString {
    var at = 0
    for ((index, col) in found) {
        if (col < at) continue
        append(line.substring(at, col))
        withStyle(if (index == current) hitNow else hit) { append(line.substring(col, (col + len).coerceAtMost(line.length))) }
        at = (col + len).coerceAtMost(line.length)
    }
    append(line.substring(at))
}

private fun findMatches(lines: List<String>, query: String): List<Match> {
    val out = ArrayList<Match>()
    for ((i, line) in lines.withIndex()) {
        var from = 0
        while (true) {
            val col = line.indexOf(query, from, ignoreCase = true)
            if (col < 0) break
            out += Match(i, col)
            if (out.size >= MAX_MATCHES) return out
            from = col + query.length
        }
    }
    return out
}

/** Lines as shown: tabs expanded, a final newline not counted as an extra empty line. */
private fun displayLines(text: String): List<String> {
    val raw = text.split('\n')
    val lines = if (raw.size > 1 && raw.last().isEmpty()) raw.dropLast(1) else raw
    return lines.map { if ('\t' in it) it.replace("\t", TAB) else it }
}

/**
 * The editor: one text field beside a gutter that numbers its logical lines (a wrapped line gets
 * one number), drawn only for the part on screen. The cursor's line number is highlighted.
 */
@Composable
private fun TextEditArea(field: androidx.compose.foundation.text.input.TextFieldState, wrap: Boolean) {
    val style = codeStyle()
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val charW = remember(style, density) { measurer.measure("0", style).size.width.toFloat() }
    val numberStyle = style.copy(color = MaterialTheme.colorScheme.outline)
    val numberNow = style.copy(color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
    val gutterColor = MaterialTheme.colorScheme.surfaceContainerLow
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    // Offsets where logical lines start; recomputed per edit, cheap next to the layout itself.
    val starts by remember {
        derivedStateOf {
            val t = field.text
            val s = ArrayList<Int>(t.length / 40 + 1).apply { add(0) }
            for (i in t.indices) if (t[i] == '\n') s += i + 1
            s.toIntArray()
        }
    }
    val digits = starts.size.toString().length.coerceAtLeast(2)
    val gutter = with(density) { (charW * digits).toDp() } + 20.dp
    val vertical = rememberScrollState()

    BoxWithConstraints(Modifier.fillMaxSize().drawBehind { drawRect(gutterColor, size = Size(with(density) { gutter.toPx() }, size.height)) }) {
        val viewportHeight = constraints.maxHeight
        val viewportWidth = with(density) { (constraints.maxWidth.toDp() - gutter) }
        Row(Modifier.fillMaxSize().verticalScroll(vertical).padding(vertical = 8.dp)) {
            val height = layout?.size?.height ?: 0
            Box(
                Modifier
                    .width(gutter)
                    .height(with(density) { height.toDp() })
                    .drawBehind {
                        val l = layout ?: return@drawBehind
                        if (l.lineCount == 0) return@drawBehind
                        val top = (vertical.value - 8.dp.toPx()).coerceAtLeast(0f)
                        val first = l.getLineForVerticalPosition(top)
                        val last = l.getLineForVerticalPosition(top + viewportHeight)
                        val cursorLine = starts.lineOf(field.selection.start)
                        for (v in first..last) {
                            val start = l.getLineStart(v)
                            val logical = java.util.Arrays.binarySearch(starts, start)
                            if (logical < 0) continue // a wrapped continuation
                            val label = "${logical + 1}"
                            val m = measurer.measure(label, if (logical == cursorLine) numberNow else numberStyle)
                            drawText(m, topLeft = Offset(size.width - 10.dp.toPx() - m.size.width, l.getLineTop(v)))
                        }
                    },
            )
            Box(Modifier.weight(1f).then(if (wrap) Modifier else Modifier.horizontalScroll(rememberScrollState()))) {
                BasicTextField(
                    state = field,
                    textStyle = style,
                    lineLimits = TextFieldLineLimits.MultiLine(),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
                    onTextLayout = { result -> layout = result() },
                    modifier = Modifier
                        .padding(start = 8.dp, end = 12.dp)
                        .then(if (wrap) Modifier.fillMaxWidth() else Modifier.widthIn(min = viewportWidth - 20.dp)),
                )
            }
        }
    }
}

/** Index of the logical line holding [offset], given the sorted line starts. */
private fun IntArray.lineOf(offset: Int): Int {
    val i = java.util.Arrays.binarySearch(this, offset)
    return if (i >= 0) i else -i - 2
}

/** Up to this size a text file opens in the viewer; larger ones (logs, dumps) go to other apps. */
const val TEXT_VIEW_MAX = 8 shl 20

/** Files the viewer takes: known text types, and extensionless ones (README, Makefile, PKGBUILD). */
fun looksLikeText(e: FileEntry): Boolean {
    if (e.isDir) return false
    val kind = kindOf(e)
    return kind == Kind.TEXT || kind == Kind.CODE || ('.' !in e.name.trimStart('.') && kind == Kind.OTHER)
}

fun readUpTo(input: java.io.InputStream, max: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buf = ByteArray(64 * 1024)
    while (out.size() < max) {
        val n = input.read(buf, 0, minOf(buf.size, max - out.size()))
        if (n < 0) break
        out.write(buf, 0, n)
    }
    return out.toByteArray()
}

/** NUL bytes near the start mean binary; text in UTF-16 is rare enough not to bother. */
fun isBinary(bytes: ByteArray): Boolean {
    val n = minOf(bytes.size, 8192)
    for (i in 0 until n) if (bytes[i] == 0.toByte()) return true
    return false
}

class DecodedText(val text: String, val charset: String, val crlf: Boolean, val bom: Boolean)

/** UTF-8 when the bytes are valid UTF-8, else Windows-1252 (old Windows and Latin-1 files). */
fun decodeText(bytes: ByteArray): DecodedText {
    val bom = bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()
    val body = if (bom) bytes.copyOfRange(3, bytes.size) else bytes
    val utf8 = Charsets.UTF_8.newDecoder()
        .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
        .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
    val (raw, charset) = try {
        utf8.decode(java.nio.ByteBuffer.wrap(body)).toString() to "UTF-8"
    } catch (_: java.nio.charset.CharacterCodingException) {
        String(body, java.nio.charset.Charset.forName("windows-1252")) to "windows-1252"
    }
    val crlf = "\r\n" in raw
    return DecodedText(if (crlf) raw.replace("\r\n", "\n") else raw, charset, crlf, bom)
}
