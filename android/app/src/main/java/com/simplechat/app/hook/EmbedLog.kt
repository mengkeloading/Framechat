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
 * 探针日志的落点：`Download/mychat/<prefix>-yyyy-MM-dd-HHmmss.log`。
 *
 * 一次启动一个文件 —— 混在一个大文件里，翻"这次启动发生了什么"要靠时间戳猜。
 * 只保留最近 [MAX_FILES] 个，否则 Downloads 会慢慢堆满。
 */
object EmbedLog {

    const val DIR = "mychat"

    /** 每个前缀保留几个文件。 */
    const val MAX_FILES = 10

    fun stamp(): String =
        SimpleDateFormat("yyyy-MM-dd-HHmmss", Locale.US).format(Date())

    fun append(context: Context, lines: List<String>, name: String): String {
        val now = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        val body = buildString {
            for (line in lines) append('[').append(now).append("] ").append(line).append('\n')
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val path = writeToDownloads(context, body, name)
            if (path != null) return path
        }
        val dir = externalDir(context)
        val file = File(dir, name)
        file.appendText(body)
        return file.absolutePath
    }

    /** 删掉同名前缀里最旧的那些，保留 [MAX_FILES] 个。 */
    fun prune(context: Context, prefix: String) {
        runCatching {
            val names = names(context, prefix).sorted()
            if (names.size <= MAX_FILES) return
            val stale = names.dropLast(MAX_FILES)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                deleteFromDownloads(context, stale)
            }
            // 私有目录里的同名文件也清一遍（两种落点可能都在）
            for (name in stale) runCatching { File(externalDir(context), name).delete() }
        }
    }

    private fun names(context: Context, prefix: String): List<String> {
        val local = externalDir(context).listFiles()
            ?.filter { it.name.startsWith(prefix) && it.name.endsWith(".log") }
            ?.map { it.name }
            .orEmpty()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return local

        val fromStore = runCatching { queryDownloads(context, prefix) }.getOrDefault(emptyList())
        return (local + fromStore).distinct()
    }

    private fun queryDownloads(context: Context, prefix: String): List<String> {
        val resolver = context.contentResolver
        return resolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.MediaColumns.DISPLAY_NAME),
            "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? AND ${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?",
            arrayOf("${Environment.DIRECTORY_DOWNLOADS}/$DIR%", "$prefix%"),
            null,
        )?.use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursor.getString(0))
            }
        }.orEmpty()
    }

    private fun deleteFromDownloads(context: Context, names: List<String>) {
        val resolver = context.contentResolver
        for (name in names) {
            runCatching {
                val uri = resolver.query(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    arrayOf(MediaStore.MediaColumns._ID),
                    "${MediaStore.MediaColumns.DISPLAY_NAME} = ?",
                    arrayOf(name),
                    null,
                )?.use { cursor -> if (cursor.moveToFirst()) ContentUris.withAppendedId(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI, cursor.getLong(0)
                ) else null }
                if (uri != null) resolver.delete(uri, null, null)
            }
        }
    }

    private fun externalDir(context: Context): File {
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        return File(base, DIR).apply { mkdirs() }
    }

    /** 同名文件存在就追加，不存在就新建；任何一步失败返回 null，交给调用方回退。 */
    private fun writeToDownloads(context: Context, body: String, name: String): String? = runCatching {
        val resolver = context.contentResolver
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val relative = "${Environment.DIRECTORY_DOWNLOADS}/$DIR"

        val existingId = resolver.query(
            collection,
            arrayOf(MediaStore.MediaColumns._ID),
            "${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND ${MediaStore.MediaColumns.RELATIVE_PATH} = ?",
            arrayOf(name, relative),
            null,
        )?.use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else null }

        val uri: Uri = if (existingId != null) {
            ContentUris.withAppendedId(collection, existingId)
        } else {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                put(MediaStore.MediaColumns.RELATIVE_PATH, relative)
            }
            resolver.insert(collection, values) ?: error("insert failed")
        }

        resolver.openOutputStream(uri, "wa")?.use { it.write(body.toByteArray()) }
            ?: error("openOutputStream failed")
        "Download/$DIR/$name"
    }.getOrNull()
}
