package com.carteltv.tv.voice

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.carteltv.tv.AppScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/** Downloads and manages the fully-free offline Vosk STT model (Apache 2.0). */
object VoskModelManager {
    const val MODEL_NAME = "vosk-model-small-en-us-0.15"
    const val MODEL_URL = "https://alphacephei.com/vosk/models/$MODEL_NAME.zip"

    /** The row's caption once the offline model is ready — the settings page and this manager share the wording. */
    const val INSTALLED_DETAIL = "Offline voice model installed"
    private const val MODEL_SIZE_BYTES = 40_000_000L // approximate, for progress UI

    enum class State { NOT_INSTALLED, DOWNLOADING, READY, ERROR }

    var state by mutableStateOf(State.NOT_INSTALLED)
        private set
    var progress by mutableFloatStateOf(0f)
        private set
    var detail by mutableStateOf("")
        private set

    @Volatile private var downloadJob: Job? = null
    private val lock = Any()
    @Volatile private var cachedModel: Model? = null
    @Volatile private var cachedModelPath: String? = null
    private val modelLock = Any()

    fun modelDir(ctx: Context): File = File(ctx.applicationContext.filesDir, "voice/models/$MODEL_NAME")

    fun isReady(ctx: Context): Boolean = isModelDir(modelDir(ctx))

    fun refresh(ctx: Context) {
        val ready = isReady(ctx)
        if (state != State.DOWNLOADING) {
            state = if (ready) State.READY else State.NOT_INSTALLED
            if (ready) detail = INSTALLED_DETAIL
            else if (state == State.NOT_INSTALLED) detail = ""
        }
    }

    fun download(ctx: Context) = synchronized(lock) {
        if (downloadJob?.isActive == true) return@synchronized
        val app = ctx.applicationContext
        downloadJob = AppScope.io.launch {
            state = State.DOWNLOADING
            progress = 0f
            detail = "Downloading offline voice model…"
            try {
                val root = File(app.filesDir, "voice/models").apply { mkdirs() }
                val tmpZip = File(root, "$MODEL_NAME.zip.tmp")
                val tmpDir = File(root, "$MODEL_NAME.tmp")
                tmpZip.delete()
                tmpDir.deleteRecursively()
                downloadTo(tmpZip)
                detail = "Installing offline voice model…"
                unzip(tmpZip, tmpDir)
                tmpZip.delete()
                val installed = findModelRoot(tmpDir) ?: throw IllegalStateException("Downloaded model is missing required files")
                val target = modelDir(app)
                target.deleteRecursively()
                if (!installed.renameTo(target)) {
                    installed.copyRecursively(target, overwrite = true)
                    tmpDir.deleteRecursively()
                } else if (tmpDir.exists()) {
                    tmpDir.deleteRecursively()
                }
                state = State.READY
                progress = 1f
                detail = INSTALLED_DETAIL
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                state = if (isReady(app)) State.READY else State.ERROR
                detail = "Offline model download failed: ${e.message ?: e.javaClass.simpleName}"
            }
        }
    }

    fun cancelDownload(ctx: Context) = synchronized(lock) {
        downloadJob?.cancel()
        downloadJob = null
        val app = ctx.applicationContext
        File(app.filesDir, "voice/models/$MODEL_NAME.zip.tmp").delete()
        File(app.filesDir, "voice/models/$MODEL_NAME.tmp").deleteRecursively()
        refresh(app)
        if (state == State.NOT_INSTALLED) detail = "Download cancelled"
    }

    fun remove(ctx: Context) {
        cancelDownload(ctx)
        closeModel()
        modelDir(ctx.applicationContext).deleteRecursively()
        refresh(ctx.applicationContext)
        detail = "Offline voice model removed"
    }

    fun getModel(ctx: Context): Model? = synchronized(modelLock) {
        val dir = modelDir(ctx.applicationContext)
        if (!isModelDir(dir)) return null
        val path = dir.absolutePath
        val existing = cachedModel
        if (existing != null && cachedModelPath == path) return existing
        existing?.close()
        runCatching { LibVosk.setLogLevel(LogLevel.WARNINGS) }
        val loaded = runCatching { Model(path) }.getOrNull()
        cachedModel = loaded
        cachedModelPath = if (loaded != null) path else null
        loaded
    }

    fun closeModel() = synchronized(modelLock) {
        cachedModel?.close()
        cachedModel = null
        cachedModelPath = null
    }

    private fun isModelDir(dir: File): Boolean =
        dir.isDirectory && (File(dir, "am/final.mdl").exists() || File(dir, "conf/model.conf").exists())

    private fun findModelRoot(dir: File): File? {
        if (isModelDir(dir)) return dir
        val children = dir.listFiles()?.filter { it.isDirectory } ?: return null
        return children.firstNotNullOfOrNull { findModelRoot(it) }
    }

    private suspend fun downloadTo(target: File) = withContext(Dispatchers.IO) {
        val conn = (URL(MODEL_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            setRequestProperty("Accept", "application/octet-stream")
        }
        try {
            conn.connect()
            val code = conn.responseCode
            if (code !in 200..299) throw IllegalStateException("HTTP $code")
            val total = conn.contentLengthLong.takeIf { it > 0 } ?: MODEL_SIZE_BYTES
            BufferedInputStream(conn.inputStream).use { input ->
                FileOutputStream(target).use { output ->
                    val buf = ByteArray(DEFAULT_BUFFER_SIZE)
                    var readTotal = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        readTotal += n
                        progress = (readTotal.toFloat() / total.toFloat()).coerceIn(0f, 0.99f)
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
    }

    private suspend fun unzip(zip: File, destDir: File) = withContext(Dispatchers.IO) {
        destDir.mkdirs()
        val canonicalDest = destDir.canonicalFile
        ZipInputStream(BufferedInputStream(zip.inputStream())).use { zis ->
            while (true) {
                val entry = zis.nextEntry ?: break
                val out = File(destDir, entry.name)
                val canonicalOut = out.canonicalFile
                if (!canonicalOut.path.startsWith(canonicalDest.path + File.separator) && canonicalOut != canonicalDest) {
                    throw SecurityException("Bad zip entry: ${entry.name}")
                }
                if (entry.isDirectory) {
                    out.mkdirs()
                } else {
                    out.parentFile?.mkdirs()
                    FileOutputStream(out).use { zis.copyTo(it) }
                }
                zis.closeEntry()
            }
        }
    }
}
