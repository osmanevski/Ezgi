package com.osmanevski.ezgi

import android.content.Context
import android.os.SystemClock
import androidx.media3.common.MediaItem
import androidx.media3.common.Player

/**
 * Çalar servisinde her şarkının gerçekten ne kadar dinlendiğini ölçer (duraklatma sayılmaz).
 * Şarkı değişince, bitince ya da servis kapanınca bir dinleme kaydı yazar. 5 sn altı yazılmaz;
 * 30 sn dolmadan elle geçilen şarkı "atlandı" sayılır.
 */
class PlayTracker(private val context: Context, private val player: Player) : Player.Listener {
    private var item: MediaItem? = player.currentMediaItem
    private var listened = 0L
    private var since: Long? = null
    private var durationMs = 0L

    private fun accumulate() {
        since?.let { listened += SystemClock.elapsedRealtime() - it }
        since = if (player.isPlaying) SystemClock.elapsedRealtime() else null
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        if (player.duration > 0) durationMs = player.duration
        accumulate()
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        val finished = reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO ||
            reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT
        finish(finished)
        item = mediaItem
    }

    override fun onPlaybackStateChanged(state: Int) {
        if (state == Player.STATE_READY && player.duration > 0) durationMs = player.duration
        if (state == Player.STATE_ENDED) {
            finish(true)
            item = player.currentMediaItem // aynı şarkı baştan çalınırsa yeni dinleme
        }
    }

    /** Servis kapanırken. */
    fun release() = finish(false)

    private fun finish(finished: Boolean) {
        accumulate()
        val it = item
        val ms = listened
        val dur = durationMs
        listened = 0
        durationMs = 0
        since = if (player.isPlaying) SystemClock.elapsedRealtime() else null
        if (it == null || ms < 5_000) return
        val id = it.mediaId
        val video = if (id.startsWith("yt:")) id.removePrefix("yt:") else id.toLongOrNull()?.let { s -> History.videoForSong(context, s) }
        val md = it.mediaMetadata
        History.add(context, PlayEvent(
            type = "dinleme",
            key = id.ifEmpty { null },
            video = video,
            title = md.title?.toString().orEmpty(),
            artist = md.artist?.toString().orEmpty(),
            album = md.albumTitle?.toString(),
            durationMs = dur,
            listenedMs = ms,
            finished = finished,
            skipped = !finished && ms < 30_000,
        ))
    }
}
