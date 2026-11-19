package com.buct.course.manager

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import java.io.File
import java.io.FileOutputStream

/**
 * 「我的」个人资料本地存储（按账号隔离，纯本地落盘，零网络上报）：
 * - 自定义展示昵称（留空回落教务真实姓名）
 * - 自定义个性签名
 * - 本地裁剪压缩后的正方形头像（<= 512px JPEG）
 */
data class UserProfile(
    val nickname: String = "",
    val signature: String = "",
    val avatarPath: String = "",
    val avatarUpdatedAt: Long = 0L
)

object UserProfileStore {
    private const val PREFS_NAME = "user_profile_prefs"
    private const val AVATAR_DIR = "profile_avatars"
    private const val MAX_AVATAR_PX = 512

    private fun safeKey(accountKey: String): String =
        accountKey.trim().ifBlank { "default" }.replace(Regex("[^A-Za-z0-9_.-]"), "_")

    fun load(context: Context, accountKey: String): UserProfile {
        val key = safeKey(accountKey)
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val rawPath = prefs.getString("avatar_path_$key", "").orEmpty()
        val validPath = if (rawPath.isNotBlank() && File(rawPath).isFile) rawPath else ""
        return UserProfile(
            nickname = prefs.getString("nickname_$key", "").orEmpty(),
            signature = prefs.getString("signature_$key", "").orEmpty(),
            avatarPath = validPath,
            avatarUpdatedAt = prefs.getLong("avatar_ts_$key", 0L)
        )
    }

    fun saveText(context: Context, accountKey: String, nickname: String, signature: String): UserProfile {
        val key = safeKey(accountKey)
        val cleanNickname = nickname.trim().take(16)
        val cleanSignature = signature.trim().take(60)
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString("nickname_$key", cleanNickname)
            .putString("signature_$key", cleanSignature)
            .apply()
        return load(context, accountKey)
    }

    fun saveAvatarFromUri(context: Context, accountKey: String, uri: Uri): UserProfile? {
        val bitmap = decodeScaledBitmap(context, uri, MAX_AVATAR_PX) ?: return null
        val square = centerCropSquare(bitmap, MAX_AVATAR_PX)
        val dir = File(context.filesDir, AVATAR_DIR).apply { mkdirs() }
        val key = safeKey(accountKey)
        val outFile = File(dir, "avatar_$key.jpg")
        val tempFile = File(dir, "avatar_$key.tmp")

        return try {
            FileOutputStream(tempFile).use { fos ->
                square.compress(Bitmap.CompressFormat.JPEG, 88, fos)
                fos.flush()
            }
            if (outFile.exists()) outFile.delete()
            if (!tempFile.renameTo(outFile)) {
                tempFile.copyTo(outFile, overwrite = true)
                tempFile.delete()
            }
            val now = System.currentTimeMillis()
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString("avatar_path_$key", outFile.absolutePath)
                .putLong("avatar_ts_$key", now)
                .apply()
            load(context, accountKey)
        } catch (_: Exception) {
            tempFile.delete()
            null
        } finally {
            if (square !== bitmap) bitmap.recycle()
            square.recycle()
        }
    }

    fun clearAvatar(context: Context, accountKey: String): UserProfile {
        val key = safeKey(accountKey)
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val oldPath = prefs.getString("avatar_path_$key", "").orEmpty()
        if (oldPath.isNotBlank()) {
            runCatching { File(oldPath).delete() }
        }
        prefs.edit()
            .remove("avatar_path_$key")
            .putLong("avatar_ts_$key", System.currentTimeMillis())
            .apply()
        return load(context, accountKey)
    }

    private fun decodeScaledBitmap(context: Context, uri: Uri, maxDim: Int): Bitmap? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val source = ImageDecoder.createSource(context.contentResolver, uri)
                ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    val w = info.size.width
                    val h = info.size.height
                    if (w > 0 && h > 0) {
                        val maxSide = maxOf(w, h)
                        if (maxSide > maxDim) {
                            val ratio = maxDim.toFloat() / maxSide.toFloat()
                            decoder.setTargetSize(
                                (w * ratio).toInt().coerceAtLeast(1),
                                (h * ratio).toInt().coerceAtLeast(1)
                            )
                        }
                    }
                }
            } else {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    BitmapFactory.decodeStream(stream, null, bounds)
                }
                var sample = 1
                while (bounds.outWidth / sample > maxDim * 2 || bounds.outHeight / sample > maxDim * 2) {
                    sample *= 2
                }
                val opts = BitmapFactory.Options().apply {
                    inSampleSize = sample
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    BitmapFactory.decodeStream(stream, null, opts)
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun centerCropSquare(src: Bitmap, targetPx: Int): Bitmap {
        val size = minOf(src.width, src.height)
        val x = (src.width - size) / 2
        val y = (src.height - size) / 2
        val cropped = Bitmap.createBitmap(src, x, y, size, size)
        if (size <= targetPx) return cropped
        val scaled = Bitmap.createScaledBitmap(cropped, targetPx, targetPx, true)
        if (scaled !== cropped) cropped.recycle()
        return scaled
    }
}
