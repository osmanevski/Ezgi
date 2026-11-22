package com.osmanevski.ezgi

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.File
import kotlin.math.abs

data class LyricLine(val timeMs: Long, val text: String)

/** [synced] boş değilse satırlar zamanlı; değilse [plain] düz metin. İkisi de boşsa bulunamadı. */
data class Lyrics(val synced: List<LyricLine>, val plain: String) {
    val found get() = synced.isNotEmpty() || plain.isNotBlank()
}

@Serializable
private data class LyricsCache(val synced: String = "", val plain: String = "", val at: Long = 0)

/**
 * Şarkı sözleri LRCLIB'den (lrclib.net: açık, anahtarsız, zamanlı LRC verir). Giden tek şey sanatçı,
 * şarkı adı ve süre. Sonuç `files/sozler/<şarkı>.json`'da saklanır; bulunamayanlar bir hafta sonra
 * yeniden denenir.
 */
object LyricsRepo {
    private const val API = "https://lrclib.net/api"
    private const val RETRY_MISSING_MS = 7L * 24 * 3600 * 1000
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun get(context: Context, songId: Long, artist: String, title: String, durationMs: Long): Lyrics =
        withContext(Dispatchers.IO) {
            val file = File(context.filesDir, "sozler/$songId.json")
            val cached = runCatching { json.decodeFromString<LyricsCache>(file.readText()) }.getOrNull()
            if (cached != null && (cached.synced.isNotBlank() || cached.plain.isNotBlank() ||
                    System.currentTimeMillis() - cached.at < RETRY_MISSING_MS)) {
                return@withContext Lyrics(parseLrc(cached.synced), cached.plain)
            }
            val hit = fetch(cleanArtist(artist), cleanTitle(title), durationMs / 1000.0)
            val synced = hit?.get("syncedLyrics")?.jsonPrimitive?.contentOrNull.orEmpty()
            val plain = hit?.get("plainLyrics")?.jsonPrimitive?.contentOrNull.orEmpty()
            file.parentFile?.mkdirs()
            file.writeText(json.encodeToString(LyricsCache(synced, plain, System.currentTimeMillis())))
            Lyrics(parseLrc(synced), plain)
        }

    /** Önce tam eşleşme (sanatçı + ad + süre), olmazsa arama; süresi en yakın, tercihen zamanlı olan. */
    private fun fetch(artist: String, title: String, durationSec: Double): JsonObject? {
        val exact = request("$API/get".toHttpUrl().newBuilder()
            .addQueryParameter("artist_name", artist)
            .addQueryParameter("track_name", title)
            .addQueryParameter("duration", durationSec.toInt().toString())
            .build().toString())
        if (exact is JsonObject && exact.hasLyrics()) return exact

        val queries = listOf(
            "$API/search".toHttpUrl().newBuilder().addQueryParameter("track_name", title)
                .addQueryParameter("artist_name", artist).build().toString(),
            "$API/search".toHttpUrl().newBuilder().addQueryParameter("q", "$artist $title").build().toString(),
        )
        for (q in queries) {
            val list = (request(q) as? JsonArray)?.map { it.jsonObject }?.filter { it.hasLyrics() }.orEmpty()
            val best = list
                .filter { durationSec <= 0 || abs((it["duration"]?.jsonPrimitive?.doubleOrNull ?: 0.0) - durationSec) <= 8 }
                .sortedWith(compareBy(
                    { it["syncedLyrics"]?.jsonPrimitive?.contentOrNull.isNullOrBlank() },
                    { abs((it["duration"]?.jsonPrimitive?.doubleOrNull ?: 0.0) - durationSec) },
                ))
                .firstOrNull()
            if (best != null) return best
        }
        return null
    }

    private fun JsonObject.hasLyrics() =
        !this["syncedLyrics"]?.jsonPrimitive?.contentOrNull.isNullOrBlank() ||
            !this["plainLyrics"]?.jsonPrimitive?.contentOrNull.isNullOrBlank()

    /** 404 gibi yanıtlar null; bağlantı hatası IOException olarak yukarı çıkar (önbelleğe "yok" yazılmasın). */
    private fun request(url: String): kotlinx.serialization.json.JsonElement? {
        val req = okhttp3.Request.Builder().url(url).header("User-Agent", "Ezgi (github.com/osmanevski/Ezgi)").build()
        return YouTube.http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) null else runCatching { json.parseToJsonElement(r.body.string()) }.getOrNull()
        }
    }

    /** "[01:23.45] söz" satırları; bir satırda birden çok zaman olabilir. */
    fun parseLrc(lrc: String): List<LyricLine> {
        val tag = Regex("""\[(\d+):(\d+)(?:[.:](\d+))?]""")
        return lrc.lineSequence().flatMap { line ->
            val text = line.replace(tag, "").trim()
            tag.findAll(line).map { m ->
                val frac = m.groupValues[3]
                val ms = if (frac.isEmpty()) 0 else frac.padEnd(3, '0').take(3).toInt()
                LyricLine(m.groupValues[1].toLong() * 60_000 + m.groupValues[2].toLong() * 1000 + ms, text)
            }
        }.sortedBy { it.timeMs }.toList()
    }

    private fun cleanTitle(t: String) = t
        .replace(Regex("""\s*[(\[][^)\]]*(official|video|audio|lyric|klip|remaster|hd|4k|canlı|live)[^)\]]*[)\]]""", RegexOption.IGNORE_CASE), "")
        .replace(Regex("""\s+(ft\.?|feat\.?)\s.*$""", RegexOption.IGNORE_CASE), "")
        .trim()

    private fun cleanArtist(a: String) = a.removeSuffix(" - Topic").replace(Regex("""\s*(VEVO|Official)$""", RegexOption.IGNORE_CASE), "").trim()
}
