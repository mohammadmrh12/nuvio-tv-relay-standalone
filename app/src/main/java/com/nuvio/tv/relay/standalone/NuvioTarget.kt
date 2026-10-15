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
        LEGACY -> "com.nuvio.tv"
        else -> "com.nuvioreshaped.tv"
    }

    fun target(context: Context): String =
        context.getSharedPreferences("relay", Context.MODE_PRIVATE).getString(PREF_KEY, RESHAPED) ?: RESHAPED

    fun intent(context: Context, type: String, id: String): Intent {
        val selected = target(context)
        val normalizedType = when (type.lowercase()) {
            "series", "tv", "show", "episode" -> "tv"
            else -> "movie"
        }
        val uri = if (selected == LEGACY && id.matches(Regex("\\d{1,12}"))) {
            // Legacy's native TMDB deep link. Non-numeric add-on IDs (for example IMDb tt IDs)
            // must keep the metadata deep link instead of being misrepresented as TMDB IDs.
            Uri.parse("nuvio://tmdb/$normalizedType/$id")
        } else {
            Uri.Builder().scheme("nuvio").authority("meta")
                .appendQueryParameter("type", type.lowercase())
                .appendQueryParameter("id", id).build()
        }
        return Intent(Intent.ACTION_VIEW, uri)
            .setPackage(packageName(context))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
    }
}
