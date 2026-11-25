package com.audioplayer.app

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import com.audioplayer.app.ui.theme.AudioPlayerTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller by mutableStateOf<MediaController?>(null)

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Android 13+ : autorisation d'afficher la notification du lecteur
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        enableEdgeToEdge()   // icônes de la barre d'état lisibles (clair/sombre)
        setContent {
            AudioPlayerTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                var showPlaylists by remember { mutableStateOf(false) }
                val c = controller
                if (c != null) {
                    if (showPlaylists) {
                        PlaylistsScreen(c) { showPlaylists = false }
                    } else {
                        PlayerScreen(c) { showPlaylists = true }
                    }
                } else {
                    Column(
                        Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) { Text("Connexion au lecteur…") }
                }
            }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // Ne se reconnecte que si ce n'est pas déjà fait
        if (controllerFuture == null) {
            val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
            val future = MediaController.Builder(this, token).buildAsync()
            controllerFuture = future
            future.addListener(
                {
                    val c = future.get()
                    controller = c
                    restoreLastSession(c)
                },
                MoreExecutors.directExecutor()
            )
        }
    }

    // Au lancement : relance la dernière liste, au dernier titre et à la dernière position
    private fun restoreLastSession(c: MediaController) {
        if (c.mediaItemCount > 0) return   // une lecture est déjà en cours
        val saved = LastSessionStore(this).load() ?: return
        c.shuffleModeEnabled = saved.shuffle
        c.setMediaItems(saved.items, saved.index, saved.positionMs)
        c.prepare()
        c.play()
    }

    override fun onDestroy() {
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
        controller = null
        super.onDestroy()
    }
}

