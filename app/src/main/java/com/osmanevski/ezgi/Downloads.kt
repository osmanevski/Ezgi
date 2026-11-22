package com.osmanevski.ezgi

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.io.OutputStream
import kotlin.coroutines.resume

sealed interface DlState {
    data class Running(val progress: Float) : DlState   // 0..1, bilinmiyorsa -1
    data object Done : DlState
    data class Failed(val reason: String) : DlState
}

@Serializable
private data class DlRecord(val url: String, val songId: Long)

/**
 * İndirmeler uygulama süreci yaşadıkça sürer (müzik çalıyorsa süreç zaten ayakta).
 * Dosyalar telefonun Müzik/Ezgi klasörüne gider; Ezgi silinse bile şarkılar kalır.
 */
class DownloadCenter(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gate = Semaphore(2)
    private val recordLock = Mutex()
    private val recordFile = File(context.filesDir, "indirilenler.json")
    private val json = Json { ignoreUnknownKeys = true }

    private val _states = MutableStateFlow<Map<String, DlState>>(emptyMap())
    val states: StateFlow<Map<String, DlState>> = _states.asStateFlow()

    /** YouTube adresi → telefondaki şarkı kimliği; "zaten indirildi" işareti için. */
    private val _done = MutableStateFlow<Map<String, Long>>(emptyMap())
    val done: StateFlow<Map<String, Long>> = _done.asStateFlow()

    var onFinished: (() -> Unit)? = null

    init {
        scope.launch {
            _done.value = runCatching {
                json.decodeFromString<List<DlRecord>>(recordFile.readText()).associate { it.url to it.songId }
            }.getOrDefault(emptyMap())
        }
    }

    fun start(hit: SearchHit) {
        val cur = _states.value[hit.url]
        if (cur is DlState.Running) return
        set(hit.url, DlState.Running(-1f))
        scope.launch {
            gate.withPermit {
                val result = runCatching { download(hit) }
                set(hit.url, result.fold({ DlState.Done }, { DlState.Failed(friendly(it)) }))
                if (result.isSuccess) onFinished?.invoke()
            }
        }
    }

    /** Şarkı telefondan silinince "indirildi" işaretini kaldır; aramada yeniden İndir görünsün. */
    fun forget(songId: Long) {
        scope.launch {
            recordLock.withLock {
                val urls = _done.value.filterValues { it == songId }.keys
                if (urls.isEmpty()) return@withLock
                _done.update { it - urls }
                _states.update { it - urls }
                recordFile.writeText(json.encodeToString(_done.value.map { DlRecord(it.key, it.value) }))
            }
        }
    }

    private fun set(url: String, s: DlState) = _states.update { it + (url to s) }

    private suspend fun download(hit: SearchHit) {
        val src = YouTube.bestAudio(hit.url)
        val base = safeName("${hit.artist} - ${hit.title}")
        val target = Target.create(context, "$base.${src.ext}", src.mime, hit)
        try {
            target.open().use { out -> fetch(src.url, out) { p -> set(hit.url, DlState.Running(p)) } }
            val songId = target.commit()
            hit.thumbnail?.let { saveCover(it, songId) }
            recordLock.withLock {
                _done.update { it + (hit.url to songId) }
                recordFile.writeText(json.encodeToString(_done.value.map { DlRecord(it.key, it.value) }))
            }
        } catch (e: Throwable) {
            target.abort()
            throw e
        }
    }

    /**
     * Parça parça indirme. YouTube tek seferde büyük isteği yavaşlatıyor; 1 MB'lık Range
     * istekleriyle tam hızda geliyor (NewPipe de böyle yapıyor).
     */
    private fun fetch(url: String, out: OutputStream, progress: (Float) -> Unit) {
        val chunk = 1L shl 20
        var pos = 0L
        var total = -1L
        val buf = ByteArray(64 * 1024)
        while (total < 0 || pos < total) {
            var attempt = 0
            while (true) {
                try {
                    val req = okhttp3.Request.Builder().url(url)
                        .header("User-Agent", YouTube.USER_AGENT)
                        .header("Range", "bytes=$pos-${pos + chunk - 1}")
                        .build()
                    YouTube.http.newCall(req).execute().use { r ->
                        if (r.code == 416) { total = pos; return@use }
                        if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
                        if (r.code == 200) total = r.body.contentLength() // sunucu Range'i yok saydı: tümü geliyor
                        else r.header("Content-Range")?.substringAfter('/')?.toLongOrNull()?.let { total = it }
                        r.body.byteStream().use { input ->
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                pos += n
                                if (total > 0) progress(pos.toFloat() / total)
                            }
                        }
                        if (r.code == 200 && total < 0) total = pos
                    }
                    break
                } catch (e: IOException) {
                    if (++attempt >= 3) throw e
                    Thread.sleep(1000L * attempt)
                }
            }
            if (total < 0) total = pos // uzunluk hiç bildirilmedi
        }
        if (pos == 0L) throw IOException("Boş dosya geldi")
    }

    private fun saveCover(url: String, songId: Long) = runCatching {
        val req = okhttp3.Request.Builder().url(url).header("User-Agent", YouTube.USER_AGENT).build()
        YouTube.http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) return@use
            val dir = File(context.filesDir, "kapak").apply { mkdirs() }
            File(dir, "$songId.jpg").writeBytes(r.body.bytes())
        }
    }

    private fun friendly(e: Throwable): String = when {
        e is IOException -> "Bağlantı koptu, tekrar dene"
        e.javaClass.simpleName.contains("ReCaptcha") -> "YouTube şu an engelledi, biraz sonra dene"
        e.javaClass.simpleName.contains("AgeRestricted") -> "Yaş sınırlı, indirilemez"
        e.javaClass.simpleName.contains("GeographicRestriction") -> "Türkiye'de kapalı"
        else -> e.message?.take(80) ?: "İndirilemedi"
    }

    companion object {
        fun safeName(s: String) = s.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), " ")
            .replace(Regex("\\s+"), " ").trim().take(120)
    }
}

