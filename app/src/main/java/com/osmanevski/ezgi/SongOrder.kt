package com.osmanevski.ezgi

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.text.Collator
import java.util.Locale

/** Şarkılar sekmesinin sıralama türleri (Spotify'daki gibi). */
enum class SortMode(val label: String) {
    ADDED("Son eklenen"),
    TITLE("Şarkı adı"),
    ARTIST("Sanatçı"),
    ALBUM("Albüm"),
    CUSTOM("Özel sıra"),
}

/** Seçili sıralama türü ayarlarda, elle verilen sıra `sira.json`'da (şarkı kimlikleri). */
class SongOrderStore(context: Context) {
    private val prefs = context.getSharedPreferences("ayarlar", Context.MODE_PRIVATE)
    private val file = File(context.filesDir, "sira.json")

    var mode: SortMode
        get() = runCatching { SortMode.valueOf(prefs.getString("siralama", null)!!) }.getOrDefault(SortMode.TITLE)
        set(v) { prefs.edit().putString("siralama", v.name).apply() }

    suspend fun loadOrder(): List<Long> = withContext(Dispatchers.IO) {
        runCatching { Json.decodeFromString<List<Long>>(file.readText()) }.getOrDefault(emptyList())
    }

    suspend fun saveOrder(ids: List<Long>) = withContext(Dispatchers.IO) {
        val tmp = File(file.parentFile, "sira.json.tmp")
        tmp.writeText(Json.encodeToString(ids))
        if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
    }
}

private val trCollator = Collator.getInstance(Locale.forLanguageTag("tr")).apply { strength = Collator.PRIMARY }

/**
 * Özel sırada listede olmayan (yeni inen) şarkılar en üste, en yeni önce gelir;
 * silinmiş şarkıların kimlikleri yok sayılır.
 */
fun sortSongs(songs: List<Song>, mode: SortMode, custom: List<Long>): List<Song> = when (mode) {
    SortMode.ADDED -> songs.sortedByDescending { it.dateAdded }
    SortMode.TITLE -> songs.sortedWith { a, b -> trCollator.compare(a.title, b.title) }
    SortMode.ARTIST -> songs.sortedWith { a, b ->
        trCollator.compare(a.artist, b.artist).takeIf { it != 0 } ?: trCollator.compare(a.title, b.title)
    }
    SortMode.ALBUM -> songs.sortedWith { a, b ->
        trCollator.compare(a.album, b.album).takeIf { it != 0 } ?: trCollator.compare(a.title, b.title)
    }
    SortMode.CUSTOM -> {
        val pos = custom.withIndex().associate { it.value to it.index }
        val (known, fresh) = songs.partition { it.id in pos }
        fresh.sortedByDescending { it.dateAdded } + known.sortedBy { pos.getValue(it.id) }
    }
}
