package com.nuvio.tv.relay.standalone

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.LinearGradient
import android.graphics.Shader
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** Durable poster and generated Hero artwork cache; composites are lazy and cached by metadata key. */
class PosterCacheProvider : ContentProvider() {
    override fun onCreate() = true
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String = "image/*"
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        require(mode == "r") { "Read-only poster cache" }
        val ctx = requireNotNull(context)
        val key = uri.lastPathSegment ?: throw java.io.FileNotFoundException("Missing image key")
        require(key.matches(Regex("[a-f0-9]{64}"))) { "Invalid image key" }
        val dir = File(ctx.cacheDir, "poster-cache").apply { mkdirs() }
        val file = File(dir, "$key.img")
        if (!file.exists() || file.length() == 0L) {
            if (uri.getQueryParameter("kind") == "hero") createHero(uri, file)
            else {
                val source = uri.getQueryParameter("url") ?: throw java.io.FileNotFoundException("Poster URL missing")
                val temp = File(dir, "$key.tmp")
                download(source, temp)
                if (!temp.renameTo(file)) { temp.copyTo(file, overwrite = true); temp.delete() }
            }
            prune(dir, keep = key)
        }
        file.setLastModified(System.currentTimeMillis())
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    private fun createHero(uri: Uri, target: File) {
        val bgUrl = uri.getQueryParameter("background") ?: throw java.io.FileNotFoundException("Hero background missing")
        val logoUrl = uri.getQueryParameter("logo")
        val rating = uri.getQueryParameter("rating").orEmpty().trim()
        val bg = decodeUrl(bgUrl) ?: throw java.io.FileNotFoundException("Hero background could not be decoded")
        // Match the downloaded asset's decoded dimensions; do not upscale a thumbnail.
        val w = bg.width
        val h = bg.height
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        // Keep the original image clean: no crop, gradients, or left-side shadow.
        canvas.drawBitmap(bg, null, Rect(0, 0, w, h), paint)

        if (!logoUrl.isNullOrBlank()) {
            val logo = decodeUrl(logoUrl)
            if (logo != null) {
                // Fixed normalized logo box: every hero uses identical placement and bounds.
                val left = w * .045f
                val logoTop = h * .18f
                val boxW = w * .19f
                val boxH = h * .115f
                val factor = minOf(boxW / logo.width, boxH / logo.height)
                val dw = logo.width * factor
                val dh = logo.height * factor
                val logoLeft = left + (boxW - dw) / 2f
                val logoY = logoTop + (boxH - dh) / 2f
                paint.alpha = 255
                canvas.drawBitmap(logo, null, RectF(logoLeft, logoY, logoLeft + dw, logoY + dh), paint)
                if (!logo.isRecycled) logo.recycle()
            }
        }

        if (rating.isNotBlank()) {
            // Metadata is anchored to the background canvas, never to logo dimensions.
            val marker = " ||| "
            val split = rating.indexOf(marker)
            val genres = if (split >= 0) rating.substring(0, split).trim() else rating
            val badgeText = if (split >= 0) rating.substring(split + marker.length).trim() else ""
            val left = w * .045f
            val metaSize = w * .0105f
            val metaBaseline = h * .325f
            val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textSize = metaSize
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                style = Paint.Style.FILL
                setShadowLayer(w * .0025f, 0f, 1f, Color.BLACK)
            }
            if (badgeText.isNotBlank()) {
                val padX = metaSize * .34f
                val padY = metaSize * .15f
                val badgeWidth = tp.measureText(badgeText)
                val badgeRect = RectF(left, metaBaseline - metaSize + padY,
                    left + badgeWidth + padX * 2f, metaBaseline + padY)
                val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.WHITE
                    style = Paint.Style.STROKE
                    strokeWidth = w * .0012f
                    setShadowLayer(w * .002f, 0f, 1f, Color.BLACK)
                }
                canvas.drawRoundRect(badgeRect, metaSize * .20f, metaSize * .20f, badgePaint)
                canvas.drawText(badgeText, left + padX, metaBaseline - metaSize * .02f, tp)
            }
            val genrePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textSize = metaSize * .86f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setShadowLayer(w * .0025f, 0f, 1f, Color.BLACK)
            }
            val maxGenreWidth = w * .39f
            val measured = genrePaint.measureText(genres)
            if (measured > maxGenreWidth && measured > 0f) {
                genrePaint.textSize *= maxGenreWidth / measured
            }
            canvas.drawText(genres, left, h * .36f, genrePaint)
        }
        val temp = File(target.parentFile, target.nameWithoutExtension + ".tmp")
        FileOutputStream(temp).use { out.compress(Bitmap.CompressFormat.JPEG, 98, it) }
        if (!temp.renameTo(target)) { temp.copyTo(target, overwrite = true); temp.delete() }
        bg.recycle(); out.recycle()
    }

    private fun decodeUrl(source: String): Bitmap? {
        val c = URL(source).openConnection() as HttpURLConnection
        c.connectTimeout = 7000; c.readTimeout = 10000
        c.setRequestProperty("User-Agent", "NuvioTVRelay/0.1")
        return try { if (c.responseCode !in 200..299) null else c.inputStream.use { BitmapFactory.decodeStream(it) } }
        finally { c.disconnect() }
    }

    private fun download(source: String, target: File) {
        val c = URL(source).openConnection() as HttpURLConnection
        c.connectTimeout = 7000; c.readTimeout = 10000
        c.setRequestProperty("User-Agent", "NuvioTVRelay/0.1")
        try {
            if (c.responseCode !in 200..299) throw java.io.FileNotFoundException("Poster HTTP ${c.responseCode}")
            c.inputStream.use { input -> FileOutputStream(target).use { output -> input.copyTo(output) } }
            if (target.length() == 0L) { target.delete(); throw java.io.FileNotFoundException("Empty poster") }
        } finally { c.disconnect() }
    }

    private fun prune(dir: File, keep: String) {
        val files = dir.listFiles()?.filter { it.isFile && it.extension == "img" }?.sortedBy { it.lastModified() } ?: return
        var total = files.sumOf { it.length() }
        for (f in files) {
            if (total <= 4L * 1024 * 1024 * 1024) break
            if (f.nameWithoutExtension == keep) continue
            val size = f.length(); if (f.delete()) total -= size
        }
    }

    companion object {
        fun uriFor(context: android.content.Context, raw: String?): Uri? {
            if (raw.isNullOrBlank() || !raw.startsWith("http")) return null
            return Uri.parse("content://com.nuvio.tv.relay.standalone.poster-cache/poster/${sha256(raw)}")
                .buildUpon().appendQueryParameter("url", raw).build()
        }

        fun heroUriFor(context: android.content.Context, background: String?, logo: String?, rating: String?): Uri? {
            if (background.isNullOrBlank() || !background.startsWith("http")) return null
            if (logo.isNullOrBlank() && rating.isNullOrBlank()) return uriFor(context, background)
            val descriptor = "hero-v16-title-fallback-fix|$background|${logo.orEmpty()}|${rating.orEmpty()}"
            return Uri.parse("content://com.nuvio.tv.relay.standalone.poster-cache/hero/${sha256(descriptor)}")
                .buildUpon().appendQueryParameter("kind", "hero").appendQueryParameter("background", background)
                .apply { if (!logo.isNullOrBlank() && logo.startsWith("http")) appendQueryParameter("logo", logo) }
                .apply { if (!rating.isNullOrBlank()) appendQueryParameter("rating", rating) }.build()
        }

        private fun sha256(value: String) =
            MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
