package com.xjyzs.operator.utils.data

import java.io.File
import java.util.UUID

object ScreenshotStore {
    private val dir by lazy { File(SharedState.appContext.filesDir, "screenshots").apply { mkdirs() } }

    fun save(base64: String): String {
        val file = File(dir, "${UUID.randomUUID()}.jpg")
        val data = base64.substringAfter("base64,")
        file.writeBytes(android.util.Base64.decode(data, android.util.Base64.DEFAULT))
        return file.absolutePath
    }

    fun readDataUrl(path: String): String? {
        val file = File(path)
        if (!file.exists()) return null
        val b64 = android.util.Base64.encodeToString(file.readBytes(), android.util.Base64.NO_WRAP)
        return "data:image/jpeg;base64,${b64}"
    }

    fun keepLatest(count: Int) {
        val files = dir.listFiles()?.sortedByDescending { it.lastModified() } ?: return
        files.drop(count).forEach { it.delete() }
    }
}
