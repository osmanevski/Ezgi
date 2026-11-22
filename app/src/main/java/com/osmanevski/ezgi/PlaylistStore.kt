package com.osmanevski.ezgi

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class Playlist(val id: String, val name: String, val songIds: List<Long> = emptyList())

/** Listeler uygulamanın kendi klasöründe tek bir JSON dosyası. Yazma önce geçici dosyaya, sonra yer değiştirme. */
class PlaylistStore(context: Context) {
    private val file = File(context.filesDir, "listeler.json")
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    suspend fun load(): List<Playlist> = withContext(Dispatchers.IO) {
        if (!file.exists()) return@withContext emptyList()
        runCatching { json.decodeFromString<List<Playlist>>(file.readText()) }.getOrDefault(emptyList())
    }

    suspend fun save(lists: List<Playlist>) = withContext(Dispatchers.IO) {
        val tmp = File(file.parentFile, "listeler.json.tmp")
        tmp.writeText(json.encodeToString(lists))
        if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
    }
}