/** Dosyanın Müzik/Ezgi'ye yazılması. Android 10+ MediaStore, öncesi doğrudan dosya. */
private sealed class Target {
    abstract fun open(): OutputStream
    abstract suspend fun commit(): Long
    abstract fun abort()

    class Store(private val context: Context, private val uri: Uri) : Target() {
        override fun open() = context.contentResolver.openOutputStream(uri, "w")
            ?: throw IOException("Dosya açılamadı")
        override suspend fun commit(): Long {
            context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }, null, null)
            return uri.lastPathSegment!!.toLong()
        }
        override fun abort() { runCatching { context.contentResolver.delete(uri, null, null) } }
    }

    class Legacy(private val context: Context, private val file: File) : Target() {
        override fun open() = file.outputStream()
        override suspend fun commit(): Long = suspendCancellableCoroutine { cont ->
            MediaScannerConnection.scanFile(context, arrayOf(file.path), null) { _, uri ->
                cont.resume(uri?.lastPathSegment?.toLongOrNull() ?: -1L)
            }
        }
        override fun abort() { file.delete() }
    }

    companion object {
        fun create(context: Context, name: String, mime: String, hit: SearchHit): Target {
            if (Build.VERSION.SDK_INT >= 29) {
                val values = ContentValues().apply {
                    put(MediaStore.Audio.Media.DISPLAY_NAME, name)
                    put(MediaStore.Audio.Media.MIME_TYPE, mime)
                    put(MediaStore.Audio.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MUSIC}/Ezgi")
                    put(MediaStore.Audio.Media.TITLE, hit.title)
                    put(MediaStore.Audio.Media.ARTIST, hit.artist)
                    put(MediaStore.Audio.Media.IS_PENDING, 1)
                }
                val uri = context.contentResolver.insert(
                    MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values,
                ) ?: throw IOException("Müzik klasörüne yazılamadı")
                return Store(context, uri)
            }
            @Suppress("DEPRECATION")
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), "Ezgi").apply { mkdirs() }
            var f = File(dir, name)
            var i = 1
            while (f.exists()) f = File(dir, name.substringBeforeLast('.') + " ($i)." + name.substringAfterLast('.')).also { i++ }
            return Legacy(context, f)
        }
    }
}
