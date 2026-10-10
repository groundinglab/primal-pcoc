package net.primal.android.pcoc

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import io.github.aakira.napier.Napier
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.primal.android.pcoc.create.CreateUiState

internal const val PCOC_OBJECT_MIME = "application/cbor"
internal const val PCOC_LOG_MIME = "text/plain"

enum class PcocExportKind {
    Object,
    Log,
}

enum class PcocExportAction {
    Save,
    Share,
}

class PcocStoredExport(
    val displayName: String,
    val bytes: ByteArray,
)

internal fun objectExportName(path: String): String {
    val name = File(path).name
    return name.ifBlank { "pcoc.cbor" }
}

internal fun logExportName(objectPath: String): String = objectExportName(objectPath) + ".events"

internal fun shareFileName(displayName: String): String {
    val cleaned = displayName.replace(Regex("[^A-Za-z0-9._-]"), "_").trim('_')
    return cleaned.ifBlank { "pcoc.cbor" }.take(MAX_SHARE_NAME)
}

/** Object bytes, or the produce event log. The log file wins; the on-screen lines are the fallback. */
internal fun bytesForExport(
    kind: PcocExportKind,
    objectFile: File,
    logLines: List<String>,
): ByteArray? =
    when (kind) {
        PcocExportKind.Object -> objectFile.takeIf { it.isFile }?.readBytes()
        PcocExportKind.Log -> {
            val log = File(objectFile.path + ".events")
            when {
                log.isFile -> log.readBytes()
                logLines.isNotEmpty() -> logLines.joinToString("\n").toByteArray()
                else -> null
            }
        }
    }

internal fun stageShareFile(cacheDir: File, displayName: String, bytes: ByteArray): File {
    val dir = File(cacheDir, SHARE_DIRECTORY)
    dir.mkdirs()
    val file = File(dir, shareFileName(displayName))
    file.writeBytes(bytes)
    return file
}

internal fun shareIntent(uri: Uri, mimeType: String, title: String): Intent {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = mimeType
        putExtra(Intent.EXTRA_STREAM, uri)
        clipData = ClipData.newRawUri(title, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    return Intent.createChooser(send, title).apply {
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}

internal suspend fun writeExport(
    context: Context,
    state: CreateUiState,
    kind: PcocExportKind,
    uri: Uri,
) {
    val bytes = withContext(Dispatchers.IO) {
        bytesForExport(kind, File(state.outputPath), state.logLines)
    } ?: return
    withContext(Dispatchers.IO) {
        writeBytes(context, uri, bytes)
    }
}

internal suspend fun shareExport(context: Context, state: CreateUiState, kind: PcocExportKind) {
    val bytes = withContext(Dispatchers.IO) {
        bytesForExport(kind, File(state.outputPath), state.logLines)
    } ?: return
    val name = exportName(kind, state.outputPath)
    val mime = exportMime(kind)
    val file = withContext(Dispatchers.IO) { stageShareFile(context.cacheDir, name, bytes) }
    startShare(context, file, mime, name)
}

internal suspend fun writeStoredExport(context: Context, export: PcocStoredExport, uri: Uri) {
    withContext(Dispatchers.IO) {
        writeBytes(context, uri, export.bytes)
    }
}

internal suspend fun shareStoredExport(context: Context, export: PcocStoredExport) {
    val file = withContext(Dispatchers.IO) {
        stageShareFile(context.cacheDir, export.displayName, export.bytes)
    }
    startShare(context, file, PCOC_OBJECT_MIME, export.displayName)
}

private fun exportName(kind: PcocExportKind, objectPath: String): String =
    when (kind) {
        PcocExportKind.Object -> objectExportName(objectPath)
        PcocExportKind.Log -> logExportName(objectPath)
    }

private fun exportMime(kind: PcocExportKind): String =
    when (kind) {
        PcocExportKind.Object -> PCOC_OBJECT_MIME
        PcocExportKind.Log -> PCOC_LOG_MIME
    }

private fun writeBytes(context: Context, uri: Uri, bytes: ByteArray) {
    try {
        context.contentResolver.openOutputStream(uri)?.use { stream ->
            stream.write(bytes)
        }
    } catch (error: IOException) {
        Napier.w(error) { "Could not export the PCOC file." }
    } catch (error: SecurityException) {
        Napier.w(error) { "Could not export the PCOC file." }
    }
}

private fun startShare(context: Context, file: File, mime: String, title: String) {
    try {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
        val intent = shareIntent(uri, mime, title)
        if (context !is Activity) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    } catch (error: IllegalArgumentException) {
        Napier.w(error) { "Could not share the PCOC file." }
    } catch (error: ActivityNotFoundException) {
        Napier.w(error) { "Could not share the PCOC file." }
    }
}

private const val SHARE_DIRECTORY = "pcoc-export"
private const val MAX_SHARE_NAME = 80
