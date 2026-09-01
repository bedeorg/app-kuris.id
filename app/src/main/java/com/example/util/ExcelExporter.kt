package com.example.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.example.data.OrderEntity
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object ExcelExporter {

    private val dateFormat = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale("id", "ID"))
    private val fileNameDateFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)

    /**
     * Exports list of orders into a CSV file compatible with Microsoft Excel, Google Sheets, etc.
     * Uses UTF-8 BOM to ensure Excel opens Indonesian characters and numbers cleanly.
     * Includes structured table columns: Nopol, Tgl Orderan, Surat Jalan, Alamat, and photo links.
     */
    fun exportOrdersToCsv(context: Context, orders: List<OrderEntity>): File {
        val exportDir = File(context.cacheDir, "exports")
        if (!exportDir.exists()) {
            exportDir.mkdirs()
        }

        val timestamp = fileNameDateFormat.format(Date())
        val file = File(exportDir, "ABDE_LOGIS_Laporan_Order_$timestamp.csv")

        FileOutputStream(file).use { fos ->
            OutputStreamWriter(fos, StandardCharsets.UTF_8).use { writer ->
                // Write UTF-8 BOM so Excel opens with correct encoding
                writer.write("\uFEFF")

                // CSV Headers as requested: Nopol, Tgl Orderan, Surat Jalan, Bukti Foto Links, etc.
                writer.write("No,Nopol,Tgl Orderan,Surat Jalan,Alamat & Lokasi GPS,Link Foto Barang (Driver),Link Foto Surat Jalan (Driver),Status Foto,Hasil Bacaan OCR Surat Jalan\n")

                // Rows
                orders.forEachIndexed { index, order ->
                    val no = index + 1
                    val nopol = escapeCsv(if (order.nopol.isNotBlank()) order.nopol.uppercase() else "-")
                    val dateFormatted = escapeCsv(dateFormat.format(Date(order.timestamp)))
                    val suratJalan = escapeCsv(if (order.orderName.isNotBlank()) order.orderName else "SJ-${order.id}")
                    
                    val gpsCoords = if (order.latitude != 0.0 || order.longitude != 0.0) {
                        String.format(Locale.US, " (GPS: %.6f, %.6f)", order.latitude, order.longitude)
                    } else ""
                    val fullAddress = escapeCsv("${order.address}$gpsCoords")

                    // Link Foto Barang
                    val goodsPhotoLink = if (order.goodsPhotoUri.isNotBlank()) {
                        val photoFile = File(order.goodsPhotoUri)
                        if (photoFile.exists()) {
                            val fileUri = "file://${photoFile.absolutePath}"
                            // Excel hyperlink formula + direct path
                            escapeCsv("=HYPERLINK(\"$fileUri\",\"Buka Foto Barang\")")
                        } else {
                            escapeCsv(order.goodsPhotoUri)
                        }
                    } else {
                        "-"
                    }

                    // Link Foto Surat Jalan
                    val waybillPhotoLink = if (order.waybillPhotoUri.isNotBlank()) {
                        val photoFile = File(order.waybillPhotoUri)
                        if (photoFile.exists()) {
                            val fileUri = "file://${photoFile.absolutePath}"
                            // Excel hyperlink formula + direct path
                            escapeCsv("=HYPERLINK(\"$fileUri\",\"Buka Foto Surat Jalan\")")
                        } else {
                            escapeCsv(order.waybillPhotoUri)
                        }
                    } else {
                        "-"
                    }

                    val photoStatus = when {
                        order.goodsPhotoUri.isNotBlank() && order.waybillPhotoUri.isNotBlank() -> "Lengkap (Barang & Surat Jalan)"
                        order.goodsPhotoUri.isNotBlank() -> "Foto Barang Saja"
                        order.waybillPhotoUri.isNotBlank() -> "Foto Surat Jalan Saja"
                        else -> "Belum Ada Foto"
                    }

                    val ocrText = escapeCsv(order.ocrResultText.replace("\n", " | ").trim())

                    writer.write("$no,$nopol,$dateFormatted,$suratJalan,$fullAddress,$goodsPhotoLink,$waybillPhotoLink,\"$photoStatus\",$ocrText\n")
                }
            }
        }

        return file
    }

    /**
     * Creates an Intent to share or open the generated CSV file.
     */
    fun getShareIntent(context: Context, file: File): Intent {
        val contentUri: Uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )

        return Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_SUBJECT, "Laporan Riwayat Order - ABDE LOGIS (${file.name})")
            putExtra(Intent.EXTRA_TEXT, "Berikut terlampir file data ekspor riwayat orderan ekspedisi dari aplikasi ABDE LOGIS.")
            putExtra(Intent.EXTRA_STREAM, contentUri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    private fun escapeCsv(text: String): String {
        var result = text.replace("\"", "\"\"")
        if (result.contains(",") || result.contains("\"") || result.contains("\n") || result.contains("\r")) {
            result = "\"$result\""
        }
        return result
    }
}
