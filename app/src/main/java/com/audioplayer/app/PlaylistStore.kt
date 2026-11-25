package com.audioplayer.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class Track(val uri: String, val title: String)

data class Playlist(val id: String, val name: String, val tracks: List<Track>)

class PlaylistStore(context: Context) {
    private val file = File(context.filesDir, "playlists.json")

    fun load(): List<Playlist> {
        if (!file.exists()) return emptyList()
        return try {
            val arr = JSONArray(file.readText())
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                val t = o.getJSONArray("tracks")
                Playlist(
                    o.getString("id"),
                    o.getString("name"),
                    (0 until t.length()).map { j ->
                        val to = t.getJSONObject(j)
                        Track(to.getString("uri"), to.getString("title"))
                    }
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun save(playlists: List<Playlist>) {
        val arr = JSONArray()
        playlists.forEach { p ->
            val t = JSONArray()
            p.tracks.forEach { t.put(JSONObject().put("uri", it.uri).put("title", it.title)) }
            arr.put(JSONObject().put("id", p.id).put("name", p.name).put("tracks", t))
        }
        file.writeText(arr.toString())
    }

    fun newId(): String = UUID.randomUUID().toString()
}
