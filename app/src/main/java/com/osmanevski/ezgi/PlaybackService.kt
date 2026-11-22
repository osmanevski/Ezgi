package com.osmanevski.ezgi

import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

/** Çalmanın asıl sahibi. Ekran kapanınca, uygulama arka plana geçince de çalar; bildirim Media3'ten gelir. */
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        // İndirmeden dinleme: YouTube akışı indirmedeki tarayıcı kimliğiyle istenir.
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(YouTube.USER_AGENT)
            .setAllowCrossProtocolRedirects(true)
        val player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(DefaultDataSource.Factory(this, http)))
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus = */ true, // arama gelince ya da başka uygulama çalınca dur
            )
            .setHandleAudioBecomingNoisy(true) // kulaklık çıkınca hoparlörden bağırmasın
            .build()
        // Ekolayzer çalar oturumuna bağlanır; oturum değişirse yeniden bağlanır.
        SoundFx.attach(this, player.audioSessionId)
        player.addListener(object : Player.Listener {
            override fun onAudioSessionIdChanged(audioSessionId: Int) = SoundFx.attach(this@PlaybackService, audioSessionId)
        })
        session = MediaSession.Builder(this, player)
            .setCallback(object : MediaSession.Callback {
                // Denetleyiciden gelen öğelerde dosya adresi taşınmayabilir; kimlikten yeniden kur.
                override fun onAddMediaItems(
                    mediaSession: MediaSession,
                    controller: MediaSession.ControllerInfo,
                    mediaItems: MutableList<MediaItem>,
                ): ListenableFuture<MutableList<MediaItem>> = Futures.immediateFuture(
                    mediaItems.map { item ->
                        val id = item.mediaId.toLongOrNull()
                        val stream = item.requestMetadata.mediaUri
                        when {
                            item.localConfiguration != null -> item
                            id != null -> item.buildUpon().setUri(songUri(id)).build()
                            stream != null -> item.buildUpon().setUri(stream).build() // YouTube akışı
                            else -> item
                        }
                    }.toMutableList()
                )
            })
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    // Uygulama son kullanılanlardan kaydırılınca: çalmıyorsa servisi de kapat.
    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = session?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        SoundFx.release()
        session?.run {
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }
}
