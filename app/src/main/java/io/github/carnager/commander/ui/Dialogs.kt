package io.github.carnager.commander.ui

import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import io.github.carnager.commander.R
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import io.github.carnager.commander.ops.ArchiveFormat

/** Single-line name prompt; preselects the base name (without extension) like desktop file managers. */
@Composable
fun NameDialog(title: String, initial: String, confirm: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    val stem = initial.substringBeforeLast('.').takeIf { it.isNotEmpty() && initial.contains('.') } ?: initial
    var value by remember { mutableStateOf(TextFieldValue(initial, TextRange(0, stem.length))) }
    val focus = remember { FocusRequester() }
    val valid = value.text.isNotBlank() && '/' !in value.text
    val submit = { if (valid) onConfirm(value.text.trim()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { submit() }),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
        },
        confirmButton = { TextButton(onClick = submit, enabled = valid) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
    LaunchedEffect(Unit) { focus.requestFocus() }
}

@Composable
fun ConfirmDialog(title: String, text: String, confirm: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
fun HostKeyDialog(p: HostKeyPrompt, onTrust: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.hostkey_title)) },
        text = {
            Column {
                Text(stringResource(R.string.hostkey_text, p.error.host, p.error.port))
                Spacer(Modifier.padding(6.dp))
                Text(p.error.keyType, style = MaterialTheme.typography.labelMedium)
                Text(p.error.fingerprint, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = onTrust) { Text(stringResource(R.string.hostkey_trust)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
fun ConflictDialog(p: ConflictPrompt, onDismiss: () -> Unit) {
    val list = p.names.take(5).joinToString("\n") + if (p.names.size > 5) "\n" + pluralStringResource(R.plurals.conflict_more, p.names.size - 5, p.names.size - 5) else ""
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(pluralStringResource(R.plurals.conflict_title, p.names.size, p.names.size)) },
        text = { Text(list) },
        confirmButton = {
            Column(horizontalAlignment = Alignment.End) {
                TextButton(onClick = { p.proceed(io.github.carnager.commander.ops.Conflict.KEEP_BOTH) }) { Text(stringResource(R.string.conflict_keep_both)) }
                TextButton(onClick = { p.proceed(io.github.carnager.commander.ops.Conflict.SKIP) }) { Text(stringResource(R.string.conflict_skip)) }
                TextButton(onClick = { p.proceed(io.github.carnager.commander.ops.Conflict.OVERWRITE) }) { Text(stringResource(R.string.conflict_replace)) }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
            }
        },
    )
}

/** Where a copy or move goes: any other open tab, or the clipboard to paste somewhere later. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TargetSheet(vm: CommanderViewModel, ui: UiState, move: Boolean, onDismiss: () -> Unit, onTab: (Long) -> Unit, onClipboard: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 8.dp)) {
            Text(
                stringResource(if (move) R.string.target_move_to else R.string.target_copy_to),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(start = 24.dp, bottom = 8.dp),
            )
            ui.tabs.filter { it.id != ui.current.id }.forEach { t ->
                TargetRow(sourceIcon(vm, t.location), vm.tabTitle(t), vm.rootLabel(t) + " · " + t.location.path) { onTab(t.id) }
            }
            if (ui.tabs.size > 1) HorizontalDivider(Modifier.padding(vertical = 4.dp))
            TargetRow(Icons.Outlined.ContentPaste, stringResource(R.string.target_clipboard), stringResource(R.string.target_clipboard_hint), onClipboard)
        }
    }
}

@Composable
private fun TargetRow(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 24.dp, vertical = 12.dp),
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(20.dp))
        Column {
            Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.StartEllipsis,
            )
        }
    }
}

@Composable
fun CompressDialog(initialName: String, onDismiss: () -> Unit, onConfirm: (String, ArchiveFormat, String) -> Unit) {
    var format by remember { mutableStateOf(ArchiveFormat.ZIP) }
    var base by remember { mutableStateOf(TextFieldValue(initialName, TextRange(0, initialName.length))) }
    var password by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    val valid = base.text.isNotBlank() && '/' !in base.text
    val canEncrypt = format != ArchiveFormat.TAR_GZ
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.compress_title)) },
        text = {
            Column {
                OutlinedTextField(
                    value = base,
                    onValueChange = { base = it },
                    singleLine = true,
                    suffix = { Text(format.extension) },
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
                Spacer(Modifier.padding(6.dp))
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    ArchiveFormat.entries.forEachIndexed { i, f ->
                        SegmentedButton(
                            selected = format == f,
                            onClick = { format = f },
                            shape = SegmentedButtonDefaults.itemShape(i, ArchiveFormat.entries.size),
                        ) { Text(f.label) }
                    }
                }
                if (canEncrypt) {
                    Spacer(Modifier.padding(6.dp))
                    SecretField(stringResource(R.string.compress_password), password) { password = it }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = { onConfirm(base.text.trim() + format.extension, format, if (canEncrypt) password else "") }) { Text(stringResource(R.string.action_create)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
    LaunchedEffect(Unit) { focus.requestFocus() }
}

/** Asked when an archive turns out to be encrypted; again, with a hint, when the password was wrong. */
@Composable
fun ArchivePasswordDialog(p: PasswordPrompt, onSubmit: (String) -> Unit, onDismiss: () -> Unit) {
    var password by remember(p) { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.Lock, null) },
        title = { Text(stringResource(R.string.archive_password_title, p.archive), maxLines = 2, overflow = TextOverflow.MiddleEllipsis) },
        text = {
            // An archive password is a one-off, not a login: keep password managers from offering to save it.
            val view = androidx.compose.ui.platform.LocalView.current
            androidx.compose.runtime.DisposableEffect(view) {
                view.rootView.importantForAutofill = android.view.View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
                onDispose {}
            }
            Column {
                if (p.wrong) {
                    Text(stringResource(R.string.archive_password_wrong), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.padding(4.dp))
                }
                SecretField(stringResource(R.string.archive_password_field), password, Modifier.focusRequester(focus), onDone = { if (password.isNotEmpty()) onSubmit(password) }) { password = it }
            }
        },
        confirmButton = { TextButton(enabled = password.isNotEmpty(), onClick = { onSubmit(password) }) { Text(stringResource(R.string.action_open)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
    LaunchedEffect(p) { focus.requestFocus() }
}

/** Password field with a show/hide toggle. */
@Composable
private fun SecretField(label: String, value: String, modifier: Modifier = Modifier, onDone: (() -> Unit)? = null, onChange: (String) -> Unit) {
    var visible by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        visualTransformation = if (visible) androidx.compose.ui.text.input.VisualTransformation.None else androidx.compose.ui.text.input.PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Password, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onDone?.invoke() }),
        trailingIcon = {
            androidx.compose.material3.IconButton(onClick = { visible = !visible }) {
                Icon(if (visible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility, stringResource(if (visible) R.string.action_hide else R.string.action_show))
            }
        },
        modifier = modifier.fillMaxWidth(),
    )
}

/** Name, place, size and content of the selection; folder sizes count up while they are walked. */
@Composable
fun PropertiesDialog(vm: CommanderViewModel, p: Properties, onDismiss: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val single = p.items.singleOrNull()
    fun size(b: Long) = android.text.format.Formatter.formatFileSize(ctx, b)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(single?.name ?: pluralStringResource(R.plurals.props_entries, p.items.size, p.items.size), maxLines = 2, overflow = TextOverflow.MiddleEllipsis) },
        text = {
            Column(verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp)) {
                PropRow(stringResource(R.string.props_location), vm.vfs.displayName(p.dir.source) + " · " + p.dir.path)
                if (single != null && !single.isDir) PropRow(stringResource(R.string.props_type), mimeOf(single.name))
                PropRow(
                    stringResource(R.string.props_size),
                    size(p.bytes) + (if (p.done) "" else " …"),
                )
                if (p.dirs > 0 || p.items.size > 1) {
                    val dirs = if (single?.isDir == true) p.dirs - 1 else p.dirs
                    PropRow(stringResource(R.string.props_contents), listOfNotNull(
                        pluralStringResource(R.plurals.props_files, p.files, p.files),
                        dirs.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.props_folders, it, it) },
                    ).joinToString(", ") + if (p.done) "" else " …")
                }
                if (single != null && single.modified > 0) {
                    PropRow(stringResource(R.string.props_modified), java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.LONG, java.text.DateFormat.SHORT).format(java.util.Date(single.modified)))
                }
                p.free?.let { (free, total) -> PropRow(stringResource(R.string.props_storage), stringResource(R.string.storage_free_of, size(free), size(total))) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) } },
    )
}

@Composable
private fun PropRow(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}
