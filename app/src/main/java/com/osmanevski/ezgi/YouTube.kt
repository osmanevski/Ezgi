package com.osmanevski.ezgi

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.AudioTrackType
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import java.util.concurrent.TimeUnit

data class SearchHit(
    val url: String,
    val title: String,
    val artist: String,
    val durationSec: Long,
    val thumbnail: String?,
)

data class AudioSource(val url: String, val mime: String, val ext: String, val bitrate: Int)

/**
 * YouTube'a telefondan doğrudan gider; arada sunucu yok. Arama YouTube Music'in "şarkılar"
 * sekmesinden: klip, canlı kayıt ve tepki videosu yerine albümdeki asıl parça gelir.
 */
object YouTube {
    const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0"

    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    @Volatile private var ready = false

    private fun ensureInit() {
        if (ready) return
        synchronized(this) {
            if (!ready) {
                NewPipe.init(OkDownloader, Localization("tr", "TR"), ContentCountry("TR"))
                ready = true
            }
        }
    }

    suspend fun search(query: String, songsOnly: Boolean): List<SearchHit> = withContext(Dispatchers.IO) {
        ensureInit()
        val filter = if (songsOnly) YoutubeSearchQueryHandlerFactory.MUSIC_SONGS
        else YoutubeSearchQueryHandlerFactory.VIDEOS
        val extractor = ServiceList.YouTube.getSearchExtractor(query, listOf(filter), "")
        extractor.fetchPage()
        extractor.initialPage.items.filterIsInstance<StreamInfoItem>().map { item ->
            SearchHit(
                url = item.url,
                title = item.name,
                artist = item.uploaderName.orEmpty().removeSuffix(" - Topic"),
                durationSec = item.duration,
                thumbnail = item.thumbnails.maxByOrNull { it.height }?.url,
            )
        }
    }

    /**
     * En iyi yalnız-ses akışı. M4A (AAC) önce: her telefon çalar ve MediaStore etiketini okur.
     * Opus yalnız M4A yoksa. Dublajlı ses izleri dışarıda.
     */
    suspend fun bestAudio(url: String): AudioSource = withContext(Dispatchers.IO) {
        ensureInit()
        val info = StreamInfo.getInfo(ServiceList.YouTube, watchUrl(url))
        val usable = info.audioStreams.filter {
            it.isUrl && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP &&
                (it.audioTrackType == null || it.audioTrackType == AudioTrackType.ORIGINAL)
        }
        fun best(fmt: MediaFormat): AudioStream? =
            usable.filter { it.format == fmt }.maxByOrNull { it.averageBitrate }
        val pick = best(MediaFormat.M4A) ?: best(MediaFormat.WEBMA_OPUS) ?: best(MediaFormat.WEBMA)
            ?: error("Bu parça için indirilebilir ses bulunamadı")
        val m4a = pick.format == MediaFormat.M4A
        AudioSource(pick.content, if (m4a) "audio/mp4" else "audio/webm", if (m4a) "m4a" else "webm", pick.averageBitrate)
    }
}

/**
 * Arama sonucunun adresi music.youtube.com, youtu.be ya da ek parametreli gelebiliyor;
 * NewPipe bazılarını "URL not accepted" diye reddediyor. Kimliği çekip düz izleme adresine çeviriyoruz.
 */
internal fun watchUrl(url: String): String {
    val id = Regex("""(?:[?&]v=|youtu\.be/|/shorts/|/embed/|/live/)([A-Za-z0-9_-]{11})""").find(url)?.groupValues?.get(1)
        ?: Regex("""^[A-Za-z0-9_-]{11}$""").find(url)?.value
    return if (id != null) "https://www.youtube.com/watch?v=$id" else url
}

/** NewPipeExtractor'ın HTTP katmanı: OkHttp ile. */
private object OkDownloader : Downloader() {
    override fun execute(request: Request): Response {
        val body = request.dataToSend()?.toRequestBody()
        val builder = okhttp3.Request.Builder()
            .url(request.url())
            .method(request.httpMethod(), body)
            .header("User-Agent", YouTube.USER_AGENT)
        request.headers().forEach { (name, values) ->
            builder.removeHeader(name)
            values.forEach { builder.addHeader(name, it) }
        }
        YouTube.http.newCall(builder.build()).execute().use { r ->
            if (r.code == 429) throw ReCaptchaException("YouTube çok fazla istek dedi", request.url())
            return Response(r.code, r.message, r.headers.toMultimap(), r.body.string(), r.request.url.toString())
        }
    }
}
