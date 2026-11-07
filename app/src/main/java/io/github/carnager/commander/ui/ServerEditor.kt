package io.github.carnager.commander.ui

import androidx.compose.ui.res.stringResource
import io.github.carnager.commander.R
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.carnager.commander.data.Protocol
import io.github.carnager.commander.data.SecretStore
import io.github.carnager.commander.data.ServerConfig

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerEditor(vm: CommanderViewModel, existing: ServerConfig?, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    var protocol by remember { mutableStateOf(existing?.protocol ?: Protocol.SFTP) }
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var host by remember { mutableStateOf(existing?.host ?: "") }
    var port by remember { mutableStateOf((existing?.port ?: 22).toString()) }
    var user by remember { mutableStateOf(existing?.user ?: "") }
    var domain by remember { mutableStateOf(existing?.domain ?: "") }
    var startPath by remember { mutableStateOf(existing?.startPath ?: "") }
    // Secrets are write-only in the UI: blank fields keep what is stored.
    var password by remember { mutableStateOf("") }
    var passphrase by remember { mutableStateOf("") }
    val hadPassword = remember { existing != null && vm.hasSecret(SecretStore.password(existing.id)) }
    val hadKey = remember { existing != null && vm.hasSecret(SecretStore.privateKey(existing.id)) }
    var newKey by remember { mutableStateOf<String?>(null) }
    var keyRemoved by remember { mutableStateOf(false) }
    var passwordRemoved by remember { mutableStateOf(false) }
    var keyError by remember { mutableStateOf<String?>(null) }
    val hasKey = newKey != null || (hadKey && !keyRemoved)

    val pickKey = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val text = runCatching { ctx.contentResolver.openInputStream(uri)!!.use { it.readBytes().decodeToString() } }.getOrNull()
        if (text != null && text.contains("PRIVATE KEY")) {
            newKey = text; keyRemoved = false; keyError = null
        } else {
            keyError = ctx.getString(R.string.server_err_no_private_key)
        }
    }

    val smb = protocol == Protocol.SMB
    // SMB allows guest access without a user name.
    val valid = host.isNotBlank() && (smb || user.isNotBlank()) && port.toIntOrNull() in 1..65535

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(if (existing == null) R.string.server_new_title else R.string.server_edit_title)) },
                    navigationIcon = { IconButton(onClick = onDismiss) { Icon(Icons.Outlined.Close, stringResource(R.string.action_close)) } },
                    actions = {
                        TextButton(enabled = valid, onClick = {
                            val config = (existing ?: ServerConfig(name = "", host = "", user = "")).copy(
                                name = name.ifBlank { host.trim() },
                                protocol = protocol,
                                domain = if (smb) domain.trim() else "",
                                host = host.trim(),
                                port = port.toInt(),
                                user = user.trim(),
                                startPath = startPath.trim(),
                            )
                            vm.saveServer(
                                config,
                                password = when {
                                    password.isNotEmpty() -> password
                                    passwordRemoved -> ""
                                    else -> null
                                },
                                privateKey = if (smb) "" else newKey ?: if (keyRemoved) "" else null,
                                passphrase = when {
                                    passphrase.isNotEmpty() -> passphrase
                                    keyRemoved || newKey != null -> ""
                                    else -> null
                                },
                            )
                            onDismiss()
                        }) { Text(stringResource(R.string.action_save)) }
                    },
                )
            },
        ) { pad ->
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize().padding(pad).imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
            ) {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    Protocol.entries.forEachIndexed { i, p ->
                        SegmentedButton(
                            selected = protocol == p,
                            onClick = {
                                // Follow the protocol's default port unless the user picked a custom one.
                                if (port == protocol.defaultPort.toString()) port = p.defaultPort.toString()
                                protocol = p
                            },
                            shape = SegmentedButtonDefaults.itemShape(i, Protocol.entries.size),
                        ) { Text(p.label) }
                    }
                }
                Field(stringResource(R.string.server_field_name), name) { name = it }
                Row {
                    Field(stringResource(R.string.server_field_host), host, Modifier.weight(1f), KeyboardType.Uri) { host = it }
                    Spacer(Modifier.width(12.dp))
                    Field(stringResource(R.string.server_field_port), port, Modifier.width(96.dp), KeyboardType.Number) { port = it.filter(Char::isDigit) }
                }
                Field(stringResource(if (smb) R.string.server_field_user_smb else R.string.server_field_user), user) { user = it }
                if (smb) Field(stringResource(R.string.server_field_domain), domain) { domain = it }
                Field(
                    stringResource(if (smb) R.string.server_field_start_smb else R.string.server_field_start),
                    startPath, keyboard = KeyboardType.Uri,
                ) { startPath = it }

                Text(stringResource(R.string.server_section_login), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp))
                if (!smb) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Key, null)
                        Spacer(Modifier.width(12.dp))
                        Text(
                            when {
                                newKey != null -> stringResource(R.string.server_key_new)
                                hasKey -> stringResource(R.string.server_key_set)
                                else -> stringResource(R.string.server_key_none)
                            },
                            modifier = Modifier.weight(1f),
                        )
                        if (hasKey) TextButton(onClick = { newKey = null; keyRemoved = true }) { Text(stringResource(R.string.action_remove)) }
                        OutlinedButton(onClick = { pickKey.launch(arrayOf("*/*")) }) { Text(stringResource(R.string.server_choose_file)) }
                    }
                    keyError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    if (hasKey) {
                        Field(stringResource(if (hadKey && newKey == null) R.string.server_field_passphrase_keep else R.string.server_field_passphrase), passphrase, secret = true) { passphrase = it }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Field(
                        stringResource(if (hadPassword && !passwordRemoved) R.string.server_field_password_keep else R.string.server_field_password),
                        password, Modifier.weight(1f), secret = true,
                    ) { password = it; if (it.isNotEmpty()) passwordRemoved = false }
                    if (hadPassword && !passwordRemoved) TextButton(onClick = { passwordRemoved = true; password = "" }) { Text(stringResource(R.string.action_remove)) }
                }
                Text(
                    stringResource(if (smb) R.string.server_note_smb else R.string.server_note_sftp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (existing != null && !smb) {
                    TextButton(onClick = { vm.forgetHostKey(existing); vm.message(ctx.getString(R.string.msg_host_key_forgotten)) }) {
                        Text(stringResource(R.string.server_forget_host_key))
                    }
                }
            }
        }
    }
}

@Composable
private fun Field(
    label: String,
    value: String,
    modifier: Modifier = Modifier.fillMaxWidth(),
    keyboard: KeyboardType = KeyboardType.Text,
    secret: Boolean = false,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = if (secret) KeyboardType.Password else keyboard, autoCorrectEnabled = false),
        visualTransformation = if (secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        modifier = modifier,
    )
}
