package com.audioplayer.app

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun PlaylistsScreen(player: Player, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { PlaylistStore(context) }

    var playlists by remember { mutableStateOf(store.load()) }
    var openId by remember { mutableStateOf<String?>(null) }
    var showNewDialog by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    var toDelete by remember { mutableStateOf<Playlist?>(null) }
    var loading by remember { mutableStateOf(false) }
    var editMode by remember { mutableStateOf(false) }
    var moveFrom by remember { mutableStateOf<Int?>(null) }
    var moveText by remember { mutableStateOf("") }

    // Quitter une playlist désactive le mode « Réorganiser »
    LaunchedEffect(openId) { editMode = false }

    fun savePlaylists(newList: List<Playlist>) {
        playlists = newList
        store.save(newList)
    }

    fun addTracks(playlistId: String, tracks: List<Track>) {
        savePlaylists(playlists.map {
            if (it.id == playlistId) it.copy(tracks = it.tracks + tracks) else it
        })
    }

    // Déplace un titre de la position « from » vers la position « to » (numérotées à partir de 0)
    fun moveTrack(playlistId: String, from: Int, to: Int) {
        savePlaylists(playlists.map { p ->
            if (p.id != playlistId || from !in p.tracks.indices) {
                p
            } else {
                val target = to.coerceIn(0, p.tracks.size - 1)
                val list = p.tracks.toMutableList()
                val track = list.removeAt(from)
                list.add(target, track)
                p.copy(tracks = list)
            }
        })
    }

    fun playFrom(tracks: List<Track>, index: Int) {
        val items = tracks.map { t ->
            MediaItem.Builder()
                .setUri(t.uri)
                .setMediaMetadata(MediaMetadata.Builder().setTitle(t.title).build())
                .setRequestMetadata(
                    MediaItem.RequestMetadata.Builder().setMediaUri(Uri.parse(t.uri)).build()
                )
                .build()
        }
        player.setMediaItems(items, index, 0L)
        player.prepare()
        player.play()
        onBack()
    }

    // Ajout de fichiers à la playlist ouverte
    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        val id = openId
        if (id != null && uris.isNotEmpty()) {
            uris.forEach { uri ->
                try {
                    context.contentResolver.takePersistableUriPermission(
                        uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                } catch (e: Exception) { /* pas grave */ }
            }
            addTracks(id, uris.map { uri ->
                val name = DocumentFile.fromSingleUri(context, uri)?.name
                Track(uri.toString(), name?.substringBeforeLast('.') ?: "Titre")
            })
        }
    }

    // Ajout d'un dossier (avec sous-dossiers) à la playlist ouverte
    val folderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { treeUri ->
        val id = openId
        if (id != null && treeUri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (e: Exception) { /* pas grave */ }

            loading = true
            scope.launch {
                val files = withContext(Dispatchers.IO) {
                    val list = mutableListOf<DocumentFile>()
                    DocumentFile.fromTreeUri(context, treeUri)?.let { collectAudio(it, list) }
                    list
                }
                loading = false
                if (files.isEmpty()) {
                    Toast.makeText(context, "Aucun fichier audio trouvé", Toast.LENGTH_LONG).show()
                } else {
                    addTracks(id, files.map {
                        Track(it.uri.toString(), it.name?.substringBeforeLast('.') ?: "Titre")
                    })
                    Toast.makeText(context, "${files.size} titres ajoutés", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // Bouton retour du téléphone : détail -> liste -> lecteur
    BackHandler {
        if (openId != null) openId = null else onBack()
    }

    val current = playlists.firstOrNull { it.id == openId }

    Column(Modifier.fillMaxSize().systemBarsPadding().padding(16.dp)) {
        if (current == null) {
            // ----- LISTE DES PLAYLISTS -----
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Retour")
                }
                Text(
                    "Playlists",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f)
                )
                Button(onClick = { newName = ""; showNewDialog = true }) { Text("Nouvelle") }
            }
            if (playlists.isEmpty()) {
                Text("Aucune playlist. Touchez « Nouvelle ».", Modifier.padding(16.dp))
            }
            LazyColumn {
                items(playlists, key = { it.id }) { p ->
                    Row(
                        Modifier.fillMaxWidth().clickable { openId = p.id }.padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(p.name, style = MaterialTheme.typography.titleMedium)
                            Text("${p.tracks.size} titres", style = MaterialTheme.typography.bodySmall)
                        }
                        IconButton(onClick = { toDelete = p }) {
                            Icon(Icons.Filled.Delete, "Supprimer la playlist")
                        }
                    }
                }
            }
        } else {
            // ----- DÉTAIL D'UNE PLAYLIST -----
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { openId = null }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Retour")
                }
                Text(
                    current.name,
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
            }
            Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = { filePicker.launch(arrayOf("audio/*")) }) { Text("+ Fichiers") }
                Spacer(Modifier.width(8.dp))
                Button(onClick = { folderPicker.launch(null) }) { Text("+ Dossier") }
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = { playFrom(current.tracks, 0) },
                    enabled = current.tracks.isNotEmpty()
                ) { Text("Lire") }
            }
            if (loading) Text("Analyse du dossier…")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${current.tracks.size} titres",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f)
                )
                TextButton(
                    onClick = { editMode = !editMode },
                    enabled = current.tracks.size > 1
                ) { Text(if (editMode) "Terminer" else "Réorganiser") }
            }
            if (editMode) {
                Text(
                    "Touchez ↑ ↓ pour déplacer d'un cran, ou touchez un titre pour choisir sa position.",
                    style = MaterialTheme.typography.bodySmall
                )
            }

            LazyColumn {
                itemsIndexed(current.tracks) { index, t ->
                    Row(
                        Modifier.fillMaxWidth()
                            .clickable {
                                if (editMode) {
                                    moveFrom = index
                                    moveText = (index + 1).toString()
                                } else {
                                    playFrom(current.tracks, index)
                                }
                            }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (editMode) {
                            Text(
                                "${index + 1}.",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.width(44.dp)
                            )
                        }
                        Text(
                            t.title,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        if (editMode) {
                            IconButton(
                                enabled = index > 0,
                                onClick = { moveTrack(current.id, index, index - 1) }
                            ) {
                                Icon(Icons.Filled.KeyboardArrowUp, "Monter")
                            }
                            IconButton(
                                enabled = index < current.tracks.size - 1,
                                onClick = { moveTrack(current.id, index, index + 1) }
                            ) {
                                Icon(Icons.Filled.KeyboardArrowDown, "Descendre")
                            }
                        } else {
                            IconButton(onClick = {
                                savePlaylists(playlists.map {
                                    if (it.id == current.id)
                                        it.copy(tracks = it.tracks.filterIndexed { i, _ -> i != index })
                                    else it
                                })
                            }) {
                                Icon(Icons.Filled.Delete, "Retirer le titre")
                            }
                        }
                    }
                }
            }
        }
    }

    // Fenêtre : nouvelle playlist
    if (showNewDialog) {
        AlertDialog(
            onDismissRequest = { showNewDialog = false },
            title = { Text("Nouvelle playlist") },
            text = {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    singleLine = true,
                    label = { Text("Nom") }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (newName.isNotBlank()) {
                        val p = Playlist(store.newId(), newName.trim(), emptyList())
                        savePlaylists(playlists + p)
                        openId = p.id
                    }
                    showNewDialog = false
                }) { Text("Créer") }
            },
            dismissButton = {
                TextButton(onClick = { showNewDialog = false }) { Text("Annuler") }
            }
        )
    }

    // Fenêtre : choisir la nouvelle position d'un titre
    moveFrom?.let { from ->
        AlertDialog(
            onDismissRequest = { moveFrom = null },
            title = { Text("Déplacer le titre") },
            text = {
                OutlinedTextField(
                    value = moveText,
                    onValueChange = { moveText = it.filter { c -> c.isDigit() } },
                    singleLine = true,
                    label = { Text("Nouvelle position (1 à ${current?.tracks?.size ?: 1})") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val pos = moveText.toIntOrNull()
                    if (pos != null && current != null) moveTrack(current.id, from, pos - 1)
                    moveFrom = null
                }) { Text("Déplacer") }
            },
            dismissButton = {
                TextButton(onClick = { moveFrom = null }) { Text("Annuler") }
            }
        )
    }

    // Fenêtre : confirmer la suppression
    toDelete?.let { p ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text("Supprimer « ${p.name} » ?") },
            text = { Text("Les fichiers de musique ne sont pas effacés.") },
            confirmButton = {
                TextButton(onClick = {
                    savePlaylists(playlists.filter { it.id != p.id })
                    toDelete = null
                }) { Text("Supprimer") }
            },
            dismissButton = {
                TextButton(onClick = { toDelete = null }) { Text("Annuler") }
            }
        )
    }
}
