package com.osmanevski.ezgi

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.util.LruCache
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.draw.alpha
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

private val Amber = Color(0xFFF2B66D)
private val TR = Locale.forLanguageTag("tr")

@Composable
fun EzgiTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Amber,
            onPrimary = Color(0xFF2A1A05),
            background = Color(0xFF121014),
            surface = Color(0xFF121014),
            surfaceContainer = Color(0xFF1C1920),
            surfaceContainerHigh = Color(0xFF25212A),
        ),
        content = content,
    )
}

@Composable
fun PermissionScreen(onAsk: () -> Unit) {
    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(Icons.Default.MusicNote, null, Modifier.size(64.dp), tint = Amber)
            Spacer(Modifier.height(16.dp))
            Text("Telefondaki şarkıları görmek için müzik izni gerekiyor.", style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(24.dp))
            Button(onClick = onAsk) { Text("İzin ver") }
        }
    }
}

// ---------------- ana iskelet ----------------

@Composable
fun EzgiApp(vm: PlayerViewModel) {
    UpdateDialog(vm)
    val deleteReq by vm.deleteRequest.collectAsStateWithLifecycle()
    val deleteLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
        vm.onDeleteResult(it.resultCode == android.app.Activity.RESULT_OK)
    }
    LaunchedEffect(deleteReq) {
        deleteReq?.let { deleteLauncher.launch(IntentSenderRequest.Builder(it).build()) }
    }
    val songs by vm.songs.collectAsStateWithLifecycle()
    val sortedSongs by vm.sortedSongs.collectAsStateWithLifecycle()
    val sortMode by vm.sortMode.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()
    val playlists by vm.playlists.collectAsStateWithLifecycle()
    val now by vm.now.collectAsStateWithLifecycle()

    var tab by rememberSaveable { mutableIntStateOf(0) }
    var openListId by rememberSaveable { mutableStateOf<String?>(null) }
    var playerOpen by rememberSaveable { mutableStateOf(false) }
    var addTarget by remember { mutableStateOf<Song?>(null) }
    var eqOpen by rememberSaveable { mutableStateOf(false) }

    if (eqOpen) {
        BackHandler { SoundFx.save(); eqOpen = false }
        EqualizerPage(onBack = { SoundFx.save(); eqOpen = false })
        return
    }

    BackHandler(enabled = playerOpen) { playerOpen = false }
    BackHandler(enabled = !playerOpen && openListId != null) { openListId = null }

    Box(Modifier.fillMaxSize()) {
        Scaffold(
            bottomBar = {
                Column {
                    if (now.key != null) MiniPlayer(now, vm::togglePlay, vm::next) { playerOpen = true }
                    NavigationBar {
                        NavigationBarItem(
                            selected = tab == 0, onClick = { tab = 0; openListId = null },
                            icon = { Icon(Icons.Default.MusicNote, null) }, label = { Text("Şarkılar") },
                        )
                        NavigationBarItem(
                            selected = tab == 1, onClick = { tab = 1 },
                            icon = { Icon(Icons.AutoMirrored.Filled.QueueMusic, null) }, label = { Text("Listeler") },
                        )
                        NavigationBarItem(
                            selected = tab == 2, onClick = { tab = 2 },
                            icon = { Icon(Icons.Default.Download, null) }, label = { Text("İndir") },
                        )
                        NavigationBarItem(
                            selected = tab == 3, onClick = { tab = 3 },
                            icon = { Icon(Icons.Default.Settings, null) }, label = { Text("Ayarlar") },
                        )
                    }
                }
            },
        ) { pad ->
            Box(Modifier.padding(pad)) {
                val openList = playlists.firstOrNull { it.id == openListId }
                when {
                    tab == 2 -> DownloadScreen(vm)
                    tab == 3 -> SettingsScreen(vm, onOpenEq = { eqOpen = true })
                    tab == 0 -> SongsScreen(sortedSongs, loading, now.songId, vm::reloadLibrary,
                        sortMode = sortMode, onSort = vm::setSortMode, onSaveOrder = vm::saveCustomOrder,
                        onDelete = vm::deleteSong,
                        onPlay = { q, i -> vm.play(q, i) },
                        onShuffle = { q -> vm.play(q, 0, shuffle = true) },
                        onAdd = { addTarget = it })
                    openList != null -> PlaylistDetail(
                        playlist = openList,
                        songs = vm.songsOf(openList, songs),
                        currentId = now.songId,
                        onBack = { openListId = null },
                        onPlay = { q, i -> vm.play(q, i) },
                        onShuffle = { q -> vm.play(q, 0, shuffle = true) },
                        onRemove = { vm.removeFromPlaylist(openList.id, it.id) },
                        onRename = { vm.renamePlaylist(openList.id, it) },
                        onDelete = { vm.deletePlaylist(openList.id); openListId = null },
                    )
                    else -> PlaylistsScreen(
                        playlists, onOpen = { openListId = it.id }, onCreate = { vm.createPlaylist(it) })
                }
            }
        }

        AnimatedVisibility(
            visible = playerOpen && now.key != null,
            enter = slideInVertically { it }, exit = slideOutVertically { it },
        ) {
            FullPlayer(now, vm, onClose = { playerOpen = false })
        }
    }

    addTarget?.let { song ->
        AddToPlaylistDialog(
            song, playlists,
            onPick = { vm.addToPlaylist(it.id, song); addTarget = null },
            onCreate = { vm.createPlaylist(it, song); addTarget = null },
            onDismiss = { addTarget = null },
        )
    }
}

