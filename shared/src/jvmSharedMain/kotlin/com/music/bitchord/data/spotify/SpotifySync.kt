package com.music.bitchord.data.spotify

import com.music.bitchord.data.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** One row of a synced playlist: the Spotify track it stands for and the YouTube Music song found for it. */
data class SyncedTrack(
    val spotifyId: String,
    val song: Song,
)

/** What a sync left behind, and how it differs from the copy it replaced. */
data class SpotifySyncResult(
    /** The playlist in Spotify's order, matched tracks only. */
    val tracks: List<SyncedTrack>,
    /** Tracks matched this time that the previous copy did not have. */
    val added: Int,
    /** Tracks the previous copy had that are no longer on the Spotify playlist. */
    val removed: Int,
    /** Spotify tracks with no YouTube Music version; looked for again on the next sync. */
    val unmatched: Int,
)

/**
 * Keeps a saved copy of a Spotify playlist in step with Spotify.
 *
 * Matching a track on YouTube Music is one search per song, which is what makes
 * a Spotify playlist page slow to fill. A sync remembers every match by its
 * Spotify track id, so a re-sync only searches for the tracks that were added
 * since (or that found nothing last time) and a removed track drops out.
 */
object SpotifySync {
    /** Matches looked up at once, as gentle on YouTube Music's search as the playlist page is. */
    private const val PARALLELISM = 4

    /** Fetches [playlistId] from the signed-in Spotify account and reconciles it with [previous]. */
    suspend fun sync(
        playlistId: String,
        previous: List<SyncedTrack>,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): SpotifySyncResult {
        val tracks = SpotifyLibrary.tracks(playlistId)
        return reconcile(tracks, previous, onProgress = onProgress)
    }

    /**
     * [tracks] in Spotify's order, each paired with its match from [previous]
     * when it had one and with a fresh [match] otherwise. [onProgress] counts
     * only the tracks that needed searching.
     */
    suspend fun reconcile(
        tracks: List<SpotifyTrack>,
        previous: List<SyncedTrack>,
        parallelism: Int = PARALLELISM,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
        match: suspend (SpotifyTrack) -> Song? = { SpotifyImporter.matchTrack(it) },
    ): SpotifySyncResult = coroutineScope {
        val known = previous.associate { it.spotifyId to it.song }
        // A track listed twice is searched for once.
        val fresh = tracks.filter { it.id !in known }.distinctBy { it.id }
        val gate = Semaphore(parallelism)
        var done = 0
        val found = fresh.map { track ->
            async(Dispatchers.IO) {
                val song = gate.withPermit { runCatching { match(track) }.getOrNull() }
                synchronized(this@SpotifySync) { onProgress(++done, fresh.size) }
                track.id to song?.let { it.copy(thumbnailUrl = it.thumbnailUrl ?: track.imageUrl) }
            }
        }.awaitAll().toMap()

        val synced = tracks.mapNotNull { track ->
            (known[track.id] ?: found[track.id])?.let { SyncedTrack(track.id, it) }
        }
        val currentIds = tracks.mapTo(HashSet()) { it.id }
        SpotifySyncResult(
            tracks = synced,
            added = found.values.count { it != null },
            removed = known.keys.count { it !in currentIds },
            unmatched = found.values.count { it == null },
        )
    }
}
