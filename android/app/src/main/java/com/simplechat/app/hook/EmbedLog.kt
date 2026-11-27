package com.simplechat.app.hook

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import mychat.framework.log.LogSink
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 日志落点：`Download/mychat/<prefix>-yyyy-MM-dd-HHmmss.log`。
 *
 * 一次启动一个文件。**URI 只解析一次**（openSession），之后每次 append 复用——
 * 早先是每次写入都按名字查一次，查不到就 insert，ROM 把 RELATIVE_PATH 存成带尾斜杠时
 * 每次都"查不到"，于是每行日志生成了一个新文件。
 */
object EmbedLog {

    const val DIR = "mychat"

    /** 每个前缀保留几个文件。 */
    const val MAX_FILES = 10

    fun stamp(): String =
        SimpleDateFormat("yyyy-MM-dd-HHmmss", Locale.US).format(Date())

    fun timeStamp(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())

    /** 解析（或创建）一次落点，返回可复用的追加器。 */
    fun openSession(context: Context, prefix: String): LogSink {
        val name = prefix + "-" + stamp() + ".log"
        val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching { resolveOrCreate(context, name) }.getOrNull()
        } else {
            null
        }
        val fallback = File(externalDir(context), name)
        return LogSink { text ->
            val bytes = text.toByteArray()
            val written = uri != null && runCatching {
                context.contentResolver.openOutputStream(uri, "wa")?.use { it.write(bytes) }
                    ?: error("no stream")
            }.isSuccess
            if (!written) fallback.appendBytes(bytes)
        }
    }

    /** 删掉同名前缀里最旧的那些，保留 [MAX_FILES] 个。 */
    fun prune(context: Context, prefix: String) {
        runCatching {
            val names = names(context, prefix).sorted()
            if (names.size <= MAX_FILES) return
            val stale = names.dropLast(MAX_FILES)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) deleteFromDownloads(context, stale)
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

    private fun queryDownloads(context: Context, prefix: String): List<String> =
        context.contentResolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.MediaColumns.DISPLAY_NAME),
            "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?",
            arrayOf("$prefix%"),
            null,
        )?.use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
        }.orEmpty()

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

    /**
     * 按名字找同一目录下的条目；找到多个（历史遗留的重复）就留一个。
     * RELATIVE_PATH 用 LIKE，兼容 ROM 存成 `Download/mychat/` 的情况。
     */
    private fun resolveOrCreate(context: Context, name: String): Uri {
        val resolver = context.contentResolver
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val relative = "${Environment.DIRECTORY_DOWNLOADS}/$DIR"

        val ids = resolver.query(
            collection,
            arrayOf(MediaStore.MediaColumns._ID),
            "${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND " +
                "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?",
            arrayOf(name, "$relative%"),
            null,
        )?.use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getLong(0)) }
        }.orEmpty()

        if (ids.isNotEmpty()) {
            // 清掉重复的历史条目，只保留第一个
            ids.drop(1).forEach { extra ->
                runCatching { resolver.delete(ContentUris.withAppendedId(collection, extra), null, null) }
            }
            return ContentUris.withAppendedId(collection, ids.first())
        }

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
            put(MediaStore.MediaColumns.RELATIVE_PATH, relative)
        }
        return resolver.insert(collection, values) ?: error("insert failed")
    }
}
