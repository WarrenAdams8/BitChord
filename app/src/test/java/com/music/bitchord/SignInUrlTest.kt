package com.music.bitchord

import com.music.bitchord.auth.signInUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Google login the in-app browser opens, with and without an account
 * picked from Android's own chooser.
 */
class SignInUrlTest {

    private val plain =
        "https://accounts.google.com/ServiceLogin?ltmpl=music&service=youtube&passive=true" +
            "&continue=https%3A%2F%2Fmusic.youtube.com%2F"

    @Test
    fun `no account picked opens the plain login`() {
        assertEquals(plain, signInUrl(null))
        assertEquals(plain, signInUrl("   "))
    }

    @Test
    fun `a picked account is passed as an encoded Email hint`() {
        assertEquals("$plain&Email=listener%40gmail.com", signInUrl("listener@gmail.com"))
    }

    @Test
    fun `the hint cannot add parameters of its own`() {
        val url = signInUrl("a+b@example.com&continue=https://evil.example")
        assertTrue(url.endsWith("&Email=a%2Bb%40example.com%26continue%3Dhttps%3A%2F%2Fevil.example"))
    }
}