// ---------------- şarkılar ----------------

@Composable
private fun SongsScreen(
    songs: List<Song>, loading: Boolean, currentId: Long?, onRefresh: () -> Unit,
    sortMode: SortMode, onSort: (SortMode) -> Unit, onSaveOrder: (List<Long>) -> Unit,
    onPlay: (List<Song>, Int) -> Unit, onShuffle: (List<Song>) -> Unit, onAdd: (Song) -> Unit,
    onDelete: (Song) -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var toDelete by remember { mutableStateOf<Song?>(null) }
    toDelete?.let { s ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            icon = { Icon(Icons.Default.Delete, null) },
            title = { Text("Şarkı silinsin mi?") },
            text = { Text("\"${s.title}\" telefondan tamamen silinir; listelerden de çıkar.") },
            confirmButton = { TextButton(onClick = { onDelete(s); toDelete = null }) { Text("Sil", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text("Vazgeç") } },
        )
    }
    var editing by rememberSaveable { mutableStateOf(false) }
    if (editing) {
        OrderEditor(songs, currentId, onDone = { ids -> onSaveOrder(ids); editing = false })
        return
    }
    val shown = remember(songs, query) {
        val q = query.trim().lowercase(TR)
        if (q.isEmpty()) songs
        else songs.filter { "${it.title} ${it.artist} ${it.album}".lowercase(TR).contains(q) }
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Ezgi", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            IconButton(onClick = onRefresh) { Icon(Icons.Default.Refresh, "Yenile") }
        }
        OutlinedTextField(
            value = query, onValueChange = { query = it },
            placeholder = { Text("Şarkı, sanatçı, albüm ara") },
            leadingIcon = { Icon(Icons.Default.Search, null) },
            trailingIcon = { if (query.isNotEmpty()) IconButton({ query = "" }) { Icon(Icons.Default.Close, "Temizle") } },
            singleLine = true, shape = RoundedCornerShape(28.dp),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        )
        when {
            loading && songs.isEmpty() -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
            songs.isEmpty() -> Empty("Telefonda şarkı bulunamadı.\nMüzik klasörüne MP3 ekleyip yenile.")
            shown.isEmpty() -> Empty("\"$query\" için sonuç yok.")
            else -> LazyColumn(Modifier.fillMaxSize()) {
                item {
                    Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 20.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        SortButton(sortMode, onSort, onEdit = { query = ""; editing = true })
                        Text("${shown.size} şarkı", style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                        FilledTonalButton(onClick = { onShuffle(shown) }) {
                            Icon(Icons.Default.Shuffle, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Karışık çal")
                        }
                    }
                }
                itemsIndexed(shown, key = { _, s -> s.id }) { i, s ->
                    SongRow(s, s.id == currentId, onClick = { onPlay(shown, i) }) { close ->
                        DropdownMenuItem(text = { Text("Listeye ekle") },
                            leadingIcon = { Icon(Icons.AutoMirrored.Filled.PlaylistAdd, null) },
                            onClick = { onAdd(s); close() })
                        DropdownMenuItem(text = { Text("Sil") },
                            leadingIcon = { Icon(Icons.Default.Delete, null) },
                            onClick = { toDelete = s; close() })
                    }
                }
            }
        }
    }
}

/** Sıralama düğmesi: türü gösterir, menüden değiştirilir; en altta elle düzenleme. */
@Composable
private fun SortButton(mode: SortMode, onSort: (SortMode) -> Unit, onEdit: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }) {
            Icon(Icons.AutoMirrored.Filled.Sort, null, Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(mode.label)
        }
        DropdownMenu(open, onDismissRequest = { open = false }) {
            Text("Sırala", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            SortMode.entries.forEach { m ->
                DropdownMenuItem(
                    text = { Text(m.label, color = if (m == mode) Amber else MaterialTheme.colorScheme.onSurface) },
                    trailingIcon = { if (m == mode) Icon(Icons.Default.Check, null, tint = Amber) },
                    onClick = { onSort(m); open = false },
                )
            }
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text("Sırayı düzenle") },
                leadingIcon = { Icon(Icons.Default.DragHandle, null) },
                onClick = { open = false; onEdit() },
            )
        }
    }
}

