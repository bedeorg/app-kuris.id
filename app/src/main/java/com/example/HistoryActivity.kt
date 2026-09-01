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
            onDeleteClick = { order -> confirmDeleteOrder(order) }
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
        } else {
            dialogBinding.ivDetailGoods.visibility = View.GONE
        }

        if (order.waybillPhotoUri.isNotBlank() && File(order.waybillPhotoUri).exists()) {
            val bitmap = BitmapFactory.decodeFile(order.waybillPhotoUri)
            dialogBinding.ivDetailWaybill.setImageBitmap(bitmap)
            dialogBinding.ivDetailWaybill.visibility = View.VISIBLE
        } else {
            dialogBinding.ivDetailWaybill.visibility = View.GONE
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

        dialog.show()
    }
}
