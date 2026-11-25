package com.audioplayer.app

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class SavedSession(
    val items: List<MediaItem>,
    val index: Int,
    val positionMs: Long,
    val shuffle: Boolean
)

// Mémorise la dernière liste lue, le titre en cours, la position et le mode aléatoire
class LastSessionStore(context: Context) {
    private val queueFile = File(context.filesDir, "last_queue.json")
    private val prefs = context.getSharedPreferences("last_session", Context.MODE_PRIVATE)

    // La liste de titres (écrite seulement quand une nouvelle liste est lancée)
    fun saveQueue(items: List<MediaItem>) {
        val arr = JSONArray()
        items.forEach { item ->
            val uri = item.localConfiguration?.uri ?: item.requestMetadata.mediaUri
            if (uri != null) {
                arr.put(
                    JSONObject()
                        .put("uri", uri.toString())
                        .put("title", item.mediaMetadata.title?.toString() ?: "")
                )
            }
        }
        val text = arr.toString()
        Thread {
            try { queueFile.writeText(text) } catch (e: Exception) { /* pas grave */ }
        }.start()
    }

    // Le titre en cours et la position (écrits très souvent, donc très léger)
    fun savePosition(index: Int, positionMs: Long, shuffle: Boolean) {
        prefs.edit()
            .putInt("index", index)
            .putLong("position", positionMs)
            .putBoolean("shuffle", shuffle)
            .apply()
    }

    fun load(): SavedSession? {
        if (!queueFile.exists()) return null
        return try {
            val arr = JSONArray(queueFile.readText())
            val items = (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                val uri = android.net.Uri.parse(o.getString("uri"))
                val title = o.optString("title", "")
                MediaItem.Builder()
                    .setUri(uri)
                    .setMediaMetadata(
                        MediaMetadata.Builder()
                            .apply { if (title.isNotEmpty()) setTitle(title) }
                            .build()
                    )
                    .setRequestMetadata(MediaItem.RequestMetadata.Builder().setMediaUri(uri).build())
                    .build()
            }
            if (items.isEmpty()) return null
            val index = prefs.getInt("index", 0).let { if (it in items.indices) it else 0 }
            SavedSession(items, index, prefs.getLong("position", 0L), prefs.getBoolean("shuffle", false))
        } catch (e: Exception) {
            null
        }
    }
}
