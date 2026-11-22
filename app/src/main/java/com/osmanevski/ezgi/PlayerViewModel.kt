package com.osmanevski.ezgi

import android.app.Application
import android.content.ComponentName
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

data class NowPlaying(
    val songId: Long? = null,
    val title: String = "",
    val artist: String = "",
    val isPlaying: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val shuffle: Boolean = false,
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
    val hasNext: Boolean = false,
    val hasPrevious: Boolean = false,
)

class PlayerViewModel(app: Application) : AndroidViewModel(app) {
    private val store = PlaylistStore(app)

    private val _songs = MutableStateFlow<List<Song>>(emptyList())
    val songs: StateFlow<List<Song>> = _songs.asStateFlow()

    private val orderStore = SongOrderStore(app)
    private val _sortMode = MutableStateFlow(orderStore.mode)
    val sortMode: StateFlow<SortMode> = _sortMode.asStateFlow()
    private val _customOrder = MutableStateFlow<List<Long>>(emptyList())

    /** Şarkılar sekmesinde görünen sıra. */
    val sortedSongs: StateFlow<List<Song>> = combine(_songs, _sortMode, _customOrder) { songs, mode, custom ->
        sortSongs(songs, mode, custom)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun setSortMode(mode: SortMode) {
        orderStore.mode = mode
        _sortMode.value = mode
    }

    /** Elle düzenlenen sırayı kaydet; düzenleme özel sırayı da seçer. */
    fun saveCustomOrder(ids: List<Long>) {
        _customOrder.value = ids
        setSortMode(SortMode.CUSTOM)
        viewModelScope.launch { orderStore.saveOrder(ids) }
    }

    // ---- güncelleme ----
    private val _update = MutableStateFlow<UpdateState>(UpdateState.None)
    val update: StateFlow<UpdateState> = _update.asStateFlow()
    val version: String = Updater.currentVersion(app)

    fun checkUpdate() {
        viewModelScope.launch {
            runCatching { Updater.check(version) }.getOrNull()?.let { _update.value = UpdateState.Available(it) }
        }
    }

    fun dismissUpdate() { _update.value = UpdateState.None }

    fun startUpdate() {
        val rel = when (val s = _update.value) {
            is UpdateState.Available -> s.release
            is UpdateState.Failed -> s.release
            is UpdateState.Ready -> { Updater.install(getApplication(), s.file); return }
            else -> return
        }
        viewModelScope.launch {
            _update.value = UpdateState.Downloading(rel, -1f)
            runCatching { Updater.download(getApplication(), rel) { p -> _update.value = UpdateState.Downloading(rel, p) } }
                .onSuccess { f -> _update.value = UpdateState.Ready(rel, f); Updater.install(getApplication(), f) }
                .onFailure {
                    if (it is kotlinx.coroutines.CancellationException) throw it
                    _update.value = UpdateState.Failed(rel, if (it is java.io.IOException) "İnternet bağlantısı yok gibi" else (it.message ?: "İndirilemedi"))
                }
        }
    }

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _playlists = MutableStateFlow<List<Playlist>>(emptyList())
    val playlists: StateFlow<List<Playlist>> = _playlists.asStateFlow()

    private val _now = MutableStateFlow(NowPlaying())
    val now: StateFlow<NowPlaying> = _now.asStateFlow()

    val downloads = DownloadCenter(app).apply {
        onFinished = { reloadLibrary() } // yeni şarkı Şarkılar sekmesine düşsün
    }

    private val _hits = MutableStateFlow<List<SearchHit>>(emptyList())
    val hits: StateFlow<List<SearchHit>> = _hits.asStateFlow()

    private val _searching = MutableStateFlow(false)
    val searching: StateFlow<Boolean> = _searching.asStateFlow()

    private val _searchError = MutableStateFlow<String?>(null)
    val searchError: StateFlow<String?> = _searchError.asStateFlow()

    private var searchJob: Job? = null

    private var controller: MediaController? = null
    private var ticker: Job? = null
    private val saveLock = Mutex()

    init {
        viewModelScope.launch { _playlists.value = store.load() }
        viewModelScope.launch { _customOrder.value = orderStore.loadOrder() }
        checkUpdate()
        viewModelScope.launch {
            val token = SessionToken(app, ComponentName(app, PlaybackService::class.java))
            val c = MediaController.Builder(app, token).buildAsync().await()
            controller = c
            c.addListener(object : Player.Listener {
                override fun onEvents(player: Player, events: Player.Events) = refresh()
            })
            refresh()
        }
    }

    fun reloadLibrary() {
        viewModelScope.launch {
            _loading.value = true
            _songs.value = MusicLibrary.load(getApplication())
            _loading.value = false
        }
    }

    // ---- YouTube ----

    fun search(query: String, songsOnly: Boolean) {
        val q = query.trim()
        if (q.isEmpty()) return
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            _searching.value = true
            _searchError.value = null
            runCatching { YouTube.search(q, songsOnly) }
                .onSuccess { _hits.value = it }
                .onFailure {
                    if (it is kotlinx.coroutines.CancellationException) throw it
                    _searchError.value = if (it is java.io.IOException) "İnternet bağlantısı yok gibi"
                    else "Arama olmadı: ${it.message?.take(80) ?: it.javaClass.simpleName}"
                }
            _searching.value = false
        }
    }