/** Spotify'daki gibi: tutamaktan sürükleyerek sırayı değiştir, Bitti ile kaydet. */
@Composable
private fun OrderEditor(songs: List<Song>, currentId: Long?, onDone: (List<Long>) -> Unit) {
    val order = remember { songs.toMutableStateList() }
    val haptic = LocalHapticFeedback.current
    val listState = rememberLazyListState()
    val reorder = rememberReorderableLazyListState(listState) { from, to ->
        val a = order.indexOfFirst { it.id == from.key }
        val b = order.indexOfFirst { it.id == to.key }
        if (a >= 0 && b >= 0) order.add(b, order.removeAt(a))
    }
    BackHandler { onDone(order.map { it.id }) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 12.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Sırayı düzenle", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text("Tutamaktan basılı tutup sürükle", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Button(onClick = { onDone(order.map { it.id }) }) { Text("Bitti") }
        }
        LazyColumn(Modifier.fillMaxSize(), state = listState) {
            items(order, key = { it.id }) { s ->
                ReorderableItem(reorder, key = s.id) { dragging ->
                    Surface(
                        color = if (dragging) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surface,
                        shadowElevation = if (dragging) 8.dp else 0.dp,
                    ) {
                        Row(
                            Modifier.fillMaxWidth().padding(start = 16.dp, top = 6.dp, bottom = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Artwork(s.id, 48.dp)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(s.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    color = if (s.id == currentId) Amber else MaterialTheme.colorScheme.onSurface)
                                Text(s.artist, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            IconButton(
                                onClick = {},
                                modifier = Modifier.draggableHandle(
                                    onDragStarted = { haptic.performHapticFeedback(HapticFeedbackType.LongPress) },
                                ),
                            ) { Icon(Icons.Default.DragHandle, "Sürükle") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ShuffleHeader(label: String, onShuffle: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        FilledTonalButton(onClick = onShuffle) {
            Icon(Icons.Default.Shuffle, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Karışık çal")
        }
    }
}

/** Satırın sağındaki üç nokta menüsü; `menu` içine kapatma fonksiyonu verilir. */
@Composable
private fun SongRow(
    song: Song, current: Boolean, onClick: () -> Unit,
    menu: @Composable ColumnScope.(close: () -> Unit) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(start = 16.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(song.id, 48.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(song.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = if (current) Amber else MaterialTheme.colorScheme.onSurface,
                fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal)
            Text("${song.artist} · ${formatTime(song.durationMs)}", maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Box {
            IconButton(onClick = { open = true }) { Icon(Icons.Default.MoreVert, "Seçenekler") }
            DropdownMenu(open, onDismissRequest = { open = false }) { menu { open = false } }
        }
    }
}

// ---------------- YouTube'dan indir ----------------

@Composable
private fun DownloadScreen(vm: PlayerViewModel) {
    val hits by vm.hits.collectAsStateWithLifecycle()
    val searching by vm.searching.collectAsStateWithLifecycle()
    val error by vm.searchError.collectAsStateWithLifecycle()
    val states by vm.downloads.states.collectAsStateWithLifecycle()
    val done by vm.downloads.done.collectAsStateWithLifecycle()
    val opening by vm.opening.collectAsStateWithLifecycle()
    val now by vm.now.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    var songsOnly by rememberSaveable { mutableStateOf(true) }
    val keyboard = LocalSoftwareKeyboardController.current
    val go = { keyboard?.hide(); vm.search(query, songsOnly) }

    Column(Modifier.fillMaxSize()) {
        Text("İndir", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 20.dp, top = 20.dp, bottom = 4.dp))
        OutlinedTextField(
            value = query, onValueChange = { query = it },
            placeholder = { Text("Şarkı adı ya da sanatçı") },
            leadingIcon = { Icon(Icons.Default.Search, null) },
            trailingIcon = { if (query.isNotEmpty()) IconButton({ query = "" }) { Icon(Icons.Default.Close, "Temizle") } },
            singleLine = true, shape = RoundedCornerShape(28.dp),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { go() }),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        )
        Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = songsOnly, onClick = { songsOnly = true; if (query.isNotBlank()) go() }, label = { Text("Şarkılar") })
            FilterChip(selected = !songsOnly, onClick = { songsOnly = false; if (query.isNotBlank()) go() }, label = { Text("Videolar") })
        }
        when {
            searching -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
            error != null -> Empty(error!!)
            hits.isEmpty() -> Empty("YouTube'da ara. Dokununca indirmeden çalar, ↓ ile indirir.\nİnen şarkılar telefonun Müzik/Ezgi klasörüne gider ve Şarkılar'da görünür.")
            else -> LazyColumn(Modifier.fillMaxSize().padding(top = 8.dp)) {
                items(hits, key = { it.url }) { hit ->
                    HitRow(hit, states[hit.url], done[hit.url],
                        opening = opening == hit.url,
                        playing = now.key == "yt:" + videoId(hit.url),
                        onDownload = { vm.download(hit) }, onPlay = { vm.playDownloaded(it) },
                        onStream = { vm.stream(hit) })
                }
            }
        }
    }
}

@Composable
private fun HitRow(
    hit: SearchHit, state: DlState?, doneId: Long?, opening: Boolean, playing: Boolean,
    onDownload: () -> Unit, onPlay: (Long) -> Unit, onStream: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth()
            .clickable { if (doneId != null) onPlay(doneId) else onStream() }
            .padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RemoteImage(hit.thumbnail, 52.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(hit.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = if (playing) Amber else MaterialTheme.colorScheme.onSurface)
            val sub = when {
                opening -> "Açılıyor…"
                state is DlState.Failed -> state.reason
                doneId != null -> "İndirildi · dokun, çal"
                else -> listOf(hit.artist, if (hit.durationSec > 0) formatTime(hit.durationSec * 1000) else "")
                    .filter { it.isNotBlank() }.joinToString(" · ")
            }
            Text(sub, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall,
                color = when {
                    state is DlState.Failed -> MaterialTheme.colorScheme.error
                    doneId != null -> Amber
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                })
        }
        Box(Modifier.size(48.dp), Alignment.Center) {
            when {
                state is DlState.Running ->
                    if (state.progress < 0) CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
                    else CircularProgressIndicator(progress = { state.progress }, modifier = Modifier.size(28.dp), strokeWidth = 3.dp)
                doneId != null -> Icon(Icons.Default.CheckCircle, "İndirildi", tint = Amber)
                state is DlState.Failed -> IconButton(onClick = onDownload) { Icon(Icons.Default.Refresh, "Tekrar dene") }
                else -> IconButton(onClick = onDownload) { Icon(Icons.Default.Download, "İndir") }
            }
        }
    }
}

private object RemoteCache { val bitmaps = LruCache<String, Bitmap>(60) }

@Composable
private fun RemoteImage(url: String?, size: Dp, corner: Dp = 8.dp) {
    val img by produceState(url?.let { RemoteCache.bitmaps.get(it) }, url) {
        if (value != null || url == null) return@produceState
        value = withContext(Dispatchers.IO) {
            runCatching {
                val req = okhttp3.Request.Builder().url(url).header("User-Agent", YouTube.USER_AGENT).build()
                YouTube.http.newCall(req).execute().use { r ->
                    val bytes = r.body.bytes()
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = 2 })
                }
            }.getOrNull()?.also { RemoteCache.bitmaps.put(url, it) }
        }
    }
    Box(
        Modifier.size(size).clip(RoundedCornerShape(corner)).background(MaterialTheme.colorScheme.surfaceContainerHigh),
        Alignment.Center,
    ) {
        img?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
            ?: Icon(Icons.Default.MusicNote, null, Modifier.size(size * 0.45f), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ---------------- listeler ----------------

@Composable
private fun PlaylistsScreen(playlists: List<Playlist>, onOpen: (Playlist) -> Unit, onCreate: (String) -> Unit) {
    var naming by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 12.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Listeler", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            IconButton(onClick = { naming = true }) { Icon(Icons.Default.Add, "Yeni liste") }
        }
        if (playlists.isEmpty()) {
            Empty("Henüz liste yok.\nSağ üstteki + ile oluştur ya da bir şarkının menüsünden \"Listeye ekle\".")
        } else LazyColumn {
            items(playlists, key = { it.id }) { p ->
                ListItem(
                    headlineContent = { Text(p.name) },
                    supportingContent = { Text("${p.songIds.size} şarkı") },
                    leadingContent = {
                        Box(Modifier.size(48.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh), Alignment.Center) {
                            Icon(Icons.AutoMirrored.Filled.QueueMusic, null, tint = Amber)
                        }
                    },
                    modifier = Modifier.clickable { onOpen(p) },
                )
            }
        }
    }
    if (naming) NameDialog("Yeni liste", "", onDone = { onCreate(it); naming = false }, onDismiss = { naming = false })
}

@Composable
private fun PlaylistDetail(
    playlist: Playlist, songs: List<Song>, currentId: Long?, onBack: () -> Unit,
    onPlay: (List<Song>, Int) -> Unit, onShuffle: (List<Song>) -> Unit, onRemove: (Song) -> Unit,
    onRename: (String) -> Unit, onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Geri") }
            Text(playlist.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "Liste seçenekleri") }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem(text = { Text("Adını değiştir") }, leadingIcon = { Icon(Icons.Default.Edit, null) },
                        onClick = { menu = false; renaming = true })
                    DropdownMenuItem(text = { Text("Listeyi sil") }, leadingIcon = { Icon(Icons.Default.Delete, null) },
                        onClick = { menu = false; confirmDelete = true })
                }
            }
        }
        if (songs.isEmpty()) Empty("Liste boş.\nŞarkılar sekmesinde bir şarkının menüsünden ekle.")
        else LazyColumn {
            item { ShuffleHeader("${songs.size} şarkı") { onShuffle(songs) } }
            itemsIndexed(songs, key = { _, s -> s.id }) { i, s ->
                SongRow(s, s.id == currentId, onClick = { onPlay(songs, i) }) { close ->
                    DropdownMenuItem(text = { Text("Listeden çıkar") }, leadingIcon = { Icon(Icons.Default.RemoveCircleOutline, null) },
                        onClick = { onRemove(s); close() })
                }
            }
        }
    }
    if (renaming) NameDialog("Adını değiştir", playlist.name, onDone = { onRename(it); renaming = false }, onDismiss = { renaming = false })
    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text("\"${playlist.name}\" silinsin mi?") },
        text = { Text("Yalnız liste silinir; şarkılar telefonda kalır.") },
        confirmButton = { TextButton({ confirmDelete = false; onDelete() }) { Text("Sil") } },
        dismissButton = { TextButton({ confirmDelete = false }) { Text("Vazgeç") } },
    )
}

