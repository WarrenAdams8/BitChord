package com.music.bitchord.data.spotify

import android.content.Context
import android.content.SharedPreferences
import com.music.bitchord.data.model.Song
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

data class LocalPlaylist(
    val id: String,
    val title: String,
    val songs: List<Song>,
    /** The Spotify playlist this one is kept in step with, or null for a one-off import. */
    val spotifyId: String? = null,
    /** Each song's Spotify track id, in the same order as [songs]; empty unless [spotifyId] is set. */
    val spotifyTrackIds: List<String> = emptyList(),
    /** When the last sync with Spotify finished, in epoch milliseconds. */
    val syncedAt: Long = 0L,
) {
    val browseId: String get() = "local:playlist:$id"

    /** The rows a re-sync can reuse instead of searching for again. */
    val syncedTracks: List<SyncedTrack>
        get() = spotifyTrackIds.zip(songs) { trackId, song -> SyncedTrack(trackId, song) }
}

object LocalPlaylistStore {
    private const val PREF_NAME = "bitchord_local_playlists"
    private const val KEY_PLAYLISTS = "playlists_json"

    private lateinit var prefs: SharedPreferences

    private val _playlists = MutableStateFlow<List<LocalPlaylist>>(emptyList())
    val playlists = _playlists.asStateFlow()

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        load()
    }

    private fun load() {
        if (!this::prefs.isInitialized) return
        val raw = prefs.getString(KEY_PLAYLISTS, null) ?: return
        runCatching {
            val list = mutableListOf<LocalPlaylist>()
            val jsonArray = JSONArray(raw)
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val id = obj.getString("id")
                val title = obj.getString("title")
                val songsArray = obj.getJSONArray("songs")
                val songs = mutableListOf<Song>()
                for (j in 0 until songsArray.length()) {
                    val sObj = songsArray.getJSONObject(j)
                    val song = Song(
                        videoId = sObj.optString("videoId", ""),
                        title = sObj.optString("title", ""),
                        artist = sObj.optString("artist", ""),
                        thumbnailUrl = sObj.optString("thumbnailUrl", null).takeIf { !it.isNullOrBlank() && it != "null" },
                        durationText = sObj.optString("durationText", null).takeIf { !it.isNullOrBlank() && it != "null" },
                        artistId = sObj.optString("artistId", null).takeIf { !it.isNullOrBlank() && it != "null" },
                        albumId = sObj.optString("albumId", null).takeIf { !it.isNullOrBlank() && it != "null" },
                        albumName = sObj.optString("albumName", null).takeIf { !it.isNullOrBlank() && it != "null" },
                        isVideo = sObj.optBoolean("isVideo", false),
                    )
                    songs.add(song)
                }
                val trackIds = obj.optJSONArray("spotifyTrackIds")
                    ?.let { ids -> List(ids.length()) { ids.getString(it) } }
                    .orEmpty()
                list.add(
                    LocalPlaylist(
                        id = id,
                        title = title,
                        songs = songs,
                        spotifyId = obj.optString("spotifyId", "").takeIf { it.isNotBlank() },
                        // A copy whose ids no longer line up with its songs is
                        // treated as never synced, so the next sync rebuilds it.
                        spotifyTrackIds = trackIds.takeIf { it.size == songs.size }.orEmpty(),
                        syncedAt = obj.optLong("syncedAt", 0L),
                    ),
                )
            }
            _playlists.value = list
        }
    }

    fun savePlaylist(title: String, songs: List<Song>): LocalPlaylist {
        val id = "sp_local_" + System.currentTimeMillis()
        val playlist = LocalPlaylist(id = id, title = title, songs = songs)
        val updated = listOf(playlist) + _playlists.value.filterNot { it.id == id }
        _playlists.value = updated
        persist(updated)
        return playlist
    }

    /** The saved copy of Spotify playlist [spotifyId], if it has been synced. */
    fun syncedPlaylist(spotifyId: String): LocalPlaylist? =
        _playlists.value.firstOrNull { it.spotifyId == spotifyId }

    /** Every playlist kept in step with Spotify. */
    fun syncedPlaylists(): List<LocalPlaylist> = _playlists.value.filter { it.spotifyId != null }

    /**
     * Saves the result of syncing Spotify playlist [spotifyId]: over its
     * existing copy when there is one, keeping that copy's id, place in the
     * list and any name the user gave it, and as a new playlist otherwise.
     */
    fun saveSynced(spotifyId: String, title: String, tracks: List<SyncedTrack>): LocalPlaylist {
        val existing = syncedPlaylist(spotifyId)
        val playlist = LocalPlaylist(
            id = existing?.id ?: ("sp_sync_" + System.currentTimeMillis()),
            title = existing?.title ?: title,
            songs = tracks.map { it.song },
            spotifyId = spotifyId,
            spotifyTrackIds = tracks.map { it.spotifyId },
            syncedAt = System.currentTimeMillis(),
        )
        val updated = if (existing != null) {
            _playlists.value.map { if (it.id == existing.id) playlist else it }
        } else {
            listOf(playlist) + _playlists.value
        }
        _playlists.value = updated
        persist(updated)
        return playlist
    }

    fun deletePlaylist(id: String) {
        val updated = _playlists.value.filterNot { it.id == id || it.browseId == id || it.id == id.removePrefix("local:playlist:") }
        _playlists.value = updated
        persist(updated)
    }

    fun renamePlaylist(id: String, newTitle: String) {
        val cleanId = id.removePrefix("local:playlist:").removePrefix("VL")
        val updated = _playlists.value.map {
            if (it.id == cleanId || it.id == id || it.browseId == id) {
                it.copy(title = newTitle)
            } else it
        }
        _playlists.value = updated
        persist(updated)
    }

    fun getPlaylist(id: String): LocalPlaylist? {
        val cleanId = id.removePrefix("local:playlist:").removePrefix("VL")
        return _playlists.value.firstOrNull { it.id == cleanId || it.id == id || it.browseId == id }
    }

    private fun persist(list: List<LocalPlaylist>) {
        if (!this::prefs.isInitialized) return
        runCatching {
            val jsonArray = JSONArray()
            for (playlist in list) {
                val pObj = JSONObject()
                pObj.put("id", playlist.id)
                pObj.put("title", playlist.title)
                val songsArray = JSONArray()
                for (song in playlist.songs) {
                    val sObj = JSONObject()
                    sObj.put("videoId", song.videoId)
                    sObj.put("title", song.title)
                    sObj.put("artist", song.artist)
                    sObj.put("thumbnailUrl", song.thumbnailUrl)
                    sObj.put("durationText", song.durationText)
                    sObj.put("artistId", song.artistId)
                    sObj.put("albumId", song.albumId)
                    sObj.put("albumName", song.albumName)
                    sObj.put("isVideo", song.isVideo)
                    songsArray.put(sObj)
                }
                pObj.put("songs", songsArray)
                playlist.spotifyId?.let { spotifyId ->
                    pObj.put("spotifyId", spotifyId)
                    pObj.put("spotifyTrackIds", JSONArray(playlist.spotifyTrackIds))
                    pObj.put("syncedAt", playlist.syncedAt)
                }
                jsonArray.put(pObj)
            }
            prefs.edit().putString(KEY_PLAYLISTS, jsonArray.toString()).apply()
        }
    }
}
