package io.github.carnager.commander.vfs

import io.github.carnager.commander.R
import io.github.carnager.commander.Res
import android.util.Base64
import io.github.carnager.commander.data.KnownHosts
import io.github.carnager.commander.data.SecretStore
import io.github.carnager.commander.data.ServerConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import net.schmizz.sshj.DefaultConfig
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.Buffer
import net.schmizz.sshj.common.KeyType
import net.schmizz.sshj.common.SSHException
import net.schmizz.sshj.sftp.FileMode
import net.schmizz.sshj.sftp.OpenMode
import net.schmizz.sshj.sftp.RemoteFile
import net.schmizz.sshj.sftp.RemoteResourceInfo
import net.schmizz.sshj.sftp.Response
import net.schmizz.sshj.sftp.SFTPClient
import net.schmizz.sshj.sftp.SFTPException
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import net.schmizz.sshj.userauth.keyprovider.KeyProvider
import net.schmizz.sshj.userauth.password.PasswordUtils
import java.io.FilterInputStream
import java.io.FilterOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.security.PublicKey
import java.util.EnumSet

/**
 * SFTP over one long-lived SSH connection per server. The connection is opened lazily, kept alive and
 * transparently re-established once if it dropped, so browsing never pays a handshake per directory.
 */
class SftpFs(
    private val config: ServerConfig,
    private val secrets: SecretStore,
    private val knownHosts: KnownHosts,
) : Vfs {
    private val mutex = Mutex()
    private var ssh: SSHClient? = null
    private var sftp: SFTPClient? = null

    private var homeDir: String? = null

    /** The directory a fresh tab on this server starts in. */
    suspend fun home(): String = config.startPath.ifBlank { null } ?: homeDir()

    /** The login user's home directory on the server. */
    suspend fun homeDir(): String = homeDir ?: withSftp { it.canonicalize(".") }.also { homeDir = it }

    override suspend fun list(path: String): List<FileEntry> = withSftp { s ->
        s.ls(path).map { it.toEntry(s, path) }
    }

    override suspend fun stat(path: String): FileEntry? = withSftp { s ->
        try {
            val a = s.stat(path)
            FileEntry(path.substringAfterLast('/'), a.type == FileMode.Type.DIRECTORY, a.size, a.mtime * 1000)
        } catch (e: SFTPException) {
            if (e.statusCode == Response.StatusCode.NO_SUCH_FILE) null else throw e
        }
    }

    override suspend fun mkdir(path: String) = withSftp { it.mkdir(path) }

    override suspend fun rename(from: String, to: String) = withSftp { it.rename(from, to) }

    override suspend fun deleteOne(path: String, isDir: Boolean) = withSftp { if (isDir) it.rmdir(path) else it.rm(path) }

    override suspend fun openRead(path: String, offset: Long): InputStream = withSftp { s ->
        val file = s.open(path, EnumSet.of(OpenMode.READ))
        // Read-ahead keeps several requests in flight, which is what makes SFTP fast on high-latency links.
        object : FilterInputStream(file.ReadAheadRemoteFileInputStream(16, offset)) {
            override fun close() {
                try { super.close() } finally { file.close() }
            }
        }
    }

    override suspend fun openWrite(path: String): OutputStream = withSftp { s ->
        val file: RemoteFile = s.open(path, EnumSet.of(OpenMode.WRITE, OpenMode.CREAT, OpenMode.TRUNC))
        object : FilterOutputStream(file.RemoteFileOutputStream(0, 32)) {
            // One SFTP write request per call: OpenSSH drops the whole connection for packets over
            // 256 KiB, so large buffers are split into the 32 KiB chunks every server accepts.
            override fun write(b: ByteArray, off: Int, len: Int) {
                var pos = off
                val end = off + len
                while (pos < end) {
                    val n = minOf(WRITE_CHUNK, end - pos)
                    out.write(b, pos, n)
                    pos += n
                }
            }
            override fun close() {
                try { super.close() } finally { file.close() }
            }
        }
    }

    override suspend fun setModified(path: String, millis: Long) = withSftp {
        val seconds = millis / 1000
        it.setattr(path, net.schmizz.sshj.sftp.FileAttributes.Builder().withAtimeMtime(seconds, seconds).build())
    }

    override suspend fun permissions(path: String): Int? = withSftp { it.stat(path).mode.permissionsMask }

    override suspend fun setPermissions(path: String, mode: Int) = withSftp { it.chmod(path, mode) }

    suspend fun disconnect() = mutex.withLock {
        runCatching { sftp?.close() }
        runCatching { ssh?.disconnect() }
        sftp = null
        ssh = null
    }

    private suspend fun <T> withSftp(block: (SFTPClient) -> T): T = withContext(Dispatchers.IO) {
        try {
            block(client())
        } catch (e: IOException) {
            // SFTP status errors (no such file, permission denied) are real answers; anything else
            // most likely means the connection died while we were idle, so reconnect once.
            if (e is SFTPException || e is UnknownHostKeyException || e is HostKeyMismatchException) throw e
            disconnect()
            block(client())
        }
    }

    private suspend fun client(): SFTPClient = mutex.withLock {
        val s = sftp
        val c = ssh
        if (s != null && c != null && c.isConnected && c.isAuthenticated) return s
        runCatching { c?.disconnect() }
        connect().also { sftp = it }
    }

    private fun connect(): SFTPClient {
        val verifier = Verifier()
        val c = SSHClient(DefaultConfig())
        c.addHostKeyVerifier(verifier)
        c.connectTimeout = 10_000
        c.timeout = 30_000
        try {
            c.connect(config.host, config.port)
        } catch (e: SSHException) {
            verifier.problem?.let { throw it }
            throw e
        }
        c.connection.keepAlive.keepAliveInterval = 20

        val keyText = secrets.get(SecretStore.privateKey(config.id))
        val password = secrets.get(SecretStore.password(config.id))
        try {
            val methods = buildList<() -> Unit> {
                if (keyText != null) add {
                    val passphrase = secrets.get(SecretStore.passphrase(config.id))
                    val key: KeyProvider = c.loadKeys(keyText, null, passphrase?.let { PasswordUtils.createOneOff(it.toCharArray()) })
                    c.authPublickey(config.user, key)
                }
                if (password != null) add { c.authPassword(config.user, password) }
            }
            if (methods.isEmpty()) throw IOException(Res.str(R.string.err_no_credentials))
            var last: Exception? = null
            for (m in methods) {
                try {
                    m()
                    break
                } catch (e: Exception) {
                    last = e
                }
            }
            if (!c.isAuthenticated) throw IOException(Res.str(R.string.err_login_failed_detail, last?.message.toString()), last)
            ssh = c
            return c.newSFTPClient()
        } catch (e: Exception) {
            runCatching { c.disconnect() }
            throw e
        }
    }

    private fun RemoteResourceInfo.toEntry(s: SFTPClient, dir: String): FileEntry {
        val a = attributes
        val isLink = a.type == FileMode.Type.SYMLINK
        // Symlinks need a second round trip to learn whether they point at a directory.
        val target = if (isLink) runCatching { s.stat(if (dir.endsWith("/")) dir + name else "$dir/$name") }.getOrNull() else null
        val isDir = (target ?: a).type == FileMode.Type.DIRECTORY
        return FileEntry(name, isDir, if (isDir) 0 else (target ?: a).size, (target ?: a).mtime * 1000, isLink)
    }

    private companion object {
        const val WRITE_CHUNK = 32 * 1024
    }

    private inner class Verifier : HostKeyVerifier {
        var problem: IOException? = null

        override fun verify(hostname: String, port: Int, key: PublicKey): Boolean {
            val type = KeyType.fromKey(key).toString()
            val blobBytes = Buffer.PlainBuffer().putPublicKey(key).compactData
            val blob = Base64.encodeToString(blobBytes, Base64.NO_WRAP)
            val fingerprint = "SHA256:" + Base64.encodeToString(
                MessageDigest.getInstance("SHA-256").digest(blobBytes), Base64.NO_WRAP or Base64.NO_PADDING,
            )
            val known = knownHosts.get(config.host, config.port)
            return when {
                known == null -> {
                    problem = UnknownHostKeyException(config.host, config.port, type, fingerprint, blob); false
                }
                known.second == blob -> true
                else -> {
                    problem = HostKeyMismatchException(config.host, fingerprint); false
                }
            }
        }

        // Letting sshj negotiate freely; its preference order is stable, so a server keeps presenting the same key.
        override fun findExistingAlgorithms(hostname: String, port: Int): List<String> = emptyList()
    }
}