@Composable
fun PlayerScreen(player: Player, onOpenPlaylists: () -> Unit) {
    val context = LocalContext.current
    var isPlaying by remember { mutableStateOf(player.isPlaying) }
    var shuffleOn by remember { mutableStateOf(player.shuffleModeEnabled) }
    var metadata by remember { mutableStateOf(player.mediaMetadata) }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }
            override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
                metadata = mediaMetadata
            }
            override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
                shuffleOn = shuffleModeEnabled
            }
        }
        player.addListener(listener)
        isPlaying = player.isPlaying
        metadata = player.mediaMetadata
        shuffleOn = player.shuffleModeEnabled
        onDispose { player.removeListener(listener) }
    }

    // Transforme la pochette (octets) en image, uniquement quand elle change
    val artwork = remember(metadata.artworkData) {
        metadata.artworkData?.let { data ->
            BitmapFactory.decodeByteArray(data, 0, data.size)?.asImageBitmap()
        }
    }

    var position by remember { mutableStateOf(0L) }
    var duration by remember { mutableStateOf(0L) }
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableStateOf(0f) }

    // Relit la position du lecteur deux fois par seconde
    LaunchedEffect(player) {
        while (true) {
            duration = player.duration.coerceAtLeast(0L)
            if (!dragging) position = player.currentPosition.coerceAtLeast(0L)
            delay(500)
        }
    }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) {
            // Garde l'accès aux fichiers même après fermeture de l'appli
            uris.forEach { uri ->
                try {
                    context.contentResolver.takePersistableUriPermission(
                        uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                } catch (e: Exception) { /* pas grave */ }
            }
            val items = uris.map { uri ->
                MediaItem.Builder()
                    .setUri(uri)
                    .setRequestMetadata(
                        MediaItem.RequestMetadata.Builder().setMediaUri(uri).build()
                    )
                    .build()
            }
            player.setMediaItems(items)
            player.prepare()
            player.play()
        }
    }

    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(false) }

    val folderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { treeUri ->
        if (treeUri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (e: Exception) { /* pas grave */ }

            loading = true
            scope.launch {
                // Le parcours se fait en arrière-plan pour ne pas figer l'écran
                val files = withContext(Dispatchers.IO) {
                    val list = mutableListOf<DocumentFile>()
                    DocumentFile.fromTreeUri(context, treeUri)?.let { collectAudio(it, list) }
                    list
                }
                loading = false
                if (files.isEmpty()) {
                    Toast.makeText(context, "Aucun fichier audio trouvé", Toast.LENGTH_LONG).show()
                } else {
                    val items = files.map { f ->
                        MediaItem.Builder()
                            .setUri(f.uri)
                            .setMediaMetadata(
                                // Nom du fichier affiché si le fichier n'a pas de titre intégré
                                MediaMetadata.Builder()
                                    .setTitle(f.name?.substringBeforeLast('.'))
                                    .build()
                            )
                            .setRequestMetadata(
                                MediaItem.RequestMetadata.Builder().setMediaUri(f.uri).build()
                            )
                            .build()
                    }
                    player.setMediaItems(items)
                    player.prepare()
                    player.play()
                }
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().systemBarsPadding().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // ----- HAUT : boutons de sélection -----
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { picker.launch(arrayOf("audio/*")) }) {
                Text("Fichiers")
            }
            Spacer(Modifier.width(12.dp))
            Button(onClick = { folderPicker.launch(null) }) {
                Text("Dossier")
            }
            Spacer(Modifier.width(12.dp))
            Button(onClick = onOpenPlaylists) {
                Text("Playlists")
            }
        }
        if (loading) Text("Analyse du dossier…")

        // ----- MILIEU : pochette, titre, artiste (prend la place restante) -----
        Column(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.8f)
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(16.dp)),
                contentAlignment = Alignment.Center
            ) {
                if (artwork != null) {
                    Image(
                        bitmap = artwork,
                        contentDescription = "Pochette",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Icon(
                        Icons.Filled.MusicNote,
                        contentDescription = "Pas de pochette",
                        modifier = Modifier.size(96.dp)
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            Text(
                text = metadata.title?.toString() ?: "Aucun titre",
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center,
                minLines = 2,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = metadata.artist?.toString() ?: "",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                minLines = 1,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        // ----- BAS : barre de progression et commandes (position fixe) -----
        Slider(
            value = if (dragging) dragValue else position.toFloat(),
            onValueChange = {
                dragging = true
                dragValue = it
            },
            onValueChangeFinished = {
                player.seekTo(dragValue.toLong())
                position = dragValue.toLong()
                dragging = false
            },
            valueRange = 0f..duration.toFloat().coerceAtLeast(1f),
            modifier = Modifier.fillMaxWidth()
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(formatTime(if (dragging) dragValue.toLong() else position))
            Text(formatTime(duration))
        }

        Spacer(Modifier.height(16.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { player.shuffleModeEnabled = !shuffleOn }) {
                Icon(
                    Icons.Filled.Shuffle,
                    if (shuffleOn) "Aléatoire activé" else "Aléatoire désactivé",
                    tint = if (shuffleOn) MaterialTheme.colorScheme.primary
                           else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                )
            }
            IconButton(onClick = { player.seekToPreviousMediaItem() }) {
                Icon(Icons.Filled.SkipPrevious, "Précédent", Modifier.size(48.dp))
            }
            IconButton(onClick = { if (isPlaying) player.pause() else player.play() }) {
                Icon(
                    if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    if (isPlaying) "Pause" else "Lecture",
                    Modifier.size(64.dp)
                )
            }
            IconButton(onClick = { player.seekToNextMediaItem() }) {
                Icon(Icons.Filled.SkipNext, "Suivant", Modifier.size(48.dp))
            }
            Spacer(Modifier.size(48.dp))
        }
    }
}

fun formatTime(ms: Long): String {
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}

private val audioExtensions =
    setOf("mp3", "m4a", "aac", "flac", "ogg", "opus", "wav", "wma", "mka")

// Parcourt un dossier et tous ses sous-dossiers, triés par nom
fun collectAudio(folder: DocumentFile, out: MutableList<DocumentFile>) {
    val children = folder.listFiles().sortedBy { it.name?.lowercase() ?: "" }
    for (child in children) {
        if (child.isDirectory) {
            collectAudio(child, out)
        } else {
            val ext = child.name?.substringAfterLast('.', "")?.lowercase()
            if (child.type?.startsWith("audio/") == true || ext in audioExtensions) {
                out.add(child)
            }
        }
    }
}
