package io.github.carnager.commander.vfs

import io.github.carnager.commander.R
import io.github.carnager.commander.Res
import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.mserref.NtStatus
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.mssmb2.SMBApiException
import com.hierynomus.protocol.commons.EnumWithValue.EnumUtils
import com.hierynomus.security.bc.BCSecurityProvider
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.common.SMBRuntimeException
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import com.rapid7.client.dcerpc.mssrvs.ServerService
import com.rapid7.client.dcerpc.transport.SMBTransportFactories
import io.github.carnager.commander.data.SecretStore
import io.github.carnager.commander.data.ServerConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.FilterOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.EnumSet
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * SMB2/3 via smbj. Paths are "/share/dir/file"; "/" lists the server's disk shares.
 * Like [SftpFs], one authenticated session is kept and re-established once when it dropped.
 */
class SmbFs(
    private val config: ServerConfig,
    private val secrets: SecretStore,
) : Vfs {
    private val mutex = Mutex()
    private var client: SMBClient? = null
    private var connection: Connection? = null
    private var session: Session? = null
    private val shares = ConcurrentHashMap<String, DiskShare>()

    fun home(): String = config.startPath.ifBlank { "/" }

    override suspend fun list(path: String): List<FileEntry> = withSmb { s ->
        val (shareName, inner) = split(path)
        if (shareName.isEmpty()) return@withSmb listShares(s)
        share(s, shareName).list(inner)
            .filter { it.fileName != "." && it.fileName != ".." }
            .map {
                val attrs = it.fileAttributes
                val isDir = EnumUtils.isSet(attrs, FileAttributes.FILE_ATTRIBUTE_DIRECTORY)
                FileEntry(
                    name = it.fileName,
                    isDir = isDir,
                    size = if (isDir) 0 else it.endOfFile,
                    modified = it.lastWriteTime.toEpochMillis(),
                    hiddenAttr = EnumUtils.isSet(attrs, FileAttributes.FILE_ATTRIBUTE_HIDDEN),
                )
            }
    }

    override suspend fun stat(path: String): FileEntry? = withSmb { s ->
        val (shareName, inner) = split(path)
        if (inner.isEmpty()) return@withSmb FileEntry(shareName.ifEmpty { "/" }, true, 0, 0)
        try {
            val info = share(s, shareName).getFileInformation(inner)
            val isDir = info.standardInformation.isDirectory
            FileEntry(
                path.substringAfterLast('/'), isDir,
                if (isDir) 0 else info.standardInformation.endOfFile,
                info.basicInformation.lastWriteTime.toEpochMillis(),
            )
        } catch (e: SMBApiException) {
            if (e.status == NtStatus.STATUS_OBJECT_NAME_NOT_FOUND || e.status == NtStatus.STATUS_OBJECT_PATH_NOT_FOUND) null else throw e
        }
    }

    override suspend fun mkdir(path: String) = withSmb { s ->
        val (shareName, inner) = split(path)
        share(s, shareName).mkdir(inner)
    }

    /** SMB can only rename within one share; anything else has to be copied. */
    override fun canRename(from: String, to: String): Boolean {
        val a = split(from)
        val b = split(to)
        return a.first.isNotEmpty() && a.first == b.first && a.second.isNotEmpty() && b.second.isNotEmpty()
    }

    override suspend fun rename(from: String, to: String) = withSmb { s ->
        val (shareName, inner) = split(from)
        val target = split(to).second
        share(s, shareName).open(
            inner, EnumSet.of(AccessMask.DELETE, AccessMask.FILE_READ_ATTRIBUTES), null,
            SMB2ShareAccess.ALL, SMB2CreateDisposition.FILE_OPEN, null,
        ).use { it.rename(target, false) }
    }

    override suspend fun deleteOne(path: String, isDir: Boolean) = withSmb { s ->
        val (shareName, inner) = split(path)
        if (isDir) share(s, shareName).rmdir(inner, false) else share(s, shareName).rm(inner)
    }

    override suspend fun openRead(path: String, offset: Long): InputStream = withSmb { s ->
        val (shareName, inner) = split(path)
        val file = share(s, shareName).openFile(
            inner, EnumSet.of(AccessMask.GENERIC_READ), null,
            SMB2ShareAccess.ALL, SMB2CreateDisposition.FILE_OPEN, null,
        )
        // Positional reads in large chunks; SMB2 servers answer reads of a megabyte or more at once.
        object : InputStream() {
            private var pos = offset
            private val one = ByteArray(1)
            override fun read(): Int = if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xff
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                val n = file.read(b, pos, off, minOf(len, READ_CHUNK))
                if (n <= 0) return -1
                pos += n
                return n
            }
            override fun close() = file.close()
        }
    }

    override suspend fun openWrite(path: String): OutputStream = withSmb { s ->
        val (shareName, inner) = split(path)
        val file = share(s, shareName).openFile(
            inner, EnumSet.of(AccessMask.GENERIC_WRITE), null,
            SMB2ShareAccess.ALL, SMB2CreateDisposition.FILE_OVERWRITE_IF, null,
        )
        object : FilterOutputStream(file.outputStream) {
            override fun write(b: ByteArray, off: Int, len: Int) = out.write(b, off, len)
            override fun close() {
                try { super.close() } finally { file.close() }
            }
        }
    }

    override suspend fun setModified(path: String, millis: Long) = withSmb { s ->
        val (shareName, inner) = split(path)
        if (inner.isEmpty()) return@withSmb
        val keep = com.hierynomus.msfscc.fileinformation.FileBasicInformation.DONT_SET
        share(s, shareName).setFileInformation(
            inner,
            com.hierynomus.msfscc.fileinformation.FileBasicInformation(keep, keep, com.hierynomus.msdtyp.FileTime.ofEpochMillis(millis), keep, 0),
        )
    }

    suspend fun disconnect() = mutex.withLock { closeQuietly() }

    private fun closeQuietly() {
        shares.values.forEach { runCatching { it.close() } }
        shares.clear()
        runCatching { session?.close() }
        runCatching { connection?.close() }
        runCatching { client?.close() }
        session = null
        connection = null
        client = null
    }

    private suspend fun <T> withSmb(block: (Session) -> T): T = withContext(Dispatchers.IO) {
        try {
            block(session())
        } catch (e: Exception) {
            // Status answers from the server are final; transport trouble gets one reconnect.
            if (e is SMBApiException || (e !is IOException && e !is SMBRuntimeException)) throw e
            mutex.withLock { closeQuietly() }
            block(session())
        }
    }

    private suspend fun session(): Session = mutex.withLock {
        val s = session
        if (s != null && connection?.isConnected == true) return s
        closeQuietly()
        val c = SMBClient(
            SmbConfig.builder()
                .withSecurityProvider(BCSecurityProvider())
                .withMultiProtocolNegotiate(true)
                .withDfsEnabled(false)
                .withTimeout(30, TimeUnit.SECONDS)
                .withSoTimeout(30, TimeUnit.SECONDS)
                .build()
        )
        try {
            val conn = c.connect(config.host, config.port)
            val password = secrets.get(SecretStore.password(config.id)).orEmpty()
            val auth = if (config.user.isBlank()) AuthenticationContext.anonymous()
            else AuthenticationContext(config.user, password.toCharArray(), config.domain.ifBlank { null })
            val newSession = conn.authenticate(auth)
            client = c
            connection = conn
            session = newSession
            newSession
        } catch (e: Exception) {
            runCatching { c.close() }
            throw if (e is SMBApiException && e.status == NtStatus.STATUS_LOGON_FAILURE) IOException(Res.str(R.string.err_login_failed)) else e
        }
    }

    private fun share(s: Session, name: String): DiskShare =
        shares.getOrPut(name) {
            s.connectShare(name) as? DiskShare ?: throw IOException(Res.str(R.string.err_not_a_file_share, name))
        }

    private fun listShares(s: Session): List<FileEntry> {
        val transport = SMBTransportFactories.SRVSVC.getTransport(s)
        return ServerService(transport).shares1
            // Disk shares only (type 0), and no administrative ones like C$ or IPC$.
            .filter { (it.type and 0x0FFFFFFF) == 0 && !it.netName.endsWith("$") }
            .map { FileEntry(it.netName, isDir = true, size = 0, modified = 0) }
    }

    private companion object {
        const val READ_CHUNK = 1024 * 1024
    }

    /** "/share/a/b" → ("share", "a\b"); "/" → ("", ""). */
    private fun split(path: String): Pair<String, String> {
        val p = path.trim('/')
        return p.substringBefore('/') to p.substringAfter('/', "").replace('/', '\\')
    }
}