@Composable
private fun AddToPlaylistDialog(
    song: Song, playlists: List<Playlist>, onPick: (Playlist) -> Unit,
    onCreate: (String) -> Unit, onDismiss: () -> Unit,
) {
    var naming by remember { mutableStateOf(playlists.isEmpty()) }
    if (naming) {
        NameDialog("Yeni liste", "", onDone = onCreate, onDismiss = onDismiss)
        return
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Listeye ekle", maxLines = 1) },
        text = {
            LazyColumn(Modifier.heightIn(max = 360.dp)) {
                item {
                    ListItem(headlineContent = { Text("Yeni liste…") }, leadingContent = { Icon(Icons.Default.Add, null) },
                        modifier = Modifier.clickable { naming = true })
                }
                items(playlists, key = { it.id }) { p ->
                    val inside = song.id in p.songIds
                    ListItem(
                        headlineContent = { Text(p.name) },
                        supportingContent = { Text(if (inside) "Zaten listede" else "${p.songIds.size} şarkı") },
                        trailingContent = { if (inside) Icon(Icons.Default.Check, null, tint = Amber) },
                        modifier = Modifier.clickable(enabled = !inside) { onPick(p) },
                    )
                }
            }
        },
        confirmButton = { TextButton(onDismiss) { Text("Kapat") } },
    )
}

@Composable
private fun NameDialog(title: String, initial: String, onDone: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(name, { name = it }, singleLine = true, placeholder = { Text("Liste adı") }) },
        confirmButton = { TextButton({ onDone(name) }, enabled = name.isNotBlank()) { Text("Kaydet") } },
        dismissButton = { TextButton(onDismiss) { Text("Vazgeç") } },
    )
}

