package io.github.carnager.commander.ui

import io.github.carnager.commander.R
import android.Manifest
import android.content.ActivityNotFoundException
import android.content.pm.PackageManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val vm: CommanderViewModel by viewModels()
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // Ask for the progress notification the first time something is actually transferred.
        lifecycleScope.launch {
            vm.transfers.state.collect { state ->
                // Only ever ask once; after that the choice lives in the system settings.
                val prefs = getSharedPreferences("ui", MODE_PRIVATE)
                if (state != null && !prefs.getBoolean("askedNotifications", false) && Build.VERSION.SDK_INT >= 33 &&
                    checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                ) {
                    prefs.edit().putBoolean("askedNotifications", true).apply()
                    notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
        }
        if (savedInstanceState == null) vm.receive(intent)
        setContent {
            CommanderTheme {
                BrowserScreen(vm, onOpenFile = ::openFile, onShare = ::share)
            }
        }
    }

    fun share(event: UiEvent.Share) {
        val intent = if (event.uris.size == 1) {
            Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, event.uris.single())
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(event.uris))
        }
        intent.type = event.mime
        // ClipData carries the read grant for every URI, not just the first.
        intent.clipData = android.content.ClipData.newRawUri(null, event.uris.first()).apply {
            event.uris.drop(1).forEach { addItem(android.content.ClipData.Item(it)) }
        }
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(intent, null))
    }

    private fun openFile(event: UiEvent.OpenFile) {
        val uri = event.uri
        val intent = when (event.mode) {
            OpenMode.SHARE -> Intent(Intent.ACTION_SEND).setType(event.mime).putExtra(Intent.EXTRA_STREAM, uri)
            else -> Intent(Intent.ACTION_VIEW).setDataAndType(uri, event.mime)
        }.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            startActivity(if (event.mode == OpenMode.VIEW) intent else Intent.createChooser(intent, null))
        } catch (_: ActivityNotFoundException) {
            // Unknown type: let the user pick any app that opens anything.
            try {
                startActivity(Intent.createChooser(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "*/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), null))
            } catch (_: ActivityNotFoundException) {
                Toast.makeText(this, R.string.msg_no_app_to_open, Toast.LENGTH_SHORT).show()
            }
        }
    }
}

@Composable
fun CommanderTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val ctx = LocalContext.current
    val colors = when {
        Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = colors, content = content)
}
