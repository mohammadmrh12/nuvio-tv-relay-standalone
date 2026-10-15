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
import android.graphics.Color
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextDirectionHeuristics
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
        val platformUrl = uri.getQueryParameter("platform")
        val platformName = uri.getQueryParameter("platformName").orEmpty().trim()
        val overview = uri.getQueryParameter("overview").orEmpty().trim()
        val descriptionLanguage = uri.getQueryParameter("descriptionLanguage").orEmpty().ifBlank { "en" }
        val bg = decodeUrl(bgUrl, maxWidth = 1920, maxHeight = 1080) ?: throw java.io.FileNotFoundException("Hero background could not be decoded")
        // Render overlays at a consistent high resolution even when the supplied backdrop is small.
        // Keep the source aspect ratio and upscale only the canvas, not the source's actual detail.
        val scale = maxOf(1f, 1920f / bg.width, 1080f / bg.height)
        val w = (bg.width * scale).toInt().coerceAtMost(1920)
        val h = (bg.height * scale).toInt().coerceAtMost(1080)
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        // Keep the original image clean: no crop, gradients, or left-side shadow.
        canvas.drawBitmap(bg, null, Rect(0, 0, w, h), paint)

        if (!logoUrl.isNullOrBlank()) {
            val logo = decodeUrl(logoUrl, maxWidth = 1200, maxHeight = 400)
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
            val genres = (if (split >= 0) rating.substring(0, split).trim() else rating)
                .replace(Regex("\\s*\\[\\s*"), " ")
                .replace(Regex("\\s*]\\s*"), " ")
                .replace(Regex("[\"']"), "")
                .replace(Regex("\\s+"), " ").trim()
            val badgeText = if (split >= 0) rating.substring(split + marker.length).trim() else ""
            val left = w * .045f
            val metaSize = w * .0112f
            val metaBaseline = h * .325f
            val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textSize = metaSize
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                style = Paint.Style.FILL
                setShadowLayer(w * .0032f, 0f, 1f, Color.BLACK)
            }
            var metadataX = left
            if (badgeText.isNotBlank()) {
                // Separate rating and year into individual outlined badges, as in the reference.
                val parts = badgeText.split(Regex("\\s+[·|]\\s+|\\s{2,}")).filter { it.isNotBlank() }
                val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.WHITE
                    style = Paint.Style.STROKE
                    strokeWidth = w * .00145f
                    setShadowLayer(w * .002f, 0f, 1f, Color.BLACK)
                }
                val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.WHITE
                    textSize = metaSize
                    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                    style = Paint.Style.FILL
                    setShadowLayer(w * .0032f, 0f, 1f, Color.BLACK)
                }
                val padX = metaSize * .34f
                val padY = metaSize * .15f
                val badgeTop = metaBaseline - metaSize + padY
                val badgeBottom = metaBaseline + padY
                parts.forEachIndexed { index, part ->
                    val textWidth = textPaint.measureText(part)
                    val rect = RectF(metadataX, badgeTop, metadataX + textWidth + padX * 2f, badgeBottom)
                    canvas.drawRoundRect(rect, metaSize * .20f, metaSize * .20f, badgePaint)
                    canvas.drawText(part, metadataX + padX, metaBaseline - metaSize * .02f, textPaint)
                    metadataX = rect.right
                    if (index < parts.lastIndex) {
                        val dividerX = metadataX + metaSize * .42f
                        val dividerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                            color = Color.WHITE
                            strokeWidth = w * .0013f
                            setShadowLayer(w * .0015f, 0f, 1f, Color.BLACK)
                        }
                        canvas.drawLine(dividerX, badgeTop - metaSize * .04f, dividerX, badgeBottom + metaSize * .04f, dividerPaint)
                        metadataX = dividerX + metaSize * .42f
                    }
                }

            }
            // Match the provider badge to the rating/year badges: transparent fill,
            // same outline, type size, corner radius and inter-badge spacing.
            if (!platformUrl.isNullOrBlank()) {
                val providerKey = platformUrl.removePrefix("asset:").substringBefore(".").lowercase()
                val providerName = platformName.ifBlank {
                    when {
                        providerKey.contains("appletv") || providerKey.contains("apple-tv") -> "Apple TV+"
                        providerKey.contains("primevideo") || providerKey.contains("prime-video") -> "Prime Video"
                        providerKey == "hbo" -> "HBO"
                        providerKey == "max" -> "Max"
                        providerKey.contains("disneyplus") || providerKey.contains("disney-plus") -> "Disney+"
                        providerKey.contains("netflix") -> "Netflix"
                        providerKey.contains("cbs") -> "CBS"
                        providerKey.contains("hulu") -> "Hulu"
                        providerKey.contains("viu") -> "Viu"
                        providerKey.contains("peacock") -> "Peacock"
                        providerKey.contains("paramountplus") || providerKey.contains("paramount-plus") -> "Paramount+"
                        providerKey.contains("crunchyroll") -> "Crunchyroll"
                        else -> "Streaming"
                    }
                }
                val providerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.WHITE
                    textSize = metaSize
                    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                    style = Paint.Style.FILL
                    setShadowLayer(w * .0032f, 0f, 1f, Color.BLACK)
                }
                val providerOutline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.WHITE
                    style = Paint.Style.STROKE
                    strokeWidth = w * .00145f
                    setShadowLayer(w * .002f, 0f, 1f, Color.BLACK)
                }
                val badgePadX = metaSize * .34f
                val badgePadY = metaSize * .15f
                val providerWidth = providerPaint.measureText(providerName)
                val badgeGap = metaSize * .60f
                val separatorX = metadataX + badgeGap * .5f
                val providerRect = RectF(
                    metadataX + badgeGap,
                    metaBaseline - metaSize + badgePadY,
                    metadataX + badgeGap + providerWidth + badgePadX * 2f,
                    metaBaseline + badgePadY
                )
                val separatorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.WHITE
                    strokeWidth = w * .0013f
                    setShadowLayer(w * .0015f, 0f, 1f, Color.BLACK)
                }
                canvas.drawLine(separatorX, providerRect.top - metaSize * .04f, separatorX, providerRect.bottom + metaSize * .04f, separatorPaint)
                canvas.drawRoundRect(providerRect, metaSize * .20f, metaSize * .20f, providerOutline)
                canvas.drawText(providerName, providerRect.left + badgePadX, metaBaseline - metaSize * .02f, providerPaint)
            }

            val genreOutline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(210, 0, 0, 0)
                textSize = metaSize * .90f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                style = Paint.Style.STROKE
                strokeWidth = w * .0011f
                strokeJoin = Paint.Join.ROUND
            }
            val genrePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
                color = Color.WHITE
                textSize = metaSize * .90f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                style = Paint.Style.FILL
                isSubpixelText = true
                setShadowLayer(w * .0032f, 0f, 1f, Color.BLACK)
            }
            val maxGenreWidth = w * .39f
            val measured = genrePaint.measureText(genres)
            if (measured > maxGenreWidth && measured > 0f) {
                genrePaint.textSize *= maxGenreWidth / measured
            }
            canvas.drawText(genres, left, h * .36f, genreOutline)
            canvas.drawText(genres, left, h * .36f, genrePaint)
        }
        if (overview.isNotBlank()) {
            val left = w * .045f
            val descriptionPaint = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
                color = Color.WHITE
                textSize = w * .0095f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setShadowLayer(w * .0028f, 0f, 1f, Color.BLACK)
            }
            // Fixed invisible text box: content may wrap or ellipsize, but can never
            // escape horizontally or vertically into the character/artwork area.
            val boxTop = h * .355f
            val boxWidth = (w * .405f).toInt().coerceAtLeast(1)
            val boxHeight = (h * .125f).toInt().coerceAtLeast(1)
            val normalizedOverview = overview.replace(Regex("\\s+"), " ").trim()
            val direction = if (descriptionLanguage.equals("ar", true)) TextDirectionHeuristics.RTL else TextDirectionHeuristics.LTR
            // Scale font to the actual artwork dimensions (bitmap width can vary by source).
            descriptionPaint.textSize = (w * .0078f).coerceAtLeast(12f)
            val layout = StaticLayout.Builder.obtain(normalizedOverview, 0, normalizedOverview.length, descriptionPaint, boxWidth)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setTextDirection(direction)
                .setIncludePad(false)
                .setMaxLines(4)
                .setEllipsize(android.text.TextUtils.TruncateAt.END)
                .setLineSpacing(w * .0007f, 1.0f)
                .build()
            canvas.save()
            // Clip before translation in bitmap coordinates, then clip again in local text coordinates.
            canvas.clipRect(left, boxTop, left + boxWidth, boxTop + boxHeight)
            canvas.translate(left, boxTop)
            canvas.clipRect(0f, 0f, boxWidth.toFloat(), boxHeight.toFloat())
            layout.draw(canvas)
            canvas.restore()
        }
        val temp = File(target.parentFile, target.nameWithoutExtension + ".tmp")
        FileOutputStream(temp).use { out.compress(Bitmap.CompressFormat.JPEG, 98, it) }
        if (!temp.renameTo(target)) { temp.copyTo(target, overwrite = true); temp.delete() }
        bg.recycle(); out.recycle()
    }

    private fun decodeUrl(source: String, maxWidth: Int, maxHeight: Int): Bitmap? {
        val c = URL(source).openConnection() as HttpURLConnection
        c.connectTimeout = 7000; c.readTimeout = 10000
        c.setRequestProperty("User-Agent", "NuvioTVRelay/0.1")
        return try {
            if (c.responseCode !in 200..299) return null
            val bytes = c.inputStream.use { input ->
                val declared = c.contentLengthLong
                if (declared > MAX_IMAGE_BYTES) throw java.io.IOException("Image response exceeds size limit")
                val output = java.io.ByteArrayOutputStream(minOf(declared.coerceAtLeast(8192L), MAX_IMAGE_BYTES.toLong()).toInt())
                val buffer = ByteArray(16 * 1024)
                var total = 0L
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > MAX_IMAGE_BYTES) throw java.io.IOException("Image response exceeds size limit")
                    output.write(buffer, 0, read)
                }
                output.toByteArray()
            }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= maxWidth || bounds.outHeight / (sample * 2) >= maxHeight) sample *= 2
            val options = BitmapFactory.Options().apply { inSampleSize = sample.coerceAtLeast(1) }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        } finally { c.disconnect() }
    }

    private fun download(source: String, target: File) {
        val c = URL(source).openConnection() as HttpURLConnection
        c.connectTimeout = 7000; c.readTimeout = 10000
        c.setRequestProperty("User-Agent", "NuvioTVRelay/0.1")
        try {
            if (c.responseCode !in 200..299) throw java.io.FileNotFoundException("Poster HTTP ${c.responseCode}")
            val declared = c.contentLengthLong
            if (declared > MAX_IMAGE_BYTES) throw java.io.IOException("Poster response exceeds size limit")
            c.inputStream.use { input -> FileOutputStream(target).use { output ->
                val buffer = ByteArray(16 * 1024)
                var total = 0L
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > MAX_IMAGE_BYTES) throw java.io.IOException("Poster response exceeds size limit")
                    output.write(buffer, 0, read)
                }
            } }
            if (target.length() == 0L) { target.delete(); throw java.io.FileNotFoundException("Empty poster") }
        } finally { c.disconnect() }
    }

    private fun prune(dir: File, keep: String) {
        val files = dir.listFiles()?.filter { it.isFile && it.extension == "img" }?.sortedBy { it.lastModified() } ?: return
        var total = files.sumOf { it.length() }
        for (f in files) {
            if (total <= 1024L * 1024 * 1024) break
            if (f.nameWithoutExtension == keep) continue
            val size = f.length(); if (f.delete()) total -= size
        }
    }

    companion object {
        private const val MAX_IMAGE_BYTES = 25 * 1024 * 1024

        fun uriFor(context: android.content.Context, raw: String?): Uri? {
            if (raw.isNullOrBlank() || !raw.startsWith("http")) return null
            return Uri.parse("content://com.nuvio.tv.relay.standalone.poster-cache/poster/${sha256(raw)}")
                .buildUpon().appendQueryParameter("url", raw).build()
        }

        fun heroUriFor(context: android.content.Context, background: String?, logo: String?, rating: String?, platform: String? = null, platformName: String? = null, overview: String? = null, descriptionLanguage: String = "en"): Uri? {
            if (background.isNullOrBlank() || !background.startsWith("http")) return null
            val effectiveOverview = if (context.getSharedPreferences("relay", android.content.Context.MODE_PRIVATE).getBoolean("synopsis_enabled", true)) overview else null
            if (logo.isNullOrBlank() && rating.isNullOrBlank() && platform.isNullOrBlank() && effectiveOverview.isNullOrBlank()) return uriFor(context, background)
            val descriptor = "hero-v39-strict-clip-$descriptionLanguage|$background|${logo.orEmpty()}|${rating.orEmpty()}|${platform.orEmpty()}|${platformName.orEmpty()}|${effectiveOverview.orEmpty()}"
            return Uri.parse("content://com.nuvio.tv.relay.standalone.poster-cache/hero/${sha256(descriptor)}")
                .buildUpon().appendQueryParameter("kind", "hero").appendQueryParameter("background", background)
                .apply { if (!logo.isNullOrBlank() && logo.startsWith("http")) appendQueryParameter("logo", logo) }
                .apply { if (!rating.isNullOrBlank()) appendQueryParameter("rating", rating) }
                .apply { if (!platform.isNullOrBlank() && (platform.startsWith("https://image.tmdb.org/") || platform.startsWith("asset:"))) appendQueryParameter("platform", platform) }
                .apply { if (!platformName.isNullOrBlank()) appendQueryParameter("platformName", platformName) }
                .apply { if (!effectiveOverview.isNullOrBlank()) appendQueryParameter("overview", effectiveOverview) }
                .appendQueryParameter("descriptionLanguage", descriptionLanguage).build()
        }

        private fun sha256(value: String) =
            MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
