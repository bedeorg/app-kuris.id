package com.example.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import com.example.R
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object ImageUtils {

    /**
     * Resizes (downscales) bitmap so its largest dimension is at most [maxDimension] pixels.
     */
    fun downscaleBitmap(original: Bitmap, maxDimension: Int = 1200): Bitmap {
        val width = original.width
        val height = original.height

        if (width <= maxDimension && height <= maxDimension) {
            return original
        }

        val ratio = width.toFloat() / height.toFloat()
        val newWidth: Int
        val newHeight: Int

        if (width >= height) {
            newWidth = maxDimension
            newHeight = (maxDimension / ratio).toInt().coerceAtLeast(1)
        } else {
            newHeight = maxDimension
            newWidth = (maxDimension * ratio).toInt().coerceAtLeast(1)
        }

        return Bitmap.createScaledBitmap(original, newWidth, newHeight, true)
    }

    /**
     * Draws watermark (ABDE LOGIS Logo, Nopol Kendaraan, Geocoded Address, Date/Time, GPS Coordinates)
     * directly onto the bitmap using Canvas.
     */
    fun addWatermark(
        context: Context? = null,
        source: Bitmap,
        latitude: Double,
        longitude: Double,
        timestamp: Long,
        address: String = "",
        nopol: String = "",
        pengurus: String = "",
        tag: String = "abde.kurir"
    ): Bitmap {
        val mutableBitmap = if (source.isMutable) {
            source
        } else {
            source.copy(Bitmap.Config.ARGB_8888, true)
        }

        val canvas = Canvas(mutableBitmap)
        val width = mutableBitmap.width.toFloat()
        val height = mutableBitmap.height.toFloat()

        // Scaled text size and padding proportional to bitmap size
        val baseScale = (width.coerceAtLeast(height) / 1000f).coerceIn(0.8f, 2.2f)
        val headerTextSize = 25f * baseScale
        val bodyTextSize = 20f * baseScale
        val padding = 18f * baseScale
        val lineSpacing = 7f * baseScale

        val dateFormat = SimpleDateFormat("EEEE, dd MMM yyyy - HH:mm:ss 'WIB'", Locale("id", "ID"))
        val dateString = dateFormat.format(Date(timestamp))
        val locationString = if (latitude != 0.0 || longitude != 0.0) {
            String.format(Locale.US, "GPS: %.6f, %.6f", latitude, longitude)
        } else {
            "GPS: Lokasi standby"
        }

        // Available width for text - leaving space for logo on the right if available
        val logoWidth = 110f * baseScale
        val logoHeight = 65f * baseScale
        val maxTextWidth = width - (padding * 2) - logoWidth - 20f

        // Paints for text
        val titlePaint = Paint().apply {
            color = Color.parseColor("#4DEEEA") // Bright cyan
            textSize = headerTextSize
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            isAntiAlias = true
            setShadowLayer(4f * baseScale, 2f, 2f, Color.BLACK)
        }

        val addressPaint = Paint().apply {
            color = Color.parseColor("#FFD54F") // Vivid amber/yellow for prominent address
            textSize = bodyTextSize
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            isAntiAlias = true
            setShadowLayer(3f * baseScale, 1f, 1f, Color.BLACK)
        }

        val textPaint = Paint().apply {
            color = Color.WHITE
            textSize = bodyTextSize
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
            isAntiAlias = true
            setShadowLayer(3f * baseScale, 1f, 1f, Color.BLACK)
        }

        val subTextPaint = Paint().apply {
            color = Color.parseColor("#E0E0E0")
            textSize = bodyTextSize * 0.9f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
            isAntiAlias = true
            setShadowLayer(3f * baseScale, 1f, 1f, Color.BLACK)
        }

        // Build list of lines with their respective paints
        data class WatermarkLine(val text: String, val paint: Paint)
        val lineItems = mutableListOf<WatermarkLine>()

        // 1. Tag / Brand & Nopol Header
        val nopolPart = if (nopol.isNotBlank()) " [${nopol.uppercase()}]" else ""
        val pengurusHeader = if (pengurus.isNotBlank()) " • Pengurus: $pengurus" else ""
        val headerTitle = "$tag$nopolPart$pengurusHeader"
        lineItems.add(WatermarkLine(headerTitle, titlePaint))

        // 2. Alamat (Address FIRST)
        val displayAddress = if (address.isNotBlank()) address else locationString
        val wrappedAddressLines = wrapText("Alamat: $displayAddress", addressPaint, maxTextWidth)
        wrappedAddressLines.forEach { line ->
            lineItems.add(WatermarkLine(line, addressPaint))
        }

        // 3. Waktu & Tanggal (Date & Time directly BELOW Address)
        lineItems.add(WatermarkLine("Waktu & Tgl: $dateString", textPaint))

        // 4. GPS Coordinates (if address was resolved)
        if (address.isNotBlank() && (latitude != 0.0 || longitude != 0.0)) {
            lineItems.add(WatermarkLine("Koordinat: $locationString", subTextPaint))
        }

        val totalTextHeight = headerTextSize + (bodyTextSize * (lineItems.size - 1)) + (lineSpacing * (lineItems.size - 1))
        val boxHeight = (totalTextHeight + (padding * 2)).coerceAtLeast(logoHeight + (padding * 2))

        // Draw semi-transparent background badge at the bottom
        val bgPaint = Paint().apply {
            color = Color.argb(210, 11, 25, 44) // Deep navy tint #0B192C
            style = Paint.Style.FILL
            isAntiAlias = true
        }

        val bgRect = RectF(0f, height - boxHeight, width, height)
        canvas.drawRect(bgRect, bgPaint)

        // Draw an accent line on top of the watermark bar
        val accentPaint = Paint().apply {
            color = Color.parseColor("#4DEEEA") // Cyan accent
            strokeWidth = 4f * baseScale
            style = Paint.Style.STROKE
            isAntiAlias = true
        }
        canvas.drawLine(0f, height - boxHeight, width, height - boxHeight, accentPaint)

        // Draw ABDE LOGIS logo image on the top-right corner of watermark box
        try {
            val logoBitmap = if (context != null) {
                BitmapFactory.decodeResource(context.resources, R.drawable.ic_abedem_logo)
            } else null

            if (logoBitmap != null) {
                val logoLeft = width - padding - logoWidth
                val logoTop = (height - boxHeight) + padding
                val logoRect = RectF(logoLeft, logoTop, logoLeft + logoWidth, logoTop + logoHeight)

                // Background container for logo
                val logoBgPaint = Paint().apply {
                    color = Color.parseColor("#1E3E62")
                    style = Paint.Style.FILL
                    isAntiAlias = true
                }
                val logoBorderPaint = Paint().apply {
                    color = Color.parseColor("#4DEEEA")
                    style = Paint.Style.STROKE
                    strokeWidth = 2f * baseScale
                    isAntiAlias = true
                }
                canvas.drawRoundRect(logoRect, 8f * baseScale, 8f * baseScale, logoBgPaint)
                canvas.drawBitmap(logoBitmap, null, logoRect, Paint(Paint.FILTER_BITMAP_FLAG))
                canvas.drawRoundRect(logoRect, 8f * baseScale, 8f * baseScale, logoBorderPaint)
            }
        } catch (e: Exception) {
            // Fallback gracefully if logo resource could not be loaded
        }

        // Draw text lines onto canvas
        var currentY = (height - boxHeight) + padding + headerTextSize
        lineItems.forEachIndexed { index, lineItem ->
            if (index == 0) {
                canvas.drawText(lineItem.text, padding + 10f, currentY, lineItem.paint)
            } else {
                currentY += bodyTextSize + lineSpacing
                canvas.drawText(lineItem.text, padding + 10f, currentY, lineItem.paint)
            }
        }

        return mutableBitmap
    }

    private fun wrapText(text: String, paint: Paint, maxWidth: Float): List<String> {
        val words = text.split(" ")
        val lines = mutableListOf<String>()
        var currentLine = StringBuilder()

        for (word in words) {
            val testLine = if (currentLine.isEmpty()) word else "$currentLine $word"
            val measuredWidth = paint.measureText(testLine)

            if (measuredWidth <= maxWidth) {
                currentLine = StringBuilder(testLine)
            } else {
                if (currentLine.isNotEmpty()) {
                    lines.add(currentLine.toString())
                }
                currentLine = StringBuilder(word)
            }
        }

        if (currentLine.isNotEmpty()) {
            lines.add(currentLine.toString())
        }

        return lines.take(3) // Limit address to at most 3 wrapped lines so it fits comfortably
    }

    /**
     * Compresses the bitmap to JPEG (80% quality) and saves to app internal storage (filesDir).
     * Returns the absolute path of the saved file.
     */
    fun saveBitmapToInternalStorage(context: Context, bitmap: Bitmap, prefix: String): String {
        val photosDir = File(context.filesDir, "order_photos")
        if (!photosDir.exists()) {
            photosDir.mkdirs()
        }

        val fileName = "${prefix}_${System.currentTimeMillis()}.jpg"
        val destFile = File(photosDir, fileName)

        FileOutputStream(destFile).use { outStream ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 80, outStream)
            outStream.flush()
        }

        return destFile.absolutePath
    }

    /**
     * Downloads and saves an image from internal storage directly to the device's public Gallery (Pictures/AbdeKurir).
     * Compatible with modern Android Scoped Storage via MediaStore API.
     */
    fun downloadImageToGallery(context: Context, imageFilePath: String, customTitle: String = "ABDE_KURIR_FOTO"): android.net.Uri? {
        val sourceFile = File(imageFilePath)
        if (!sourceFile.exists()) return null

        val fileName = "${customTitle}_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.jpg"
        val contentValues = android.content.ContentValues().apply {
            put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/AbdeKurir")
                put(android.provider.MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }

        val resolver = context.contentResolver
        val imageUri = resolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)

        if (imageUri != null) {
            try {
                resolver.openOutputStream(imageUri)?.use { outStream ->
                    java.io.FileInputStream(sourceFile).use { inStream ->
                        inStream.copyTo(outStream)
                    }
                    outStream.flush()
                }

                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                    contentValues.clear()
                    contentValues.put(android.provider.MediaStore.MediaColumns.IS_PENDING, 0)
                    resolver.update(imageUri, contentValues, null, null)
                }
                return imageUri
            } catch (e: Exception) {
                resolver.delete(imageUri, null, null)
                return null
            }
        }
        return null
    }

    /**
     * Creates an Intent to share an image file using FileProvider.
     */
    fun getShareImageIntent(context: Context, imageFilePath: String, subject: String = "Foto Pengiriman ABDE KURIR"): android.content.Intent? {
        val file = File(imageFilePath)
        if (!file.exists()) return null

        val contentUri = androidx.core.content.FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )

        return android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "image/jpeg"
            putExtra(android.content.Intent.EXTRA_SUBJECT, subject)
            putExtra(android.content.Intent.EXTRA_STREAM, contentUri)
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
}
