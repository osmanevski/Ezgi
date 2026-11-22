package com.osmanevski.ezgi

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.security.SecureRandom
import java.util.UUID

/** Bir dinleme ya da indirme. [key] telefondaki şarkı kimliği veya "yt:<video>". */
data class PlayEvent(
    val type: String,          // "dinleme" | "indirme"
    val key: String?,
    val video: String?,
    val title: String,
    val artist: String,
    val album: String?,
    val durationMs: Long,
    val listenedMs: Long,
    val finished: Boolean,
    val skipped: Boolean,
    val at: Long = System.currentTimeMillis(),
    val id: String = UUID.randomUUID().toString(),
)

/**
 * Dinleme geçmişi telefonda her zaman tutulur (gecmis.db). Sunucuya yalnız kullanıcı "paylaş"
 * dediyse gider: osmanevski.com/ezgi. Kimlik rastgele bir kurulum kimliği ve gizli anahtar;
 * telefon bilgisi, hesap ya da konum yok.
 */
object History {
    private const val API = "https://osmanevski.com/ezgi/v1/olaylar"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sendLock = Mutex()
    private var pending: Job? = null
    private var helper: Db? = null

    /** null: henüz sorulmadı. */
    private val _sharing = MutableStateFlow<Boolean?>(null)
    val sharing: StateFlow<Boolean?> = _sharing.asStateFlow()

    private val _counts = MutableStateFlow(0 to 0) // (toplam, gönderilmemiş)
    val counts: StateFlow<Pair<Int, Int>> = _counts.asStateFlow()

    private lateinit var app: Context

    fun init(context: Context) {
        if (helper != null) return
        app = context.applicationContext
        helper = Db(app)
        val p = prefs()
        _sharing.value = if (p.contains("paylas")) p.getBoolean("paylas", false) else null
        scope.launch { refreshCounts(); push() }
    }

    private fun prefs() = app.getSharedPreferences("esitleme", Context.MODE_PRIVATE)

    fun setSharing(on: Boolean) {
        prefs().edit().putBoolean("paylas", on).apply()
        _sharing.value = on
        if (on) schedulePush(0)
    }

    fun add(context: Context, e: PlayEvent) {
        init(context)
        scope.launch {
            helper!!.writableDatabase.insertWithOnConflict("olaylar", null, ContentValues().apply {
                put("id", e.id); put("tur", e.type); put("anahtar", e.key); put("video", e.video)
                put("baslik", e.title); put("sanatci", e.artist); put("album", e.album)
                put("sure_ms", e.durationMs); put("dinlenen_ms", e.listenedMs)
                put("bitti", if (e.finished) 1 else 0); put("atlandi", if (e.skipped) 1 else 0)
                put("zaman", e.at); put("gonderildi", 0)
            }, SQLiteDatabase.CONFLICT_IGNORE)
            refreshCounts()
            schedulePush(30_000) // dinlemeleri toplu gönder
        }
    }

    private fun schedulePush(delayMs: Long) {
        if (_sharing.value != true) return
        pending?.cancel()
        pending = scope.launch { delay(delayMs); push() }
    }

    /** Gönderilmemişleri 500'erli parçalarla gönderir; ağ yoksa sonraki fırsatta yeniden dener. */
    suspend fun push() = sendLock.withLock {
        if (_sharing.value != true) return@withLock
        val db = helper?.writableDatabase ?: return@withLock
        val (kur, key) = identity()
        while (true) {
            val rows = mutableListOf<JsonObject>()
            db.rawQuery("SELECT id,tur,anahtar,video,baslik,sanatci,album,sure_ms,dinlenen_ms,bitti,atlandi,zaman " +
                "FROM olaylar WHERE gonderildi=0 ORDER BY zaman LIMIT 500", null).use { c ->
                while (c.moveToNext()) rows += buildJsonObject {
                    put("id", c.getString(0)); put("tur", c.getString(1))
                    put("anahtar", c.getString(2)); put("video", c.getString(3))
                    put("baslik", c.getString(4)); put("sanatci", c.getString(5)); put("album", c.getString(6))
                    put("sure_ms", c.getLong(7)); put("dinlenen_ms", c.getLong(8))
                    put("bitti", c.getInt(9) == 1); put("atlandi", c.getInt(10) == 1); put("zaman", c.getLong(11))
                }
            }
            if (rows.isEmpty()) break
            val body = buildJsonObject {
                put("surum", runCatching { Updater.currentVersion(app) }.getOrDefault("?"))
                put("olaylar", buildJsonArray { rows.forEach { add(it) } })
            }.toString()
            val ok = runCatching {
                val req = okhttp3.Request.Builder().url(API)
                    .header("Authorization", "Bearer $kur.$key")
                    .header("User-Agent", "Ezgi")
                    .post(body.toRequestBody("application/json".toMediaType()))
                    .build()
                YouTube.http.newCall(req).execute().use { r -> r.isSuccessful }
            }.getOrDefault(false)
            if (!ok) break
            db.beginTransaction()
            try {
                rows.forEach { db.execSQL("UPDATE olaylar SET gonderildi=1 WHERE id=?", arrayOf(it["id"]!!.jsonPrimitive.content)) }
                db.setTransactionSuccessful()
            } finally { db.endTransaction() }
        }
        refreshCounts()
    }

    /** Telefonun kalıcı, rastgele kimliği: hiçbir cihaz bilgisinden türetilmez. */
    private fun identity(): Pair<String, String> {
        val p = prefs()
        val kur = p.getString("kurulum", null) ?: UUID.randomUUID().toString().also { p.edit().putString("kurulum", it).apply() }
        val key = p.getString("anahtar", null) ?: ByteArray(32).also { SecureRandom().nextBytes(it) }
            .joinToString("") { "%02x".format(it) }.also { p.edit().putString("anahtar", it).apply() }
        return kur to key
    }

    fun installId(): String? = if (::app.isInitialized) prefs().getString("kurulum", null) else null

    private fun refreshCounts() {
        val db = helper?.readableDatabase ?: return
        db.rawQuery("SELECT COUNT(*), SUM(gonderildi=0) FROM olaylar", null).use { c ->
            if (c.moveToFirst()) _counts.value = c.getInt(0) to c.getInt(1)
        }
    }

    /** İndirilen şarkıların YouTube kimliği: indirilenler.json (adres → şarkı). */
    fun videoForSong(context: Context, songId: Long): String? = runCatching {
        val arr = Json.parseToJsonElement(File(context.filesDir, "indirilenler.json").readText())
        (arr as kotlinx.serialization.json.JsonArray).map { it.jsonObject }
            .firstOrNull { it["songId"]?.jsonPrimitive?.content?.toLongOrNull() == songId }
            ?.get("url")?.jsonPrimitive?.content?.let(::videoId)
    }.getOrNull()

    private class Db(context: Context) : SQLiteOpenHelper(context, "gecmis.db", null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("""CREATE TABLE olaylar (
                id TEXT PRIMARY KEY, tur TEXT NOT NULL, anahtar TEXT, video TEXT,
                baslik TEXT, sanatci TEXT, album TEXT, sure_ms INTEGER, dinlenen_ms INTEGER,
                bitti INTEGER, atlandi INTEGER, zaman INTEGER NOT NULL, gonderildi INTEGER NOT NULL DEFAULT 0)""")
            db.execSQL("CREATE INDEX olaylar_gonder ON olaylar(gonderildi, zaman)")
        }
        override fun onUpgrade(db: SQLiteDatabase, old: Int, new: Int) {}
    }
}
