package com.example

import android.app.DatePickerDialog
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.data.AppDatabase
import com.example.data.OrderEntity
import com.example.databinding.ActivityHistoryBinding
import com.example.databinding.DialogOrderDetailBinding
import com.example.ui.OrderAdapter
import com.example.util.ExcelExporter
import com.example.util.ImageUtils
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class HistoryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHistoryBinding
    private lateinit var database: AppDatabase
    private lateinit var orderAdapter: OrderAdapter

    private var currentOrders: List<OrderEntity> = emptyList()
    private var filterMode: FilterMode = FilterMode.ALL
    private var customStartTimestamp: Long? = null
    private var customEndTimestamp: Long? = null
    private var currentSearchQuery: String = ""
    private var loadJob: kotlinx.coroutines.Job? = null

    private val displayDateFormat = SimpleDateFormat("dd MMMM yyyy", Locale("id", "ID"))

    private enum class FilterMode {
        ALL,
        TODAY,
        LAST_7_DAYS,
        CUSTOM_DATE
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHistoryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        database = AppDatabase.getDatabase(this)

        setupRecyclerView()
        setupListeners()
        setupSearch()
        loadOrders()
    }

    private fun setupRecyclerView() {
        orderAdapter = OrderAdapter(
            onItemClick = { order -> showOrderDetailDialog(order) },
            onDeleteClick = { order -> confirmDeleteOrder(order) },
            onEditClick = { order -> showEditOrderDialog(order) }
        )
        binding.rvHistoryOrders.apply {
            layoutManager = LinearLayoutManager(this@HistoryActivity)
            adapter = orderAdapter
        }
    }

    private fun setupListeners() {
        binding.btnBack.setOnClickListener {
            finish()
        }

        binding.btnExportExcel.setOnClickListener {
            exportCurrentOrdersToExcel()
        }

        binding.btnShareExport.setOnClickListener {
            exportCurrentOrdersToExcel()
        }

        binding.btnResetDateFilter.setOnClickListener {
            binding.chipAll.isChecked = true
            filterMode = FilterMode.ALL
            customStartTimestamp = null
            customEndTimestamp = null
            binding.layoutActiveFilter.visibility = View.GONE
            loadOrders()
        }

        binding.chipGroupFilter.setOnCheckedStateChangeListener { _, checkedIds ->
            when {
                checkedIds.contains(R.id.chipAll) -> {
                    filterMode = FilterMode.ALL
                    customStartTimestamp = null
                    customEndTimestamp = null
                    binding.layoutActiveFilter.visibility = View.GONE
                    loadOrders()
                }
                checkedIds.contains(R.id.chipToday) -> {
                    filterMode = FilterMode.TODAY
                    setTodayRange()
                    binding.layoutActiveFilter.visibility = View.VISIBLE
                    binding.tvActiveFilterText.text = "Filter: Hari Ini (${displayDateFormat.format(Date())})"
                    loadOrders()
                }
                checkedIds.contains(R.id.chip7Days) -> {
                    filterMode = FilterMode.LAST_7_DAYS
                    setLast7DaysRange()
                    binding.layoutActiveFilter.visibility = View.VISIBLE
                    binding.tvActiveFilterText.text = "Filter: 7 Hari Terakhir"
                    loadOrders()
                }
                checkedIds.contains(R.id.chipCustomDate) -> {
                    showDatePickerDialog()
                }
            }
        }
    }

    private fun setupSearch() {
        binding.etSearchHistory.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                currentSearchQuery = s?.toString()?.trim() ?: ""
                binding.btnClearSearch.visibility = if (currentSearchQuery.isNotEmpty()) View.VISIBLE else View.GONE
                loadOrders()
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        binding.btnClearSearch.setOnClickListener {
            binding.etSearchHistory.text.clear()
        }
    }

    private fun setTodayRange() {
        val calendar = Calendar.getInstance()
        calendar.set(Calendar.HOUR_OF_DAY, 0)
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        customStartTimestamp = calendar.timeInMillis

        calendar.set(Calendar.HOUR_OF_DAY, 23)
        calendar.set(Calendar.MINUTE, 59)
        calendar.set(Calendar.SECOND, 59)
        calendar.set(Calendar.MILLISECOND, 999)
        customEndTimestamp = calendar.timeInMillis
    }

    private fun setLast7DaysRange() {
        val calendar = Calendar.getInstance()
        calendar.set(Calendar.HOUR_OF_DAY, 23)
        calendar.set(Calendar.MINUTE, 59)
        calendar.set(Calendar.SECOND, 59)
        calendar.set(Calendar.MILLISECOND, 999)
        customEndTimestamp = calendar.timeInMillis

        calendar.add(Calendar.DAY_OF_YEAR, -7)
        calendar.set(Calendar.HOUR_OF_DAY, 0)
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        customStartTimestamp = calendar.timeInMillis
    }

    private fun showDatePickerDialog() {
        val calendar = Calendar.getInstance()
        val datePicker = DatePickerDialog(
            this,
            { _, year, month, dayOfMonth ->
                val selectedCal = Calendar.getInstance().apply {
                    set(Calendar.YEAR, year)
                    set(Calendar.MONTH, month)
                    set(Calendar.DAY_OF_MONTH, dayOfMonth)
                    set(Calendar.HOUR_OF_DAY, 0)
                    set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }
                customStartTimestamp = selectedCal.timeInMillis

                selectedCal.set(Calendar.HOUR_OF_DAY, 23)
                selectedCal.set(Calendar.MINUTE, 59)
                selectedCal.set(Calendar.SECOND, 59)
                selectedCal.set(Calendar.MILLISECOND, 999)
                customEndTimestamp = selectedCal.timeInMillis

                filterMode = FilterMode.CUSTOM_DATE
                val dateStr = displayDateFormat.format(Date(customStartTimestamp!!))
                binding.layoutActiveFilter.visibility = View.VISIBLE
                binding.tvActiveFilterText.text = "Filter Tanggal: $dateStr"
                loadOrders()
            },
            calendar.get(Calendar.YEAR),
            calendar.get(Calendar.MONTH),
            calendar.get(Calendar.DAY_OF_MONTH)
        )
        datePicker.setOnCancelListener {
            if (filterMode != FilterMode.CUSTOM_DATE) {
                binding.chipAll.isChecked = true
            }
        }
        datePicker.show()
    }

    private fun loadOrders() {
        loadJob?.cancel()
        loadJob = lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                val flow = when {
                    customStartTimestamp != null && customEndTimestamp != null && currentSearchQuery.isNotEmpty() -> {
                        database.orderDao().searchOrdersWithDateRange(
                            currentSearchQuery,
                            customStartTimestamp!!,
                            customEndTimestamp!!
                        )
                    }
                    customStartTimestamp != null && customEndTimestamp != null -> {
                        database.orderDao().getOrdersByDateRange(
                            customStartTimestamp!!,
                            customEndTimestamp!!
                        )
                    }
                    currentSearchQuery.isNotEmpty() -> {
                        database.orderDao().searchOrders(currentSearchQuery)
                    }
                    else -> {
                        database.orderDao().getAllOrders()
                    }
                }

                flow.collectLatest { orders ->
                    currentOrders = orders
                    orderAdapter.submitList(orders)
                    binding.tvHistoryCount.text = "TOTAL: ${orders.size} ORDER"

                    if (orders.isEmpty()) {
                        binding.layoutEmptyHistory.visibility = View.VISIBLE
                        binding.rvHistoryOrders.visibility = View.GONE
                        if (currentSearchQuery.isNotEmpty()) {
                            binding.tvEmptyTitle.text = "Hasil Tidak Ditemukan"
                            binding.tvEmptyDescription.text = "Tidak ada orderan yang cocok dengan pencarian '$currentSearchQuery'"
                        } else if (filterMode != FilterMode.ALL) {
                            binding.tvEmptyTitle.text = "Tidak Ada Order di Tanggal Ini"
                            binding.tvEmptyDescription.text = "Belum ada riwayat pengiriman order pada rentang tanggal yang dipilih."
                        } else {
                            binding.tvEmptyTitle.text = "Belum Ada Riwayat Order"
                            binding.tvEmptyDescription.text = "Orderan yang Anda simpan dari kamera akan otomatis muncul di sini."
                        }
                    } else {
                        binding.layoutEmptyHistory.visibility = View.GONE
                        binding.rvHistoryOrders.visibility = View.VISIBLE
                    }
                }
            }
        }
    }

    private fun exportCurrentOrdersToExcel() {
        if (currentOrders.isEmpty()) {
            Toast.makeText(this, "Tidak ada data riwayat order untuk di-export", Toast.LENGTH_SHORT).show()
            return
        }

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val exportedFile = ExcelExporter.exportOrdersToCsv(this@HistoryActivity, currentOrders)
                val shareIntent = ExcelExporter.getShareIntent(this@HistoryActivity, exportedFile)

                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@HistoryActivity,
                        "Berhasil export ${currentOrders.size} order ke file Excel (.csv)",
                        Toast.LENGTH_SHORT
                    ).show()

                    val chooser = Intent.createChooser(shareIntent, "Bagikan / Buka File Excel Riwayat Order")
                    startActivity(chooser)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@HistoryActivity,
                        "Gagal mengekspor file: ${e.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun confirmDeleteOrder(order: OrderEntity) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Hapus Riwayat Order")
            .setMessage("Apakah Anda yakin ingin menghapus catatan order '${order.orderName}'?")
            .setPositiveButton("Hapus") { _, _ ->
                lifecycleScope.launch(Dispatchers.IO) {
                    database.orderDao().deleteOrder(order)
                    if (order.goodsPhotoUri.isNotBlank()) File(order.goodsPhotoUri).delete()
                    if (order.waybillPhotoUri.isNotBlank()) File(order.waybillPhotoUri).delete()
                }
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun showOrderDetailDialog(order: OrderEntity) {
        val dialogBinding = DialogOrderDetailBinding.inflate(layoutInflater)
        val dialog = AlertDialog.Builder(this)
            .setView(dialogBinding.root)
            .create()

        dialogBinding.tvDetailTitle.text = order.orderName

        if (order.nopol.isNotBlank()) {
            dialogBinding.tvDetailNopol.text = order.nopol.uppercase()
            dialogBinding.tvDetailNopol.visibility = View.VISIBLE
        } else {
            dialogBinding.tvDetailNopol.visibility = View.GONE
        }

        val dateFormat = SimpleDateFormat("EEEE, dd MMMM yyyy HH:mm:ss 'WIB'", Locale("id", "ID"))
        val dateText = dateFormat.format(Date(order.timestamp))
        val locationText = if (order.latitude != 0.0 || order.longitude != 0.0) {
            String.format(Locale.US, "GPS: %.6f, %.6f", order.latitude, order.longitude)
        } else {
            "GPS: Lokasi tidak tersedia"
        }
        dialogBinding.tvDetailMeta.text = "$dateText\n$locationText"

        if (order.address.isNotBlank()) {
            dialogBinding.layoutDetailAddress.visibility = View.VISIBLE
            dialogBinding.tvDetailAddress.text = order.address
        } else {
            dialogBinding.layoutDetailAddress.visibility = View.GONE
        }

        if (order.goodsPhotoUri.isNotBlank() && File(order.goodsPhotoUri).exists()) {
            val bitmap = BitmapFactory.decodeFile(order.goodsPhotoUri)
            dialogBinding.ivDetailGoods.setImageBitmap(bitmap)
            dialogBinding.ivDetailGoods.visibility = View.VISIBLE
            dialogBinding.btnDownloadGoodsPhoto.visibility = View.VISIBLE
            dialogBinding.btnShareGoodsPhoto.visibility = View.VISIBLE

            dialogBinding.btnDownloadGoodsPhoto.setOnClickListener {
                downloadPhoto(order.goodsPhotoUri, "BARANG_${order.orderName}")
            }
            dialogBinding.btnShareGoodsPhoto.setOnClickListener {
                sharePhoto(order.goodsPhotoUri, "Foto Barang: ${order.orderName}")
            }
            dialogBinding.ivDetailGoods.setOnClickListener {
                showFullscreenPhotoDialog(order.goodsPhotoUri, "Foto Barang - ${order.orderName}")
            }
        } else {
            dialogBinding.ivDetailGoods.visibility = View.GONE
            dialogBinding.btnDownloadGoodsPhoto.visibility = View.GONE
            dialogBinding.btnShareGoodsPhoto.visibility = View.GONE
        }

        if (order.waybillPhotoUri.isNotBlank() && File(order.waybillPhotoUri).exists()) {
            val bitmap = BitmapFactory.decodeFile(order.waybillPhotoUri)
            dialogBinding.ivDetailWaybill.setImageBitmap(bitmap)
            dialogBinding.ivDetailWaybill.visibility = View.VISIBLE
            dialogBinding.btnDownloadWaybillPhoto.visibility = View.VISIBLE
            dialogBinding.btnShareWaybillPhoto.visibility = View.VISIBLE

            dialogBinding.btnDownloadWaybillPhoto.setOnClickListener {
                downloadPhoto(order.waybillPhotoUri, "SURAT_JALAN_${order.orderName}")
            }
            dialogBinding.btnShareWaybillPhoto.setOnClickListener {
                sharePhoto(order.waybillPhotoUri, "Foto Surat Jalan: ${order.orderName}")
            }
            dialogBinding.ivDetailWaybill.setOnClickListener {
                showFullscreenPhotoDialog(order.waybillPhotoUri, "Foto Surat Jalan - ${order.orderName}")
            }
        } else {
            dialogBinding.ivDetailWaybill.visibility = View.GONE
            dialogBinding.btnDownloadWaybillPhoto.visibility = View.GONE
            dialogBinding.btnShareWaybillPhoto.visibility = View.GONE
        }

        if (order.ocrResultText.isNotBlank()) {
            dialogBinding.tvDetailOcr.text = order.ocrResultText
            dialogBinding.tvDetailOcr.visibility = View.VISIBLE
        } else {
            dialogBinding.tvDetailOcr.text = "Tidak ada teks OCR tersimpan"
        }

        dialogBinding.btnDetailClose.setOnClickListener {
            dialog.dismiss()
        }

        dialogBinding.btnDetailOpenDrive.setOnClickListener {
            openGoogleDriveFolder()
        }

        dialogBinding.btnDetailEdit.setOnClickListener {
            dialog.dismiss()
            showEditOrderDialog(order)
        }

        dialog.show()
    }

    private fun downloadPhoto(photoPath: String, titlePrefix: String) {
        val uri = ImageUtils.downloadImageToGallery(this, photoPath, titlePrefix)
        if (uri != null) {
            Toast.makeText(this, "Foto berhasil di-download & disimpan ke Galeri HP (Pictures/AbdeKurir)", Toast.LENGTH_LONG).show()
        } else {
            Toast.makeText(this, "Gagal men-download foto. File tidak ditemukan.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun sharePhoto(photoPath: String, subject: String) {
        val intent = ImageUtils.getShareImageIntent(this, photoPath, subject)
        if (intent != null) {
            startActivity(Intent.createChooser(intent, "Bagikan Foto Melalui..."))
        } else {
            Toast.makeText(this, "File foto tidak ditemukan", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showFullscreenPhotoDialog(photoPath: String, title: String) {
        val file = File(photoPath)
        if (!file.exists()) return

        val viewerBinding = com.example.databinding.DialogFullscreenPhotoBinding.inflate(layoutInflater)
        val dialog = AlertDialog.Builder(this)
            .setView(viewerBinding.root)
            .create()

        viewerBinding.tvViewerTitle.text = title
        val bitmap = BitmapFactory.decodeFile(photoPath)
        viewerBinding.ivViewerImage.setImageBitmap(bitmap)

        viewerBinding.btnViewerClose.setOnClickListener { dialog.dismiss() }
        viewerBinding.btnViewerDownload.setOnClickListener {
            downloadPhoto(photoPath, title.replace(" ", "_"))
        }
        viewerBinding.btnViewerShare.setOnClickListener {
            sharePhoto(photoPath, title)
        }

        dialog.show()
    }

    private fun openGoogleDriveFolder() {
        try {
            val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(ExcelExporter.GOOGLE_DRIVE_FOLDER_URL))
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "Tidak dapat membuka link Google Drive: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showEditOrderDialog(order: OrderEntity) {
        val editBinding = com.example.databinding.DialogEditOrderBinding.inflate(layoutInflater)
        val dialog = AlertDialog.Builder(this)
            .setView(editBinding.root)
            .create()

        var currentGoodsPhoto = order.goodsPhotoUri
        var currentWaybillPhoto = order.waybillPhotoUri

        // Load Goods Photo Preview
        if (currentGoodsPhoto.isNotBlank() && File(currentGoodsPhoto).exists()) {
            val bmp = BitmapFactory.decodeFile(currentGoodsPhoto)
            editBinding.ivEditGoodsPreview.setImageBitmap(bmp)
        } else {
            editBinding.ivEditGoodsPreview.setImageResource(R.drawable.ic_camera)
        }

        // Load Waybill Photo Preview
        if (currentWaybillPhoto.isNotBlank() && File(currentWaybillPhoto).exists()) {
            val bmp = BitmapFactory.decodeFile(currentWaybillPhoto)
            editBinding.ivEditWaybillPreview.setImageBitmap(bmp)
        } else {
            editBinding.ivEditWaybillPreview.setImageResource(R.drawable.ic_camera)
        }

        editBinding.etEditNopol.setText(order.nopol)
        editBinding.etEditOrderName.setText(order.orderName)
        editBinding.etEditAddress.setText(order.address)
        editBinding.etEditOcr.setText(order.ocrResultText)

        // Launch full Camera & Edit screen in NewOrderActivity
        val launchFullEdit = {
            dialog.dismiss()
            val intent = Intent(this, NewOrderActivity::class.java).apply {
                putExtra("EXTRA_EDIT_ORDER_ID", order.id)
            }
            startActivity(intent)
        }

        editBinding.btnOpenFullEditCamera.setOnClickListener { launchFullEdit() }
        editBinding.btnEditChangeGoods.setOnClickListener { launchFullEdit() }
        editBinding.btnEditChangeWaybill.setOnClickListener { launchFullEdit() }

        // Delete Goods Photo action
        editBinding.btnEditDeleteGoods.setOnClickListener {
            currentGoodsPhoto = ""
            editBinding.ivEditGoodsPreview.setImageResource(R.drawable.ic_camera)
            Toast.makeText(this, "Foto barang dihapus dari catatan order", Toast.LENGTH_SHORT).show()
        }

        // Delete Waybill Photo action
        editBinding.btnEditDeleteWaybill.setOnClickListener {
            currentWaybillPhoto = ""
            editBinding.ivEditWaybillPreview.setImageResource(R.drawable.ic_camera)
            Toast.makeText(this, "Foto surat jalan dihapus dari catatan order", Toast.LENGTH_SHORT).show()
        }

        editBinding.btnCancelEdit.setOnClickListener {
            dialog.dismiss()
        }

        editBinding.btnSaveEdit.setOnClickListener {
            val updatedNopol = editBinding.etEditNopol.text?.toString()?.trim() ?: ""
            var updatedOrderName = editBinding.etEditOrderName.text?.toString()?.trim() ?: ""
            val updatedAddress = editBinding.etEditAddress.text?.toString()?.trim() ?: ""
            val updatedOcr = editBinding.etEditOcr.text?.toString()?.trim() ?: ""

            if (updatedOrderName.isBlank()) {
                updatedOrderName = "SJ-${order.id}"
            }

            val updatedOrder = order.copy(
                nopol = updatedNopol,
                orderName = updatedOrderName,
                address = updatedAddress,
                ocrResultText = updatedOcr,
                goodsPhotoUri = currentGoodsPhoto,
                waybillPhotoUri = currentWaybillPhoto
            )

            lifecycleScope.launch(Dispatchers.IO) {
                database.orderDao().updateOrder(updatedOrder)
                withContext(Dispatchers.Main) {
                    dialog.dismiss()
                    Toast.makeText(this@HistoryActivity, "Data & foto pengiriman berhasil diperbarui!", Toast.LENGTH_SHORT).show()
                }
            }
        }

        dialog.show()
    }
}
