package com.audioplayer.app

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

class PlaybackService : MediaLibraryService() {

    private var mediaSession: MediaLibrarySession? = null

    // Mémoire de la dernière session (liste, titre, position)
    private val store by lazy { LastSessionStore(this) }
    private val handler = Handler(Looper.getMainLooper())
    private val positionSaver = object : Runnable {
        override fun run() {
            savePosition()
            handler.postDelayed(this, 5000)
        }
    }

    private fun savePosition() {
        val p = mediaSession?.player ?: return
        if (p.mediaItemCount == 0) return
        store.savePosition(p.currentMediaItemIndex, p.currentPosition, p.shuffleModeEnabled)
    }

    private fun rememberQueue(items: List<MediaItem>): List<MediaItem> {
        store.saveQueue(items)
        return items
    }

    companion object {
        private const val ROOT_ID = "root"
        private const val PL_PREFIX = "pl:"   // une playlist : pl:<id>
        private const val TR_PREFIX = "tr:"   // un titre : tr:<id playlist>:<numéro>
        private const val PLAY_PREFIX = "plplay:"     // lire toute la playlist dans l'ordre
        private const val SHUF_PREFIX = "plshuffle:"  // lire toute la playlist en aléatoire
    }

    override fun onCreate() {
        super.onCreate()

        val audioAttributes = AudioAttributes.Builder()
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .setUsage(C.USAGE_MEDIA)
            .build()

        val player = ExoPlayer.Builder(this)
            .setAudioAttributes(audioAttributes, true)   // gère le focus audio (appels, autres applis)
            .setHandleAudioBecomingNoisy(true)           // pause si le casque est débranché
            .setWakeMode(C.WAKE_MODE_LOCAL)              // garde la lecture active écran éteint
            .build()

        // Recommence la liste quand elle arrive à la fin
        player.repeatMode = Player.REPEAT_MODE_ALL

        // Sauvegarde la position : toutes les 5 s pendant la lecture, et à chaque changement
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                handler.removeCallbacks(positionSaver)
                if (isPlaying) handler.post(positionSaver) else savePosition()
            }
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                savePosition()
            }
            override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
                savePosition()
            }
        })

        // Ce qui s'ouvre quand on touche la notification
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val sessionActivity = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        mediaSession = MediaLibrarySession.Builder(this, player, LibraryCallback())
            .setSessionActivity(sessionActivity)
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? =
        mediaSession

    // Si on ferme l'appli depuis les applis récentes et que rien ne joue, on arrête le service
    override fun onTaskRemoved(rootIntent: Intent?) {
        savePosition()
        val player = mediaSession?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        handler.removeCallbacks(positionSaver)
        savePosition()
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        super.onDestroy()
    }

    // ---------- Construction des éléments affichés dans la voiture ----------

    private fun rootItem(): MediaItem =
        MediaItem.Builder()
            .setMediaId(ROOT_ID)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle("Audio Player")
                    .setIsBrowsable(true)
                    .setIsPlayable(false)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                    .build()
            )
            .build()

    private fun playlistItem(p: Playlist): MediaItem =
        MediaItem.Builder()
            .setMediaId(PL_PREFIX + p.id)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(p.name)
                    .setSubtitle("${p.tracks.size} titres")
                    .setIsBrowsable(true)
                    .setIsPlayable(false)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_PLAYLIST)
                    .build()
            )
            .build()

    private fun actionItem(mediaId: String, title: String): MediaItem =
        MediaItem.Builder()
            .setMediaId(mediaId)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setIsBrowsable(false)
                    .setIsPlayable(true)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_PLAYLIST)
                    .build()
            )
            .build()

    // Les deux entrées affichées en tête de chaque playlist dans la voiture
    private fun playlistActions(playlistId: String): List<MediaItem> = listOf(
        actionItem(PLAY_PREFIX + playlistId, "Lire la playlist (dans l'ordre)"),
        actionItem(SHUF_PREFIX + playlistId, "Lecture aléatoire (shuffle)")
    )

    private fun trackItem(playlistId: String, index: Int, t: Track): MediaItem {
        val uri = Uri.parse(t.uri)
        return MediaItem.Builder()
            .setMediaId("$TR_PREFIX$playlistId:$index")
            .setUri(uri)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(t.title)
                    .setIsBrowsable(false)
                    .setIsPlayable(true)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                    .build()
            )
            .setRequestMetadata(MediaItem.RequestMetadata.Builder().setMediaUri(uri).build())
            .build()
    }

    private fun playlistItems(playlistId: String): List<MediaItem> {
        val p = PlaylistStore(this).load().firstOrNull { it.id == playlistId } ?: return emptyList()
        return p.tracks.mapIndexed { i, t -> trackItem(playlistId, i, t) }
    }

    // Retrouve un élément à partir de son identifiant
    private fun resolve(mediaId: String): MediaItem? {
        if (mediaId == ROOT_ID) return rootItem()
        if (mediaId.startsWith(PL_PREFIX)) {
            val id = mediaId.removePrefix(PL_PREFIX)
            return PlaylistStore(this).load().firstOrNull { it.id == id }?.let { playlistItem(it) }
        }
        if (mediaId.startsWith(PLAY_PREFIX)) return playlistActions(mediaId.substringAfter(':'))[0]
        if (mediaId.startsWith(SHUF_PREFIX)) return playlistActions(mediaId.substringAfter(':'))[1]
        if (mediaId.startsWith(TR_PREFIX)) {
            val parts = mediaId.removePrefix(TR_PREFIX).split(":")
            if (parts.size == 2) {
                val index = parts[1].toIntOrNull() ?: return null
                return playlistItems(parts[0]).getOrNull(index)
            }
        }
        return null
    }

    // Complète un morceau reçu : titre de playlist -> vrai fichier, ou URI venant de l'appli
    private fun fixItem(item: MediaItem): MediaItem {
        if (item.mediaId.startsWith(TR_PREFIX)) {
            resolve(item.mediaId)?.let { return it }
        }
        val uri = item.requestMetadata.mediaUri ?: item.localConfiguration?.uri
        return if (uri != null) item.buildUpon().setUri(uri).build() else item
    }

    // ---------- Ce que la voiture et l'appli peuvent demander ----------

    private inner class LibraryCallback : MediaLibrarySession.Callback {

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<MediaItem>> =
            Futures.immediateFuture(LibraryResult.ofItem(rootItem(), params))

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            val all: List<MediaItem> = when {
                parentId == ROOT_ID ->
                    PlaylistStore(this@PlaybackService).load().map { playlistItem(it) }
                parentId.startsWith(PL_PREFIX) -> {
                    val id = parentId.removePrefix(PL_PREFIX)
                    val tracks = playlistItems(id)
                    if (tracks.isEmpty()) tracks else playlistActions(id) + tracks
                }
                else -> emptyList()
            }
            val from = (page * pageSize).coerceAtMost(all.size)
            val to = (from + pageSize).coerceAtMost(all.size)
            return Futures.immediateFuture(
                LibraryResult.ofItemList(ImmutableList.copyOf(all.subList(from, to)), params)
            )
        }

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String
        ): ListenableFuture<LibraryResult<MediaItem>> {
            val item = resolve(mediaId)
            return Futures.immediateFuture(
                if (item != null) LibraryResult.ofItem(item, null)
                else LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE)
            )
        }

        // Quand on touche un titre dans la voiture : on charge toute la playlist
        // en commençant par ce titre.
        override fun onSetMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
            startIndex: Int,
            startPositionMs: Long
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            if (mediaItems.size == 1) {
                val id = mediaItems[0].mediaId
                if (id.startsWith(PLAY_PREFIX) || id.startsWith(SHUF_PREFIX)) {
                    val shuffle = id.startsWith(SHUF_PREFIX)
                    val items = playlistItems(id.substringAfter(':'))
                    if (items.isNotEmpty()) {
                        mediaSession.player.shuffleModeEnabled = shuffle
                        val start = if (shuffle) items.indices.random() else 0
                        return Futures.immediateFuture(
                            MediaSession.MediaItemsWithStartPosition(rememberQueue(items), start, C.TIME_UNSET)
                        )
                    }
                }
            }
            if (mediaItems.size == 1 && mediaItems[0].mediaId.startsWith(TR_PREFIX)) {
                val parts = mediaItems[0].mediaId.removePrefix(TR_PREFIX).split(":")
                if (parts.size == 2) {
                    val index = parts[1].toIntOrNull()
                    val items = playlistItems(parts[0])
                    if (index != null && index in items.indices) {
                        return Futures.immediateFuture(
                            MediaSession.MediaItemsWithStartPosition(rememberQueue(items), index, startPositionMs)
                        )
                    }
                }
            }
            return Futures.immediateFuture(
                MediaSession.MediaItemsWithStartPosition(
                    rememberQueue(mediaItems.map { fixItem(it) }),
                    startIndex,
                    startPositionMs
                )
            )
        }

        // Bouton play du casque alors que l'appli est fermée : on reprend la dernière session
        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            val saved = store.load()
            return if (saved != null) {
                mediaSession.player.shuffleModeEnabled = saved.shuffle
                Futures.immediateFuture(
                    MediaSession.MediaItemsWithStartPosition(saved.items, saved.index, saved.positionMs)
                )
            } else {
                Futures.immediateFailedFuture<MediaSession.MediaItemsWithStartPosition>(
                    UnsupportedOperationException("Rien à reprendre")
                )
            }
        }

        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>
        ): ListenableFuture<MutableList<MediaItem>> =
            Futures.immediateFuture(mediaItems.map { fixItem(it) }.toMutableList())
    }
}
