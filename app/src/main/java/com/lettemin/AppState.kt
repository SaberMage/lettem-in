package com.lettemin

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri

object AppState {
    private const val PREFS = "lettemin"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_GREETING_URI = "greeting_uri"

    @Volatile var serviceRunning = false

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isEnabled(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_ENABLED, true)

    fun setEnabled(ctx: Context, value: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_ENABLED, value).apply()
    }

    fun getGreetingUri(ctx: Context): Uri? =
        prefs(ctx).getString(KEY_GREETING_URI, null)?.toUri()

    fun setGreetingUri(ctx: Context, uri: Uri?) {
        prefs(ctx).edit().apply {
            if (uri == null) remove(KEY_GREETING_URI) else putString(KEY_GREETING_URI, uri.toString())
            apply()
        }
    }
}
