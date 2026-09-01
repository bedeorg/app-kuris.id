package com.example

import android.content.Context
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import com.example.data.OrderEntity
import com.example.util.ExcelExporter
import com.example.util.ImageUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExampleRobolectricTest {

    @Test
    fun `verify app name string is Abedem`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("Abedem", appName)
    }

    @Test
    fun `verify bitmap downscale optimization`() {
        val largeBitmap = Bitmap.createBitmap(2000, 1500, Bitmap.Config.ARGB_8888)
        val downscaled = ImageUtils.downscaleBitmap(largeBitmap, 1200)

        assertTrue(downscaled.width <= 1200)
        assertTrue(downscaled.height <= 1200)
        assertEquals(1200, downscaled.width)
        assertEquals(900, downscaled.height)
    }

    @Test
    fun `verify addWatermark with address and nopol`() {
        val bitmap = Bitmap.createBitmap(800, 600, Bitmap.Config.ARGB_8888)
        val watermarked = ImageUtils.addWatermark(
            source = bitmap,
            latitude = -6.2088,
            longitude = 106.8456,
            timestamp = System.currentTimeMillis(),
            address = "Jl. Sudirman Kav. 52, Jakarta Selatan",
            nopol = "B 1234 ABC",
            tag = "ABEDEM EXPRESS - FOTO BARANG"
        )
        assertNotNull(watermarked)
        assertEquals(800, watermarked.width)
        assertEquals(600, watermarked.height)
    }

    @Test
    fun `verify excel exporter generates valid csv with nopol and address headers`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dummyOrders = listOf(
            OrderEntity(
                id = 1,
                orderName = "RESI-JKT-00123",
                goodsPhotoUri = "/data/user/0/com.example/files/order_photos/barang_1.jpg",
                waybillPhotoUri = "/data/user/0/com.example/files/order_photos/surat_jalan_1.jpg",
                latitude = -6.2088,
                longitude = 106.8456,
                address = "Jl. Sudirman No 45, Jakarta",
                nopol = "B 9999 XYZ",
                timestamp = System.currentTimeMillis(),
                ocrResultText = "Penerima: Budi Santoso\nAlamat: Jl. Sudirman No 45"
            )
        )

        val csvFile = ExcelExporter.exportOrdersToCsv(context, dummyOrders)
        assertTrue(csvFile.exists())
        assertTrue(csvFile.length() > 0)

        val content = csvFile.readText()
        assertTrue(content.contains("RESI-JKT-00123"))
        assertTrue(content.contains("B 9999 XYZ"))
        assertTrue(content.contains("Jl. Sudirman No 45, Jakarta"))
        assertTrue(content.contains("Penerima: Budi Santoso"))
        assertTrue(content.contains("Nopol"))
        assertTrue(content.contains("Surat Jalan"))
        assertTrue(content.contains("Link Foto Barang (Driver)"))
        assertTrue(content.contains("Link Foto Surat Jalan (Driver)"))

        val shareIntent = ExcelExporter.getShareIntent(context, csvFile)
        assertNotNull(shareIntent)
        assertEquals("text/csv", shareIntent.type)
        assertTrue(shareIntent.getStringExtra(android.content.Intent.EXTRA_SUBJECT)?.contains("ABDE LOGIS") == true)
    }
}