// ---------------- çalar ----------------

@Composable
private fun MiniPlayer(now: NowPlaying, onToggle: () -> Unit, onNext: () -> Unit, onOpen: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.clickable(onClick = onOpen)) {
        Column {
            val progress = if (now.durationMs > 0) now.positionMs.toFloat() / now.durationMs else 0f
            LinearProgressIndicator(progress = { progress }, Modifier.fillMaxWidth().height(2.dp), color = Amber, trackColor = Color.Transparent)
            Row(Modifier.fillMaxWidth().padding(start = 12.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                NowArtwork(now, 44.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(now.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                    Text(now.artist, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = onToggle) {
                    Icon(if (now.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, if (now.isPlaying) "Duraklat" else "Çal")
                }
                IconButton(onClick = onNext, enabled = now.hasNext) { Icon(Icons.Default.SkipNext, "Sonraki") }
            }
        }
    }
}

@Composable
private fun FullPlayer(now: NowPlaying, vm: PlayerViewModel, onClose: () -> Unit) {
    var eqOpen by rememberSaveable { mutableStateOf(false) }
    var showLyrics by rememberSaveable { mutableStateOf(false) }
    val eqOn by SoundFx.state.collectAsStateWithLifecycle()
    if (eqOpen) {
        BackHandler { SoundFx.save(); eqOpen = false }
        EqualizerPage(onBack = { SoundFx.save(); eqOpen = false })
        return
    }
    var dragging by remember { mutableStateOf<Float?>(null) }
    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose, modifier = Modifier.offset(x = (-12).dp)) {
                    Icon(Icons.Default.KeyboardArrowDown, "Kapat", Modifier.size(32.dp))
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { eqOpen = true }, modifier = Modifier.offset(x = 12.dp)) {
                    Icon(Icons.Default.Equalizer, "Ekolayzer",
                        tint = if (eqOn.enabled) Amber else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (showLyrics && now.key != null) {
                LyricsPanel(now, vm, Modifier.weight(1f).fillMaxWidth().padding(top = 8.dp, bottom = 16.dp))
            } else {
                Spacer(Modifier.weight(1f))
                NowArtwork(now, 300.dp, corner = 20.dp)
                Spacer(Modifier.height(32.dp))
            }
            Text(now.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
                maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth())
            Text(now.artist, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(16.dp))
            val dur = now.durationMs.coerceAtLeast(1)
            Slider(
                value = dragging ?: (now.positionMs.toFloat() / dur).coerceIn(0f, 1f),
                onValueChange = { dragging = it },
                onValueChangeFinished = { dragging?.let { vm.seekTo((it * dur).toLong()) }; dragging = null },
                colors = SliderDefaults.colors(thumbColor = Amber, activeTrackColor = Amber),
            )
            Row(Modifier.fillMaxWidth()) {
                val shownPos = dragging?.let { (it * dur).toLong() } ?: now.positionMs
                Text(formatTime(shownPos), style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                Text(formatTime(now.durationMs), style = MaterialTheme.typography.labelMedium)
            }
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = vm::toggleShuffle) {
                    Icon(Icons.Default.Shuffle, "Karışık", tint = if (now.shuffle) Amber else MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = vm::previous, modifier = Modifier.size(56.dp)) { Icon(Icons.Default.SkipPrevious, "Önceki", Modifier.size(36.dp)) }
                FilledIconButton(onClick = vm::togglePlay, modifier = Modifier.size(72.dp)) {
                    Icon(if (now.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        if (now.isPlaying) "Duraklat" else "Çal", Modifier.size(40.dp))
                }
                IconButton(onClick = vm::next, enabled = now.hasNext, modifier = Modifier.size(56.dp)) {
                    Icon(Icons.Default.SkipNext, "Sonraki", Modifier.size(36.dp))
                }
                IconButton(onClick = vm::cycleRepeat) {
                    val (icon, on) = when (now.repeatMode) {
                        Player.REPEAT_MODE_ONE -> Icons.Default.RepeatOne to true
                        Player.REPEAT_MODE_ALL -> Icons.Default.Repeat to true
                        else -> Icons.Default.Repeat to false
                    }
                    Icon(icon, "Tekrar", tint = if (on) Amber else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(16.dp))
            // Sözler: yazılı düğme, simge aramaya gerek kalmasın.
            FilledTonalButton(onClick = { showLyrics = !showLyrics }) {
                Icon(if (showLyrics) Icons.Default.Album else Icons.Default.Lyrics, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(if (showLyrics) "Kapağa dön" else "Sözler")
            }
            if (showLyrics) Spacer(Modifier.height(16.dp)) else Spacer(Modifier.weight(1f))
        }
    }
}

// ---------------- şarkı sözleri ----------------

private sealed interface LyricsUi {
    data object Loading : LyricsUi
    data object Offline : LyricsUi
    data class Done(val lyrics: Lyrics) : LyricsUi
}

/** Zamanlı sözlerde çalan satır ortada ve altın renkte; satıra dokununca oraya atlar. */
@Composable
private fun LyricsPanel(now: NowPlaying, vm: PlayerViewModel, modifier: Modifier) {
    val context = LocalContext.current
    val key = now.key ?: return
    var retry by remember { mutableIntStateOf(0) }
    val ui by produceState<LyricsUi>(LyricsUi.Loading, key, retry) {
        value = LyricsUi.Loading
        val song = now.songId?.let { id -> vm.songs.value.firstOrNull { it.id == id } }
        if (song == null && now.durationMs <= 0) kotlinx.coroutines.delay(1500) // akışta süre biraz geç gelir
        value = runCatching {
            LyricsRepo.get(context, key, song?.artist ?: now.artist, song?.title ?: now.title, song?.durationMs ?: now.durationMs)
        }.fold({ LyricsUi.Done(it) }, { LyricsUi.Offline })
    }
    Box(modifier, contentAlignment = Alignment.Center) {
        when (val u = ui) {
            LyricsUi.Loading -> CircularProgressIndicator(color = Amber)
            LyricsUi.Offline -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Sözler için internet gerekiyor.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = { retry++ }) { Text("Tekrar dene") }
            }
            is LyricsUi.Done -> when {
                !u.lyrics.found -> Text("Bu şarkının sözleri bulunamadı.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                u.lyrics.synced.isNotEmpty() -> SyncedLyrics(u.lyrics.synced, now.positionMs, onSeek = vm::seekTo)
                else -> Text(u.lyrics.plain, style = MaterialTheme.typography.titleMedium, lineHeight = MaterialTheme.typography.titleMedium.lineHeight * 1.3f,
                    modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()))
            }
        }
    }
}

@Composable
private fun SyncedLyrics(lines: List<LyricLine>, positionMs: Long, onSeek: (Long) -> Unit) {
    val current = lines.indexOfLast { it.timeMs <= positionMs + 200 }
    val listState = rememberLazyListState()
    LaunchedEffect(current) {
        if (current >= 0) listState.animateScrollToItem((current - 2).coerceAtLeast(0))
    }
    LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(vertical = 24.dp)) {
        itemsIndexed(lines) { i, line ->
            val text = line.text.ifBlank { "♪" }
            Text(
                text,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = if (i == current) FontWeight.Bold else FontWeight.SemiBold,
                color = when {
                    i == current -> Amber
                    i < current -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.fillMaxWidth().clickable { onSeek(line.timeMs) }.padding(vertical = 8.dp),
            )
        }
        item {
            Text("Sözler: LRCLIB", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f), modifier = Modifier.padding(top = 16.dp))
        }
    }
}

// ---------------- ekolayzer ----------------

/** Spotify'daki gibi: üstte sürüklenebilir eğri, altında aç/kapa ve hazır ayar listesi. */
@Composable
private fun EqualizerPage(onBack: () -> Unit) {
    val st by SoundFx.state.collectAsStateWithLifecycle()
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().systemBarsPadding()) {
            Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
                FilledTonalIconButton(onClick = onBack, modifier = Modifier.align(Alignment.CenterStart)) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Geri")
                }
                Text("Ekolayzer", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                    modifier = Modifier.align(Alignment.Center))
            }
            when {
                !st.ready -> Empty("Bir şarkı çalmaya başlayınca ayarlanabilir.")
                !st.supported -> Empty("Bu telefon uygulama içi ekolayzer desteklemiyor.")
                else -> {
                    EqGraph(st, Modifier.fillMaxWidth().height(300.dp).background(MaterialTheme.colorScheme.surfaceContainerLow))
                    LazyColumn(Modifier.fillMaxSize()) {
                        item {
                            Row(Modifier.fillMaxWidth().clickable { SoundFx.setEnabled(!st.enabled) }
                                .padding(horizontal = 20.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text("Ekolayzer", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                                Switch(checked = st.enabled, onCheckedChange = SoundFx::setEnabled,
                                    colors = SwitchDefaults.colors(checkedTrackColor = Amber))
                            }
                        }
                        if (st.preset < 0) item { PresetRow("Özel", selected = true) {} }
                        itemsIndexed(EqPresets.all) { i, (name, _) ->
                            PresetRow(name, selected = st.preset == i) { SoundFx.usePreset(i) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PresetRow(name: String, selected: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f),
            color = if (selected) Amber else MaterialTheme.colorScheme.onSurface)
        if (selected) Icon(Icons.Default.Check, null, tint = Amber)
    }
}

/** Bant noktaları eğri üstünde; noktayı (ya da o sütunu) yukarı-aşağı sürükle. */
@Composable
private fun EqGraph(st: EqState, modifier: Modifier) {
    val n = st.bands.size
    if (n == 0) return
    val measurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelMedium.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
    val valueStyle = MaterialTheme.typography.labelLarge.copy(color = Color.White, fontWeight = FontWeight.Bold)
    val grid = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
    val line = if (st.enabled) Amber else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
    var active by remember { mutableIntStateOf(-1) }
    val stNow by rememberUpdatedState(st)
    val density = LocalDensity.current
    val padX = with(density) { 32.dp.toPx() }
    val padTop = with(density) { 28.dp.toPx() }
    val padBottom = with(density) { 40.dp.toPx() }

    fun xOf(i: Int, w: Float) = padX + i * (w - 2 * padX) / (n - 1).coerceAtLeast(1)
    fun levelFromY(y: Float, h: Float): Int {
        val s = stNow
        val t = ((y - padTop) / (h - padTop - padBottom)).coerceIn(0f, 1f)
        val mb = s.maxMb - t * (s.maxMb - s.minMb)
        return (Math.round(mb / 50f) * 50)
    }
    fun nearest(x: Float, w: Float) = (0 until n).minBy { kotlin.math.abs(xOf(it, w) - x) }

    Canvas(
        modifier
            .pointerInput(n) {
                detectTapGestures { o ->
                    val i = nearest(o.x, size.width.toFloat())
                    SoundFx.setBand(i, levelFromY(o.y, size.height.toFloat())); SoundFx.save()
                }
            }
            .pointerInput(n) {
                detectDragGestures(
                    onDragStart = { o -> active = nearest(o.x, size.width.toFloat()) },
                    onDragEnd = { active = -1; SoundFx.save() },
                    onDragCancel = { active = -1; SoundFx.save() },
                ) { change, _ ->
                    change.consume()
                    if (active >= 0) SoundFx.setBand(active, levelFromY(change.position.y, size.height.toFloat()))
                }
            }
    ) {
        val w = size.width; val h = size.height
        val plotBottom = h - padBottom
        fun yOf(mb: Int) = padTop + (st.maxMb - mb).toFloat() / (st.maxMb - st.minMb) * (plotBottom - padTop)
        // ızgara: her bandın dikey çizgisi ve 0 dB yatay çizgisi
        for (i in 0 until n) drawLine(grid, Offset(xOf(i, w), padTop / 2), Offset(xOf(i, w), plotBottom), strokeWidth = 1f)
        drawLine(grid, Offset(0f, yOf(0)), Offset(w, yOf(0)), strokeWidth = 1f)
        val pts = st.bands.mapIndexed { i, b -> Offset(xOf(i, w), yOf(b.levelMb)) }
        val curve = Path().apply { moveTo(pts[0].x, pts[0].y); pts.drop(1).forEach { lineTo(it.x, it.y) } }
        val fill = Path().apply { addPath(curve); lineTo(pts.last().x, plotBottom); lineTo(pts[0].x, plotBottom); close() }
        drawPath(fill, Brush.verticalGradient(listOf(line.copy(alpha = 0.45f), line.copy(alpha = 0.03f)), startY = padTop, endY = plotBottom))
        drawPath(curve, line, style = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        pts.forEachIndexed { i, p ->
            drawCircle(Color.White, radius = (if (i == active) 13 else 10).dp.toPx(), center = p)
            if (i == active) {
                val t = measurer.measure(db(st.bands[i].levelMb), valueStyle)
                drawText(t, topLeft = Offset(p.x - t.size.width / 2, (p.y - 22.dp.toPx() - t.size.height).coerceAtLeast(0f)))
            }
            val l = measurer.measure(hz(st.bands[i].centerHz), labelStyle)
            drawText(l, topLeft = Offset((p.x - l.size.width / 2).coerceIn(0f, w - l.size.width), plotBottom + 12.dp.toPx()))
        }
    }
}

private fun hz(h: Int) = if (h >= 1000) "%s kHz".format(if (h % 1000 == 0) "${h / 1000}" else "%.1f".format(TR, h / 1000f)) else "$h Hz"
private fun db(mb: Int) = (mb / 100f).let { if (it > 0) "+%.0f dB".format(it) else "%.0f dB".format(it) }

// ---------------- ayarlar ----------------

@Composable
private fun SettingsScreen(vm: PlayerViewModel, onOpenEq: () -> Unit) {
    val eq by SoundFx.state.collectAsStateWithLifecycle()
    val checking by vm.checking.collectAsStateWithLifecycle()
    val note by vm.updateNote.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var lyricsCount by remember { mutableIntStateOf(vm.lyricsCacheCount()) }
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Text("Ayarlar", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 20.dp, top = 20.dp, bottom = 8.dp))
        }
        item { SettingsHeader("Ses") }
        item {
            val presetName = if (eq.preset >= 0) EqPresets.all[eq.preset].first else "Özel"
            ListItem(
                headlineContent = { Text("Ekolayzer") },
                supportingContent = { Text(if (eq.enabled) "Açık · $presetName" else "Kapalı") },
                leadingContent = { Icon(Icons.Default.Equalizer, null, tint = if (eq.enabled) Amber else MaterialTheme.colorScheme.onSurfaceVariant) },
                trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null) },
                modifier = Modifier.clickable(onClick = onOpenEq),
            )
        }
        item { SettingsHeader("Şarkı sözleri") }
        item {
            ListItem(
                headlineContent = { Text("Sözler nerede?") },
                supportingContent = { Text("Çalan şarkıyı aç (alttaki küçük çalara dokun), kontrollerin altındaki Sözler düğmesine bas. Çalan satır altın renkte; bir satıra dokununca şarkı oraya atlar.") },
                leadingContent = { Icon(Icons.Default.Lyrics, null) },
            )
        }
        item {
            ListItem(
                headlineContent = { Text("Kaydedilmiş sözleri sil") },
                supportingContent = { Text(if (lyricsCount == 0) "Kayıtlı söz yok" else "$lyricsCount şarkının sözleri telefonda; sonra yeniden indirilir") },
                leadingContent = { Icon(Icons.Default.DeleteSweep, null) },
                modifier = Modifier.clickable(enabled = lyricsCount > 0) { vm.clearLyricsCache(); lyricsCount = 0 },
            )
        }
        item { SettingsHeader("İndirmeler") }
        item {
            ListItem(
                headlineContent = { Text("Klasör") },
                supportingContent = { Text("Müzik/Ezgi · Ezgi silinse de şarkılar kalır") },
                leadingContent = { Icon(Icons.Default.Folder, null) },
            )
        }
        item { SettingsHeader("Güncelleme") }
        item {
            ListItem(
                headlineContent = { Text("Ezgi ${vm.version}") },
                supportingContent = { Text(note ?: "Açılışta kendiliğinden bakılır") },
                leadingContent = { Icon(Icons.Default.SystemUpdate, null) },
                trailingContent = {
                    if (checking) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp, color = Amber)
                    else FilledTonalButton(onClick = { vm.checkUpdate(manual = true) }) { Text("Denetle") }
                },
            )
        }
        item { SettingsHeader("Hakkında") }
        item {
            ListItem(
                headlineContent = { Text("Kaynak kod") },
                supportingContent = { Text("github.com/osmanevski/Ezgi") },
                leadingContent = { Icon(Icons.Default.Code, null) },
                modifier = Modifier.clickable {
                    context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW,
                        android.net.Uri.parse("https://github.com/osmanevski/Ezgi")))
                },
            )
        }
        item {
            Text("Arama ve indirme YouTube Music'ten (NewPipeExtractor), sözler LRCLIB'den. Reklam yok, hesap yok.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp))
        }
    }
}

