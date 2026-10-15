package com.music.bitchord.auth

import android.accounts.AccountManager
import android.app.Activity
import android.content.ActivityNotFoundException
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import com.music.bitchord.data.DebugLog as Log

/** The account type Android files Google accounts under. */
private const val GOOGLE_ACCOUNT_TYPE = "com.google"

/**
 * Asks Android which of the Google accounts already on this device to sign in
 * with, using the system's own account chooser.
 *
 * This is the native half of signing in. The chooser lists the accounts the
 * phone is signed in to and needs no permission, no `GET_ACCOUNTS` and no OAuth
 * client registered with Google: the app is told only the address the listener
 * picked, and only because they picked it.
 *
 * What it cannot do is hand over a YouTube Music session. Innertube is signed
 * with Google's web cookies, and Android gives a third-party app no way to turn
 * a device account into those — that exchange is reserved to Google's own apps.
 * So the address is used as a login hint for the in-app Google page, which then
 * opens straight on that account and asks only to confirm it (password, passkey
 * on another device, or "Yes, it's me" on the phone), instead of starting from
 * an empty email field.
 *
 * Returns a function that opens the chooser. [onResult] is called exactly once
 * per launch with the chosen address, or null when the chooser was dismissed or
 * isn't available; either way the caller carries on with the web sign-in.
 */
@Composable
fun rememberDeviceGoogleAccountPicker(onResult: (String?) -> Unit): () -> Unit {
    val currentOnResult by rememberUpdatedState(onResult)
    val chooser = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val email = result.data
            ?.getStringExtra(AccountManager.KEY_ACCOUNT_NAME)
            ?.takeIf { result.resultCode == Activity.RESULT_OK && it.isNotBlank() }
        currentOnResult(email)
    }
    return {
        val intent = AccountManager.newChooseAccountIntent(
            null,
            null,
            arrayOf(GOOGLE_ACCOUNT_TYPE),
            null,
            null,
            null,
            null,
        )
        try {
            chooser.launch(intent)
        } catch (e: ActivityNotFoundException) {
            // A device stripped of the account framework. Not a reason to
            // block sign-in: the web page still takes an address typed in.
            Log.w("BitChord", "no system account chooser: ${e.message}")
            currentOnResult(null)
        }
    }
}
