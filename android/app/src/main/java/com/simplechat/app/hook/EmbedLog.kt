package com.simplechat.app.hook

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 探针结果的落点：优先 `Download/mychat-embed.log`（MediaStore，Android 10+ 才允许），
 * 写不了就落到 app 的外部私有目录。返回最终路径，调用方负责打到日志里。
 */
object EmbedLog {

    private const val NAME = "mychat-embed.log"

    fun append(context: Context, lines: List<String>): String {
        val now = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        val body = buildString {
            for (line in lines) append('[').append(now).append("] ").append(line).append('\n')
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val path = writeToDownloads(context, body)
            if (path != null) return path
        }

        val dir = context.getExternalFilesDir(null) ?: context.filesDir
        val file = File(dir, NAME)
        file.appendText(body)
        return file.absolutePath
    }

    /** 同名文件存在就追加，不存在就新建；任何一步失败返回 null，交给调用方回退。 */
    private fun writeToDownloads(context: Context, body: String): String? = runCatching {
        val resolver = context.contentResolver
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI

        val existingId = resolver.query(
            collection,
            arrayOf(MediaStore.MediaColumns._ID),
            MediaStore.MediaColumns.DISPLAY_NAME + "=?",
            arrayOf(NAME),
            null,
        )?.use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else null }

        val uri: Uri = if (existingId != null) {
            ContentUris.withAppendedId(collection, existingId)
        } else {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, NAME)
                put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            resolver.insert(collection, values) ?: error("insert failed")
        }

        resolver.openOutputStream(uri, "wa")?.use { it.write(body.toByteArray()) }
            ?: error("openOutputStream failed")
        "Download/$NAME"
    }.getOrNull()
}
