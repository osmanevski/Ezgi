package com.osmanevski.ezgi

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.File
import java.io.IOException

data class Release(val version: String, val notes: String, val apkUrl: String, val size: Long)

sealed interface UpdateState {
    data object None : UpdateState
    data class Available(val release: Release) : UpdateState
    data class Downloading(val release: Release, val progress: Float) : UpdateState
    /** APK indi; Android'in yükleme ekranı açılacak (gerekirse önce izin). */
    data class Ready(val release: Release, val file: File) : UpdateState
    data class Failed(val release: Release, val reason: String) : UpdateState
}

/**
 * Güncellemeler GitHub'daki açık depodan: son sürümün APK'sı indirilir, Android'in kendi yükleme
 * ekranı açılır. Aynı anahtarla imzalı olduğu için üstüne kurulur; listeler ve şarkılar kalır.
 * Sessiz kurulum yok: Play dışı uygulamada son "Güncelle" dokunuşu her zaman kullanıcının.
 */
object Updater {
    private const val LATEST = "https://api.github.com/repos/osmanevski/Ezgi/releases/latest"
    private val json = Json { ignoreUnknownKeys = true }

    fun currentVersion(context: Context): String =
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "0"

    /** Daha yeni sürüm varsa onu döner; yoksa ya da ağ yoksa null. */
    suspend fun check(current: String): Release? = withContext(Dispatchers.IO) {
        val req = okhttp3.Request.Builder().url(LATEST)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "Ezgi")
            .build()
        val body = YouTube.http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) return@withContext null
            r.body.string()
        }
        val o = json.parseToJsonElement(body).jsonObject
        val version = o["tag_name"]?.jsonPrimitive?.content?.removePrefix("v") ?: return@withContext null
        val apk = o["assets"]?.jsonArray?.map { it.jsonObject }
            ?.firstOrNull { it["name"]?.jsonPrimitive?.content?.endsWith(".apk") == true }
            ?: return@withContext null
        if (!isNewer(version, current)) return@withContext null
        Release(
            version = version,
            notes = o["body"]?.jsonPrimitive?.content.orEmpty().trim(),
            apkUrl = apk["browser_download_url"]!!.jsonPrimitive.content,
            size = apk["size"]?.jsonPrimitive?.longOrNull ?: -1,
        )
    }

    /** "1.10" > "1.9": parça parça sayı olarak. */
    fun isNewer(a: String, b: String): Boolean {
        val x = a.split('.').map { it.toIntOrNull() ?: 0 }
        val y = b.split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(x.size, y.size)) {
            val d = x.getOrElse(i) { 0 } - y.getOrElse(i) { 0 }
            if (d != 0) return d > 0
        }
        return false
    }

    suspend fun download(context: Context, release: Release, progress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "guncelleme").apply { deleteRecursively(); mkdirs() }
        val file = File(dir, "Ezgi-${release.version}.apk")
        val req = okhttp3.Request.Builder().url(release.apkUrl).header("User-Agent", "Ezgi").build()
        YouTube.http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
            val total = r.body.contentLength().takeIf { it > 0 } ?: release.size
            r.body.byteStream().use { input ->
                file.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (total > 0) progress(done.toFloat() / total)
                    }
                }
            }
        }
        file
    }

    /** Bilinmeyen kaynak izni yoksa ayar ekranını açar ve false döner; izin verilince tekrar çağrılır. */
    fun install(context: Context, file: File): Boolean {
        if (Build.VERSION.SDK_INT >= 26 && !context.packageManager.canRequestPackageInstalls()) {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            return false
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.dosyalar", file)
        context.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        return true
    }
}
