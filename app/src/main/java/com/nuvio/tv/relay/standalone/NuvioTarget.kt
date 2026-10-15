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
            // The installed Native Legacy package exposes launcher activities only; device
            // package inspection confirms it has no VIEW/deep-link filters. Launch its original
            // icon activity explicitly rather than sending nuvio:// to Relay or another Nuvio app.
            return Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
                .setClassName("space.nuvio.nativelegacy", "space.nuvio.nativelegacy.Icone_original")
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
