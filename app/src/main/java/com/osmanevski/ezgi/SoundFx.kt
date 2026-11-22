package com.osmanevski.ezgi

import android.content.Context
import android.media.audiofx.Equalizer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlin.math.log10

data class EqBand(val centerHz: Int, val levelMb: Int)

data class EqState(
    val ready: Boolean = false,          // çalar oturumuna bağlandı mı
    val supported: Boolean = true,       // telefon ekolayzeri veriyor mu
    val enabled: Boolean = false,
    val bands: List<EqBand> = emptyList(),
    val minMb: Int = -1500,              // millibel; 100 mB = 1 dB
    val maxMb: Int = 1500,
    val preset: Int = -1,                // EqPresets içindeki sıra; -1: özel
)

/**
 * Hazır ayarlar telefonunkiler değil, Ezgi'nin kendi eğrileri: her telefonda aynı liste.
 * dB değerleri şu frekanslarda; telefonun bant merkezlerine logaritmik aradeğerle taşınır.
 */
object EqPresets {
    val freqs = listOf(60, 150, 400, 1000, 2400, 15000)
    val all: List<Pair<String, List<Int>>> = listOf(
        "Düz" to listOf(0, 0, 0, 0, 0, 0),
        "Akustik" to listOf(4, 3, 1, 1, 2, 3),
        "Bas Artırıcı" to listOf(6, 4, 1, 0, 0, 0),
        "Bas Azaltıcı" to listOf(-6, -4, -1, 0, 0, 0),
        "Klasik" to listOf(4, 3, 0, -1, 1, 3),
        "Dans" to listOf(5, 3, 0, 1, 3, 2),
        "Derin" to listOf(4, 2, 1, 0, -2, -4),
        "Elektronik" to listOf(5, 3, 0, -1, 2, 4),
        "Hip-Hop" to listOf(5, 4, 0, -1, 1, 2),
        "Caz" to listOf(3, 2, -1, 1, 2, 3),
        "Latin" to listOf(2, 0, -1, 0, 2, 4),
        "Yüksek Ses" to listOf(4, 2, 0, 0, 2, 3),
        "Salon" to listOf(-2, 0, 1, 2, 0, -1),
        "Piyano" to listOf(2, 1, 0, 2, 3, 2),
        "Pop" to listOf(-1, 1, 3, 3, 1, -1),
        "R&B" to listOf(4, 6, 2, -1, 1, 2),
        "Rock" to listOf(4, 3, -1, -1, 2, 4),
        "Küçük Hoparlör" to listOf(5, 3, 1, 0, -1, -3),
        "Konuşma" to listOf(-3, -1, 2, 4, 3, 0),
        "Tiz Artırıcı" to listOf(0, 0, 0, 1, 3, 6),
        "Tiz Azaltıcı" to listOf(0, 0, 0, -1, -3, -6),
        "Vokal Artırıcı" to listOf(-2, -1, 2, 4, 3, 1),
    )

    /** Hazır ayarın [hz] frekansındaki değeri, millibel. */
    fun levelAt(preset: Int, hz: Int): Int {
        val db = all[preset].second
        val x = log10(hz.toDouble().coerceAtLeast(1.0))
        val xs = freqs.map { log10(it.toDouble()) }
        if (x <= xs.first()) return db.first() * 100
        if (x >= xs.last()) return db.last() * 100
        val i = xs.indexOfLast { it <= x }
        val t = (x - xs[i]) / (xs[i + 1] - xs[i])
        return ((db[i] + (db[i + 1] - db[i]) * t) * 100).toInt()
    }
}

/**
 * Telefonun kendi ekolayzeri (android.media.audiofx), Ezgi'nin çalar oturumuna bağlı.
 * Yalnız Ezgi'nin sesini değiştirir. Ayarlar `ses` tercihlerinde, açılışta geri yüklenir.
 */
object SoundFx {
    private var eq: Equalizer? = null
    private var prefs: android.content.SharedPreferences? = null

    private val _state = MutableStateFlow(EqState())
    val state: StateFlow<EqState> = _state.asStateFlow()

    fun attach(context: Context, sessionId: Int) {
        release()
        val p = context.getSharedPreferences("ses", Context.MODE_PRIVATE).also { prefs = it }
        val e = runCatching { Equalizer(0, sessionId) }.getOrNull()
        if (e == null) {
            _state.value = EqState(ready = true, supported = false)
            return
        }
        eq = e
        val n = e.numberOfBands.toInt()
        val (min, max) = e.bandLevelRange.let { it[0].toInt() to it[1].toInt() }
        val centers = (0 until n).map { e.getCenterFreq(it.toShort()) / 1000 }
        val preset = p.getInt("hazir2", -1).takeIf { it in EqPresets.all.indices } ?: -1
        val saved = p.getString("bantlar", null)?.split(',')?.mapNotNull { it.toIntOrNull() }
        val levels = when {
            preset >= 0 -> centers.map { EqPresets.levelAt(preset, it) }
            saved != null && saved.size == n -> saved
            else -> List(n) { 0 }
        }.map { it.coerceIn(min, max) }
        levels.forEachIndexed { i, l -> runCatching { e.setBandLevel(i.toShort(), l.toShort()) } }
        val enabled = p.getBoolean("acik", false)
        runCatching { e.enabled = enabled }
        _state.value = EqState(
            ready = true, supported = true, enabled = enabled,
            bands = centers.zip(levels) { c, l -> EqBand(c, l) },
            minMb = min, maxMb = max, preset = preset,
        )
    }

    fun setEnabled(on: Boolean) {
        runCatching { eq?.enabled = on }
        _state.update { it.copy(enabled = on) }
        save()
    }

    fun usePreset(i: Int) {
        val s = _state.value
        val levels = s.bands.map { EqPresets.levelAt(i, it.centerHz).coerceIn(s.minMb, s.maxMb) }
        levels.forEachIndexed { b, l -> runCatching { eq?.setBandLevel(b.toShort(), l.toShort()) } }
        _state.update { it.copy(preset = i, bands = it.bands.zip(levels) { band, l -> band.copy(levelMb = l) }) }
        if (!s.enabled) setEnabled(true) else save()
    }

    /** Grafikte nokta sürüklenirken çağrılır; kaydetme [save] ile parmak kalkınca. */
    fun setBand(i: Int, levelMb: Int) {
        val s = _state.value
        val l = levelMb.coerceIn(s.minMb, s.maxMb)
        runCatching { eq?.setBandLevel(i.toShort(), l.toShort()) }
        _state.update { it.copy(preset = -1, bands = it.bands.mapIndexed { b, band -> if (b == i) band.copy(levelMb = l) else band }) }
        if (!s.enabled) setEnabled(true)
    }

    fun save() {
        val s = _state.value
        prefs?.edit()
            ?.putBoolean("acik", s.enabled)
            ?.putInt("hazir2", s.preset)
            ?.putString("bantlar", s.bands.joinToString(",") { it.levelMb.toString() })
            ?.apply()
    }

    fun release() {
        runCatching { eq?.release() }
        eq = null
    }
}