    fun download(hit: SearchHit) = downloads.start(hit)

    /** İndirilmiş bir parçayı doğrudan çal. */
    fun playDownloaded(songId: Long) {
        val all = _songs.value
        val i = all.indexOfFirst { it.id == songId }
        if (i >= 0) play(all, i)
    }

    // ---- çalma ----

    fun play(queue: List<Song>, startIndex: Int, shuffle: Boolean = false) {
        val c = controller ?: return
        if (queue.isEmpty()) return
        c.shuffleModeEnabled = shuffle
        val start = if (shuffle) queue.indices.random() else startIndex.coerceIn(queue.indices)
        c.setMediaItems(queue.map { it.toMediaItem() }, start, 0L)
        c.prepare()
        c.play()
    }

    fun togglePlay() {
        val c = controller ?: return
        if (c.isPlaying) c.pause() else {
            if (c.playbackState == Player.STATE_IDLE) c.prepare()
            c.play()
        }
    }

    fun next() = controller?.seekToNextMediaItem()
    fun previous() = controller?.seekToPrevious()
    fun seekTo(ms: Long) = controller?.seekTo(ms)

    fun toggleShuffle() {
        controller?.let { it.shuffleModeEnabled = !it.shuffleModeEnabled }
    }

    fun cycleRepeat() {
        val c = controller ?: return
        c.repeatMode = when (c.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
    }

    private fun refresh() {
        val c = controller ?: return
        val item = c.currentMediaItem
        _now.value = NowPlaying(
            songId = item?.mediaId?.toLongOrNull(),
            title = item?.mediaMetadata?.title?.toString().orEmpty(),
            artist = item?.mediaMetadata?.artist?.toString().orEmpty(),
            isPlaying = c.isPlaying,
            positionMs = c.currentPosition.coerceAtLeast(0),
            durationMs = c.duration.takeIf { it > 0 } ?: 0,
            shuffle = c.shuffleModeEnabled,
            repeatMode = c.repeatMode,
            hasNext = c.hasNextMediaItem(),
            hasPrevious = item != null,
        )
        if (c.isPlaying && ticker?.isActive != true) {
            ticker = viewModelScope.launch {
                while (isActive && controller?.isPlaying == true) {
                    _now.update { it.copy(positionMs = controller?.currentPosition ?: it.positionMs) }
                    delay(500)
                }
            }
        }
    }

    // ---- listeler ----

    fun createPlaylist(name: String, firstSong: Song? = null) = editLists { lists ->
        lists + Playlist(UUID.randomUUID().toString(), name.trim(), listOfNotNull(firstSong?.id))
    }

    fun renamePlaylist(id: String, name: String) = editLists { lists ->
        lists.map { if (it.id == id) it.copy(name = name.trim()) else it }
    }

    fun deletePlaylist(id: String) = editLists { lists -> lists.filterNot { it.id == id } }

    fun addToPlaylist(id: String, song: Song) = editLists { lists ->
        lists.map { if (it.id == id && song.id !in it.songIds) it.copy(songIds = it.songIds + song.id) else it }
    }

    fun removeFromPlaylist(id: String, songId: Long) = editLists { lists ->
        lists.map { if (it.id == id) it.copy(songIds = it.songIds - songId) else it }
    }

    /** Listedeki kimlikleri telefondaki şarkılarla eşler; silinmiş dosyalar sessizce düşer. */
    fun songsOf(playlist: Playlist, all: List<Song>): List<Song> {
        val byId = all.associateBy { it.id }
        return playlist.songIds.mapNotNull { byId[it] }
    }

    private fun editLists(change: (List<Playlist>) -> List<Playlist>) {
        _playlists.update(change)
        // Kilit içinde en güncel hâli yaz: art arda iki düzenleme eski hâlin sona yazılmasına yol açmasın.
        viewModelScope.launch { saveLock.withLock { store.save(_playlists.value) } }
    }

    override fun onCleared() {
        controller?.release()
        controller = null
    }
}

private fun Song.toMediaItem(): MediaItem = MediaItem.Builder()
    .setMediaId(id.toString())
    .setUri(uri)
    .setMediaMetadata(
        MediaMetadata.Builder()
            .setTitle(title)
            .setArtist(artist)
            .setAlbumTitle(album)
            .setIsPlayable(true)
            .build()
    )
    .build()