@Composable
private fun SettingsHeader(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = Amber,
        modifier = Modifier.padding(start = 20.dp, top = 20.dp, bottom = 4.dp))
}

// ---------------- güncelleme ----------------

@Composable
private fun UpdateDialog(vm: PlayerViewModel) {
    val st by vm.update.collectAsStateWithLifecycle()
    val rel = when (val s = st) {
        is UpdateState.Available -> s.release
        is UpdateState.Downloading -> s.release
        is UpdateState.Ready -> s.release
        is UpdateState.Failed -> s.release
        UpdateState.None -> return
    }
    val busy = st is UpdateState.Downloading
    AlertDialog(
        onDismissRequest = { if (!busy) vm.dismissUpdate() },
        icon = { Icon(Icons.Default.SystemUpdate, null, tint = Amber) },
        title = { Text("Yeni sürüm: ${rel.version}") },
        text = {
            Column {
                Text("Şu an ${vm.version} kullanıyorsun. Listelerin ve şarkıların kalır.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (rel.notes.isNotBlank()) {
                    Spacer(Modifier.height(12.dp))
                    Text(rel.notes, style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.heightIn(max = 220.dp).verticalScroll(rememberScrollState()))
                }
                when (val s = st) {
                    is UpdateState.Downloading -> {
                        Spacer(Modifier.height(16.dp))
                        if (s.progress < 0) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Amber)
                        else LinearProgressIndicator({ s.progress }, Modifier.fillMaxWidth(), color = Amber)
                    }
                    is UpdateState.Ready -> {
                        Spacer(Modifier.height(12.dp))
                        Text("İndi. Açılan ekranda Güncelle'ye bas. İzin ekranı açıldıysa Ezgi'ye izin verip geri dön, sonra Kur'a bas.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    is UpdateState.Failed -> {
                        Spacer(Modifier.height(12.dp))
                        Text(s.reason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                    else -> {}
                }
            }
        },
        confirmButton = {
            Button(onClick = vm::startUpdate, enabled = !busy) {
                Text(when (st) { is UpdateState.Ready -> "Kur"; is UpdateState.Failed -> "Tekrar dene"; else -> "Güncelle" })
            }
        },
        dismissButton = { if (!busy) TextButton(onClick = vm::dismissUpdate) { Text("Sonra") } },
    )
}

// ---------------- kapak ----------------

/** Çalan öğenin kapağı: telefondaki şarkıda gömülü kapak, akışta YouTube kapağı. */
@Composable
private fun NowArtwork(now: NowPlaying, size: Dp, corner: Dp = 8.dp) {
    when {
        now.songId != null -> Artwork(now.songId, size, corner)
        else -> RemoteImage(now.artworkUrl, size, corner)
    }
}

private object ArtCache {
    val bitmaps = LruCache<Long, Bitmap>(80)
    val missing = HashSet<Long>()
}

/** Dosyaya gömülü kapağı okur; yoksa nota simgesi. Küçültülmüş kopya önbellekte tutulur. */
@Composable
private fun Artwork(songId: Long, size: Dp, corner: Dp = 8.dp) {
    val context = LocalContext.current
    // produceState anahtar değişince eski değeri tutuyor; şarkı geçince önceki kapak kalıyordu.
    val art by produceState<Bitmap?>(ArtCache.bitmaps.get(songId), songId) {
        value = ArtCache.bitmaps.get(songId)
        if (value != null || songId in ArtCache.missing) return@produceState
        value = withContext(Dispatchers.IO) {
            runCatching {
                java.io.File(context.filesDir, "kapak/$songId.jpg").takeIf { it.exists() }?.let { f ->
                    BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = 2 })
                } ?: MediaMetadataRetriever().run {
                    try {
                        setDataSource(context, songUri(songId))
                        embeddedPicture?.let { bytes ->
                            val opts = BitmapFactory.Options().apply { inSampleSize = if (bytes.size > 200_000) 4 else 2 }
                            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
                        }
                    } finally { release() }
                }
            }.getOrNull()
        }?.also { ArtCache.bitmaps.put(songId, it) } ?: run { ArtCache.missing += songId; null }
    }
    Box(
        Modifier.size(size).clip(RoundedCornerShape(corner)).background(MaterialTheme.colorScheme.surfaceContainerHigh),
        Alignment.Center,
    ) {
        art?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
            ?: Icon(Icons.Default.MusicNote, null, Modifier.size(size * 0.45f), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Empty(text: String) {
    Box(Modifier.fillMaxSize().padding(32.dp), Alignment.Center) {
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge)
    }
}

private fun formatTime(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(s / 60, s % 60)
}
