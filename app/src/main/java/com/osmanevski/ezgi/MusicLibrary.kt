package com.osmanevski.ezgi

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.Collator
import java.util.Locale

data class Song(
    val id: Long,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val dateAdded: Long = 0, // saniye, MediaStore
) {
    val uri: Uri get() = songUri(id)
}

fun songUri(id: Long): Uri =
    ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)

/** Telefondaki müzikleri MediaStore'dan okur. Zil sesleri ve bildirim sesleri dışarıda kalır. */
object MusicLibrary {
    private const val MIN_DURATION_MS = 30_000L // WhatsApp sesli mesajı gibi kısa kayıtlar listeyi kirletmesin

    suspend fun load(context: Context): List<Song> = withContext(Dispatchers.IO) {
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.DATE_ADDED,
        )
        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0 AND ${MediaStore.Audio.Media.DURATION} >= ?"
        val songs = mutableListOf<Song>()
        context.contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            projection, selection, arrayOf(MIN_DURATION_MS.toString()),
            null, // MediaStore yeni sürümlerde "COLLATE LOCALIZED"ı reddediyor; sıralama aşağıda Türkçe kurallarla
        )?.use { c ->
            val id = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val title = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artist = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val album = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val duration = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val added = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)
            while (c.moveToNext()) {
                val a = c.getString(artist)
                var t = c.getString(title) ?: "Adsız"
                var ar = if (a.isNullOrBlank() || a == "<unknown>") null else a
                // Etiketsiz dosyada MediaStore başlığı dosya adından alır: "Sanatçı - Şarkı" (Ezgi indirmeleri böyle).
                if (ar == null && " - " in t) {
                    ar = t.substringBefore(" - ").trim()
                    t = t.substringAfter(" - ").trim()
                }
                songs += Song(
                    id = c.getLong(id),
                    title = t,
                    artist = ar ?: "Bilinmeyen sanatçı",
                    album = c.getString(album) ?: "",
                    durationMs = c.getLong(duration),
                    dateAdded = c.getLong(added),
                )
            }
        }
        val tr = Collator.getInstance(Locale.forLanguageTag("tr")).apply { strength = Collator.PRIMARY }
        songs.sortedWith { a, b -> tr.compare(a.title, b.title) }
    }
}
