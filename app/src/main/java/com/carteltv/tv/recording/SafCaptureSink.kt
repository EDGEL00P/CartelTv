package com.carteltv.tv.recording

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream

/**
 * SAF recording target (MainActivity 4713
 * folder-picker plumbing): a [CaptureSink] writing into a persisted
 * document-tree URI (ACTION_OPEN_DOCUMENT_TREE grant with
 * takePersistableUriPermission). Free-space comes from the provider's
 * COLUMN_AVAILABLE_BYTES; MAX_VALUE when the provider won't say.
 *
 * treeUri arrives as an opaque string ("content://.../tree/..."); the
 * picker UI lane will pass it through, same destiny as the SMB target.
 */
class SafCaptureSink(context: Context, private val treeUriStr: String, fileName: String) : CaptureSink {

    private val resolver = context.contentResolver
    private val treeUri: Uri = Uri.parse(treeUriStr)
    private val docUri: Uri =
        DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri))
    private val target: Uri? = try {
        // Create fails harmlessly if the name exists (FILE_OVERWRITE vibe varies
        // by provider; the created duplicate is acceptable v1 behavior).
        DocumentsContract.createDocument(resolver, docUri, "video/mp2t", fileName)
    } catch (_: Exception) { null }
    override val displayPath: String = "$treeUriStr/$fileName"
    private var pfd: ParcelFileDescriptor? = null
    private var out: OutputStream? = null

    override fun bytesSoFar(): Long = written
    private var written = 0L

    override fun freeBytes(): Long = try {
        // Storage-volume fallback (some providers don't answer per-doc queries):
        // DOCUMENT_ROOT_URI path… simplest reliable form: query the ROOT doc,
        // COLUMN_AVAILABLE_BYTES via projection string literal (constant name
        // differs across API levels).
        resolver.query(
            docUri,
            arrayOf("available_bytes"),
            null, null, null,
        )?.use { c ->
            val idx = c.getColumnIndex("available_bytes")
            if (idx >= 0 && c.moveToFirst()) c.getLong(idx) else Long.MAX_VALUE
        } ?: Long.MAX_VALUE
    } catch (_: Exception) { Long.MAX_VALUE }

    override fun open(): OutputStream {
        val uri = target ?: throw java.io.IOException("SAF document create failed: $displayPath")
        // "rwt" truncates at open; wrapping pfd.fileDescriptor in a FileOutputStream would close its fd.
        pfd = resolver.openFileDescriptor(uri, "rwt")
            ?: throw java.io.IOException("SAF open failed: $displayPath")
        val stream = resolver.openOutputStream(uri, "rw")
            ?: throw java.io.IOException("SAF stream failed: $displayPath")
        return stream.let { inner ->
            object : OutputStream() {
                override fun write(b: Int) { inner.write(b); written++ }
                override fun write(b: ByteArray) { inner.write(b); written += b.size }
                override fun write(b: ByteArray, off: Int, len: Int) { inner.write(b, off, len); written += len }
                override fun flush() = inner.flush()
                override fun close() = inner.close()
            }.also { out = it }
        }
    }

    override fun close() {
        try { out?.flush() } catch (_: Exception) {}
        try { out?.close() } catch (_: Exception) {}
        try { pfd?.close() } catch (_: Exception) {}
        out = null; pfd = null
    }
}
