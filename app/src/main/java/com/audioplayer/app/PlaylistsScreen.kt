package com.audioplayer.app

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
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
import androidx.compose.ui.graphics.Color
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
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

// Un titre en cours d'édition, avec un identifiant unique (même fichier présent 2 fois = 2 identifiants)
data class EditItem(val key: Int, val track: Track)

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

    // ----- Mode « Réorganiser » -----
    var editMode by remember { mutableStateOf(false) }
    var editItems by remember { mutableStateOf<List<EditItem>>(emptyList()) }
    var selected by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var placing by remember { mutableStateOf(false) }
    var showPosDialog by remember { mutableStateOf(false) }
    var posText by remember { mutableStateOf("") }

    // Quitter une playlist désactive le mode « Réorganiser »
    LaunchedEffect(openId) {
        editMode = false
        placing = false
        selected = emptySet()
    }

    fun savePlaylists(newList: List<Playlist>) {
        playlists = newList
        store.save(newList)
    }

    fun addTracks(playlistId: String, tracks: List<Track>) {
        savePlaylists(playlists.map {
            if (it.id == playlistId) it.copy(tracks = it.tracks + tracks) else it
        })
    }

    // Enregistre l'ordre en cours d'édition dans la playlist ouverte
    fun commitEdit() {
        val id = openId ?: return
        val tracks = editItems.map { it.track }
        savePlaylists(playlists.map { if (it.id == id) it.copy(tracks = tracks) else it })
    }

    fun startEdit(tracks: List<Track>) {
        editItems = tracks.mapIndexed { i, t -> EditItem(i, t) }
        selected = emptySet()
        placing = false
        editMode = true
    }

    fun stopEdit() {
        commitEdit()
        editMode = false
        placing = false
        selected = emptySet()
    }

    // Déplace tout le groupe sélectionné (il garde son ordre interne).
    // « index » donne la position d'insertion dans la liste sans les titres sélectionnés.
    fun moveSelectedTo(index: (List<EditItem>) -> Int) {
        val moving = editItems.filter { it.key in selected }
        if (moving.isEmpty()) return
        val rest = editItems.filter { it.key !in selected }
        val at = index(rest).coerceIn(0, rest.size)
        editItems = rest.take(at) + moving + rest.drop(at)
        commitEdit()
    }

    val lazyListState = rememberLazyListState()
    val reorderableState = rememberReorderableLazyListState(lazyListState) { from, to ->
        editItems = editItems.toMutableList().apply { add(to.index, removeAt(from.index)) }
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

    // Bouton retour du téléphone : placement -> édition -> détail -> liste -> lecteur
    BackHandler {
        when {
            placing -> placing = false
            editMode -> stopEdit()
            openId != null -> openId = null
            else -> onBack()
        }
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
                IconButton(onClick = { if (editMode) stopEdit(); openId = null }) {
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
                Button(onClick = { filePicker.launch(arrayOf("audio/*")) }, enabled = !editMode) { Text("+ Fichiers") }
                Spacer(Modifier.width(8.dp))
                Button(onClick = { folderPicker.launch(null) }, enabled = !editMode) { Text("+ Dossier") }
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = { playFrom(current.tracks, 0) },
                    enabled = current.tracks.isNotEmpty() && !editMode
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
                    onClick = { if (editMode) stopEdit() else startEdit(current.tracks) },
                    enabled = current.tracks.size > 1
                ) { Text(if (editMode) "Terminer" else "Réorganiser") }
            }

            if (editMode) {
                // ----- BARRE D'OUTILS DE SÉLECTION -----
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        when {
                            placing -> "Touchez le titre après lequel placer les ${selected.size} titres."
                            selected.isEmpty() -> "Touchez des titres pour les sélectionner. Glissez ≡ pour déplacer."
                            else -> "${selected.size} sélectionné(s)"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f)
                    )
                    if (placing) {
                        TextButton(onClick = { placing = false }) { Text("Annuler") }
                    } else {
                        TextButton(onClick = {
                            selected = if (selected.isEmpty()) editItems.map { it.key }.toSet() else emptySet()
                        }) { Text(if (selected.isEmpty()) "Tout" else "Aucun") }
                        if (selected.isNotEmpty()) {
                            IconButton(onClick = {
                                editItems = editItems.filter { it.key !in selected }
                                selected = emptySet()
                                commitEdit()
                            }) { Icon(Icons.Filled.Delete, "Retirer la sélection") }
                        }
                    }
                }
                if (selected.isNotEmpty() && !placing) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        TextButton(onClick = {
                            moveSelectedTo { 0 }
                            selected = emptySet()
                            scope.launch { lazyListState.scrollToItem(0) }
                        }) { Text("↑ Haut") }
                        TextButton(onClick = {
                            moveSelectedTo { it.size }
                            selected = emptySet()
                            scope.launch { lazyListState.scrollToItem((editItems.size - 1).coerceAtLeast(0)) }
                        }) { Text("↓ Bas") }
                        TextButton(onClick = { posText = ""; showPosDialog = true }) { Text("N°…") }
                        TextButton(onClick = { placing = true }) { Text("Placer…") }
                    }
                }

                // ----- LISTE RÉORGANISABLE -----
                LazyColumn(state = lazyListState) {
                    itemsIndexed(editItems, key = { _, item -> item.key }) { index, item ->
                        ReorderableItem(reorderableState, key = item.key) { isDragging ->
                            val isSelected = item.key in selected
                            val bg = when {
                                isDragging -> MaterialTheme.colorScheme.surfaceVariant
                                isSelected -> MaterialTheme.colorScheme.primaryContainer
                                else -> Color.Transparent
                            }
                            Row(
                                Modifier.fillMaxWidth()
                                    .background(bg)
                                    .clickable {
                                        if (placing) {
                                            if (!isSelected) {
                                                moveSelectedTo { rest ->
                                                    rest.indexOfFirst { it.key == item.key } + 1
                                                }
                                                selected = emptySet()
                                                placing = false
                                            }
                                        } else {
                                            selected = if (isSelected) selected - item.key else selected + item.key
                                        }
                                    },
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(
                                    checked = isSelected,
                                    enabled = !placing,
                                    onCheckedChange = {
                                        selected = if (isSelected) selected - item.key else selected + item.key
                                    }
                                )
                                Text(
                                    "${index + 1}.",
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.width(40.dp)
                                )
                                Text(
                                    item.track.title,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                IconButton(
                                    modifier = Modifier.draggableHandle(
                                        onDragStopped = { commitEdit() }
                                    ),
                                    onClick = {}
                                ) {
                                    Icon(Icons.Filled.DragHandle, "Glisser pour déplacer")
                                }
                            }
                        }
                    }
                }
            } else {
                // ----- LISTE NORMALE -----
                LazyColumn {
                    itemsIndexed(current.tracks) { index, t ->
                        Row(
                            Modifier.fillMaxWidth()
                                .clickable { playFrom(current.tracks, index) }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                t.title,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
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

    // Fenêtre : position précise pour le groupe sélectionné
    if (showPosDialog) {
        AlertDialog(
            onDismissRequest = { showPosDialog = false },
            title = { Text("Placer la sélection") },
            text = {
                OutlinedTextField(
                    value = posText,
                    onValueChange = { posText = it.filter { c -> c.isDigit() } },
                    singleLine = true,
                    label = { Text("Position du 1er titre (1 à ${editItems.size - selected.size + 1})") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val pos = posText.toIntOrNull()
                    if (pos != null) {
                        moveSelectedTo { pos - 1 }
                        selected = emptySet()
                        scope.launch { lazyListState.scrollToItem((pos - 1).coerceAtLeast(0)) }
                    }
                    showPosDialog = false
                }) { Text("Déplacer") }
            },
            dismissButton = {
                TextButton(onClick = { showPosDialog = false }) { Text("Annuler") }
            }
        )
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
