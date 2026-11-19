package com.buct.course

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.buct.one.data.model.TheolDownloadedFile
import java.io.File
import java.util.Locale

internal object TheolAttachmentOpener {
    fun open(context: Context, file: TheolDownloadedFile): Boolean {
        val target = File(file.filePath)
        if (!target.exists() || !target.isFile) return false
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            target
        )
        val mime = file.mimeType.ifBlank { "*/*" }
        val viewIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(viewIntent)
            true
        } catch (_: ActivityNotFoundException) {
            share(context, file)
        } catch (_: SecurityException) {
            share(context, file)
        }
    }

    fun share(context: Context, file: TheolDownloadedFile): Boolean {
        val target = File(file.filePath)
        if (!target.exists() || !target.isFile) return false
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            target
        )
        val mime = file.mimeType.ifBlank { "*/*" }
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, file.fileName)
            putExtra(Intent.EXTRA_TITLE, file.fileName)
            clipData = ClipData.newRawUri(file.fileName, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(sendIntent, "打开或保存「${file.fileName}」").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(chooser)
            true
        } catch (_: ActivityNotFoundException) {
            false
        }
    }

    fun formatFileSize(bytes: Long): String {
        if (bytes < 0L) return "未知大小"
        if (bytes < 1024L) return "${bytes} B"
        val kb = bytes / 1024.0
        if (kb < 1024.0) return String.format(Locale.US, "%.1f KB", kb)
        val mb = kb / 1024.0
        return String.format(Locale.US, "%.1f MB", mb)
    }
}
