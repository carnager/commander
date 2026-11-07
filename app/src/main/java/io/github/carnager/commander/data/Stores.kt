package io.github.carnager.commander.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import io.github.carnager.commander.vfs.Location
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

enum class Protocol(val label: String, val defaultPort: Int) { SFTP("SFTP", 22), SMB("SMB", 445) }

/** Non-secret part of a server entry; passwords and keys live in [SecretStore]. */
@Serializable
data class ServerConfig(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val protocol: Protocol = Protocol.SFTP,
    val host: String,
    val port: Int = protocol.defaultPort,
    val user: String,
    /** SMB only; empty for workgroup or local accounts. */
    val domain: String = "",
    /** Empty means the server's default (home directory for SFTP). */
    val startPath: String = "",
)

@Serializable
data class Bookmark(val id: String = UUID.randomUUID().toString(), val name: String, val location: Location)

@Serializable
data class SavedTabs(val tabs: List<Location>, val active: Int)

enum class SortBy { NAME, SIZE, DATE }

@Serializable
data class Settings(
    val showHidden: Boolean = false,
    val sortBy: SortBy = SortBy.NAME,
    val descending: Boolean = false,
    val grid: Boolean = false,
    /** Text viewer and editor wrap long lines. */
    val wrap: Boolean = true,
)

private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

/** A value persisted as one JSON file in app-private storage, exposed as a StateFlow. */
class JsonStore<T>(private val file: File, private val serializer: KSerializer<T>, default: T) {
    private val _state = MutableStateFlow(
        runCatching { json.decodeFromString(serializer, file.readText()) }.getOrDefault(default)
    )
    val state: StateFlow<T> = _state
    val value get() = _state.value

    @Synchronized
    fun update(transform: (T) -> T) {
        val next = transform(_state.value)
        _state.value = next
        val tmp = File(file.path + ".tmp")
        tmp.writeText(json.encodeToString(serializer, next))
        tmp.renameTo(file)
    }
}

/** Secrets encrypted with an Android Keystore key that never leaves the device. */
class SecretStore(context: Context) {
    private val store = JsonStore(
        File(context.filesDir, "secrets.json"),
        MapSerializer(String.serializer(), String.serializer()),
        emptyMap(),
    )

    fun get(key: String): String? = store.value[key]?.let { runCatching { decrypt(it) }.getOrNull() }

    fun put(key: String, value: String?) = store.update {
        if (value.isNullOrEmpty()) it - key else it + (key to encrypt(value))
    }

    fun removePrefix(prefix: String) = store.update { m -> m.filterKeys { !it.startsWith(prefix) } }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return gen.generateKey()
    }

    private fun encrypt(plain: String): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        return Base64.encodeToString(c.iv + c.doFinal(plain.toByteArray()), Base64.NO_WRAP)
    }

    private fun decrypt(enc: String): String {
        val raw = Base64.decode(enc, Base64.NO_WRAP)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, raw, 0, 12))
        return String(c.doFinal(raw, 12, raw.size - 12))
    }

    companion object {
        private const val ALIAS = "commander-secrets"
        fun password(serverId: String) = "$serverId.password"
        fun privateKey(serverId: String) = "$serverId.key"
        fun passphrase(serverId: String) = "$serverId.passphrase"
    }
}

/** Remembered SSH host keys, keyed by "host:port" → "keyType base64Blob". */
class KnownHosts(context: Context) {
    private val store = JsonStore(
        File(context.filesDir, "known_hosts.json"),
        MapSerializer(String.serializer(), String.serializer()),
        emptyMap(),
    )

    fun get(host: String, port: Int): Pair<String, String>? =
        store.value["$host:$port"]?.split(' ', limit = 2)?.let { it[0] to it[1] }

    fun put(host: String, port: Int, keyType: String, blob: String) = store.update { it + ("$host:$port" to "$keyType $blob") }

    fun remove(host: String, port: Int) = store.update { it - "$host:$port" }
}

class Stores(context: Context) {
    private val dir = context.filesDir
    val servers = JsonStore(File(dir, "servers.json"), ListSerializer(ServerConfig.serializer()), emptyList())
    val bookmarks = JsonStore(File(dir, "bookmarks.json"), ListSerializer(Bookmark.serializer()), emptyList())
    val tabs = JsonStore(File(dir, "tabs.json"), SavedTabs.serializer(), SavedTabs(emptyList(), 0))
    val settings = JsonStore(File(dir, "settings.json"), Settings.serializer(), Settings())
    val secrets = SecretStore(context)
    val knownHosts = KnownHosts(context)
}
