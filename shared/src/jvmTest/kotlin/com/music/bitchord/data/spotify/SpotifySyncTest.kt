package com.music.bitchord.data.spotify

import com.music.bitchord.data.model.Song
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

class SpotifySyncTest {
    private fun track(id: String) = SpotifyTrack(
        id = id,
        title = "Title $id",
        artist = "Artist",
        album = null,
        durationMs = 0,
        imageUrl = "https://i.scdn.co/$id",
    )

    private fun song(id: String, thumbnail: String? = null) =
        Song(videoId = "yt-$id", title = "Title $id", artist = "Artist", thumbnailUrl = thumbnail)

    @Test
    fun resyncSearchesOnlyForNewTracks() = runBlocking {
        val previous = listOf(SyncedTrack("a", song("a", "art")), SyncedTrack("b", song("b", "art")))
        val searched = mutableListOf<String>()
        val result = SpotifySync.reconcile(
            tracks = listOf(track("c"), track("a")),
            previous = previous,
        ) { track ->
            synchronized(searched) { searched += track.id }
            song(track.id)
        }
        assertEquals(listOf("c"), searched)
        // Spotify's order, the kept match reused, the removed track gone.
        assertEquals(listOf("c", "a"), result.tracks.map { it.spotifyId })
        assertEquals(listOf("yt-c", "yt-a"), result.tracks.map { it.song.videoId })
        assertEquals(1, result.added)
        assertEquals(1, result.removed)
        assertEquals(0, result.unmatched)
    }

    @Test
    fun unmatchedTracksAreLeftOutAndCounted() = runBlocking {
        val result = SpotifySync.reconcile(
            tracks = listOf(track("a"), track("b")),
            previous = emptyList(),
        ) { track -> if (track.id == "a") song("a") else null }
        assertEquals(listOf("a"), result.tracks.map { it.spotifyId })
        assertEquals(1, result.added)
        assertEquals(1, result.unmatched)
    }

    @Test
    fun aMatchWithoutArtworkBorrowsSpotifys() = runBlocking {
        val result = SpotifySync.reconcile(listOf(track("a")), emptyList()) { song("a") }
        assertEquals("https://i.scdn.co/a", result.tracks.single().song.thumbnailUrl)
    }

    @Test
    fun aTrackListedTwiceIsSearchedOnceAndKeptTwice() = runBlocking {
        var searches = 0
        val result = SpotifySync.reconcile(listOf(track("a"), track("a")), emptyList()) {
            searches++
            song("a")
        }
        assertEquals(1, searches)
        assertEquals(2, result.tracks.size)
    }

    @Test
    fun aFailedSearchCountsAsUnmatched() = runBlocking {
        val result = SpotifySync.reconcile(listOf(track("a")), emptyList()) { error("offline") }
        assertEquals(0, result.tracks.size)
        assertEquals(1, result.unmatched)
    }
}
