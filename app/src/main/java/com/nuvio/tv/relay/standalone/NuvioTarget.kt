package com.nuvio.tv.relay.standalone

import android.content.Context
import android.content.Intent
import android.net.Uri

/** Builds the launcher intent for the Nuvio app selected in Relay settings. */
object NuvioTarget {
    const val PREF_KEY = "nuvio_target"
    const val RESHAPED = "reshaped"
    const val LEGACY = "legacy"

    fun packageName(context: Context): String = when (target(context)) {
        LEGACY -> "space.nuvio.nativelegacy"
        else -> "com.nuvioreshaped.tv"
    }

    fun target(context: Context): String =
        context.getSharedPreferences("relay", Context.MODE_PRIVATE).getString(PREF_KEY, RESHAPED) ?: RESHAPED

    fun intent(context: Context, type: String, id: String): Intent {
        val selected = target(context)
        val flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        if (selected == LEGACY) {
            // Nuvio Legacy 2.0.8 handles nuvio-legacy://meta/{movie|series}/{id}.
            // Use the explicit package so Android sends the deep link to Legacy, not
            // Nuvio Re-Shaped or another app that may register a similar URI scheme.
            val legacyType = if (type.equals("movie", ignoreCase = true)) "movie" else "series"
            val uri = Uri.Builder()
                .scheme("nuvio-legacy")
                .authority("meta")
                .appendPath(legacyType)
                .appendPath(id.trim())
                .build()
            return Intent(Intent.ACTION_VIEW, uri)
                .setPackage("space.nuvio.nativelegacy")
                .addFlags(flags)
        }
        val uri = Uri.Builder().scheme("nuvio").authority("meta")
            .appendQueryParameter("type", type.lowercase())
            .appendQueryParameter("id", id).build()
        return Intent(Intent.ACTION_VIEW, uri)
            .setPackage("com.nuvioreshaped.tv")
            .addFlags(flags)
    }
}
