package com.example

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.location.Address
import android.location.Geocoder
import android.location.Location
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.data.AppDatabase
import com.example.data.OrderEntity
import com.example.databinding.ActivityMainBinding
import com.example.databinding.DialogOrderDetailBinding
import com.example.ui.OrderAdapter
import com.example.util.ExcelExporter
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
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

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var database: AppDatabase
    private lateinit var orderAdapter: OrderAdapter
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var sharedPreferences: SharedPreferences

    private var allOrders: List<OrderEntity> = emptyList()
    private val dateFormat = SimpleDateFormat("dd MMM yyyy", Locale("id", "ID"))

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val fineLoc = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true
        val coarseLoc = permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true

        if (fineLoc || coarseLoc) {
            fetchDashboardLocation()
        } else {
            binding.tvDashboardGpsBadge.text = "GPS OFF"
            binding.tvDashboardAddress.text = "Izin lokasi tidak diberikan"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        database = AppDatabase.getDatabase(this)
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        sharedPreferences = getSharedPreferences("abedem_courier_prefs", Context.MODE_PRIVATE)

        setupDashboardHeader()
        setupRecyclerView()
        setupActionButtons()
        observeOrders()
        checkLocationPermission()
    }

    override fun onResume() {
        super.onResume()
        updateNopolDisplay()
    }

    private fun setupDashboardHeader() {
        binding.tvDashboardDate.text = dateFormat.format(Date())
        updateNopolDisplay()

        binding.btnEditNopol.setOnClickListener {
            showEditNopolDialog()
        }

        binding.btnRefreshDashboardLocation.setOnClickListener {
            fetchDashboardLocation()
        }
    }

    private fun updateNopolDisplay() {
        val savedNopol = sharedPreferences.getString("key_nopol", "B 1234 ABC") ?: "B 1234 ABC"
        binding.tvDashboardNopol.text = savedNopol.uppercase()
    }

    private fun showEditNopolDialog() {
        val currentNopol = sharedPreferences.getString("key_nopol", "B 1234 ABC") ?: "B 1234 ABC"
        val input = EditText(this).apply {
            setText(currentNopol)
            setSelection(currentNopol.length)
            setSingleLine(true)
            setPadding(48, 32, 48, 32)
        }

        MaterialAlertDialogBuilder(this)
            .setTitle("Ubah Nomor Polisi Kendaraan")
            .setMessage("Masukkan Nopol armada yang sedang Anda gunakan:")
            .setView(input)
            .setPositiveButton("Simpan") { _, _ ->
                val newNopol = input.text.toString().trim()
                if (newNopol.isNotBlank()) {
                    sharedPreferences.edit().putString("key_nopol", newNopol).apply()
                    updateNopolDisplay()
                    Toast.makeText(this, "Nopol berhasil diubah: $newNopol", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun setupRecyclerView() {
        orderAdapter = OrderAdapter(
            onItemClick = { order -> showOrderDetailDialog(order) },
            onDeleteClick = { order -> confirmDeleteOrder(order) }
        )
        binding.rvRecentOrders.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = orderAdapter
        }
    }

    private fun setupActionButtons() {
        // Hero Button: Input Order Baru
        binding.cardBtnNewOrder.setOnClickListener {
            startActivity(Intent(this, NewOrderActivity::class.java))
        }

        // Secondary Action: Riwayat
        binding.cardBtnHistory.setOnClickListener {
            startActivity(Intent(this, HistoryActivity::class.java))
        }

        binding.btnViewAllOrders.setOnClickListener {
            startActivity(Intent(this, HistoryActivity::class.java))
        }

        // Secondary Action: Export Excel
        binding.cardBtnExportExcel.setOnClickListener {
            exportOrdersToExcel()
        }
    }

    private fun observeOrders() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                database.orderDao().getAllOrders().collectLatest { orders ->
                    allOrders = orders

                    // 1. Total count
                    binding.tvStatTotalCount.text = orders.size.toString()

                    // 2. Today's count
                    val todayStart = Calendar.getInstance().apply {
                        set(Calendar.HOUR_OF_DAY, 0)
                        set(Calendar.MINUTE, 0)
                        set(Calendar.SECOND, 0)
                        set(Calendar.MILLISECOND, 0)
                    }.timeInMillis

                    val todayEnd = Calendar.getInstance().apply {
                        set(Calendar.HOUR_OF_DAY, 23)
                        set(Calendar.MINUTE, 59)
                        set(Calendar.SECOND, 59)
                        set(Calendar.MILLISECOND, 999)
                    }.timeInMillis

                    val todayOrders = orders.filter { it.timestamp in todayStart..todayEnd }
                    binding.tvStatTodayCount.text = todayOrders.size.toString()

                    // 3. Show recent 5 orders on dashboard
                    val recentOrders = orders.take(5)
                    orderAdapter.submitList(recentOrders)

                    if (orders.isEmpty()) {
                        binding.layoutEmptyDashboard.visibility = View.VISIBLE
                        binding.rvRecentOrders.visibility = View.GONE
                        binding.btnViewAllOrders.visibility = View.GONE
                    } else {
                        binding.layoutEmptyDashboard.visibility = View.GONE
                        binding.rvRecentOrders.visibility = View.VISIBLE
                        binding.btnViewAllOrders.visibility = View.VISIBLE
                    }
                }
            }
        }
    }

    private fun checkLocationPermission() {
        val permissions = arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )

        val hasFine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val hasCoarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

        if (hasFine || hasCoarse) {
            fetchDashboardLocation()
        } else {
            permissionLauncher.launch(permissions)
        }
    }

    private fun fetchDashboardLocation() {
        val hasFine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val hasCoarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

        if (!hasFine && !hasCoarse) {
            binding.tvDashboardGpsBadge.text = "GPS OFF"
            binding.tvDashboardAddress.text = "Izin lokasi tidak tersedia"
            return
        }

        binding.tvDashboardGpsBadge.text = "MENCARI GPS..."
        binding.tvDashboardAddress.text = "Mencari lokasi armada..."

        try {
            val cancellationTokenSource = CancellationTokenSource()
            fusedLocationClient.getCurrentLocation(
                Priority.PRIORITY_HIGH_ACCURACY,
                cancellationTokenSource.token
            ).addOnSuccessListener { location: Location? ->
                if (location != null) {
                    resolveDashboardAddress(location.latitude, location.longitude)
                } else {
                    fusedLocationClient.lastLocation.addOnSuccessListener { lastLoc ->
                        if (lastLoc != null) {
                            resolveDashboardAddress(lastLoc.latitude, lastLoc.longitude)
                        } else {
                            binding.tvDashboardGpsBadge.text = "GPS STANDBY"
                            binding.tvDashboardAddress.text = "GPS aktif (menunggu sinyal satelit)"
                        }
                    }
                }
            }.addOnFailureListener {
                binding.tvDashboardGpsBadge.text = "GPS ERROR"
                binding.tvDashboardAddress.text = "Gagal mengambil koordinat lokasi"
            }
        } catch (e: SecurityException) {
            binding.tvDashboardGpsBadge.text = "DENIED"
            binding.tvDashboardAddress.text = "Akses lokasi ditolak"
        }
    }

    private fun resolveDashboardAddress(lat: Double, lng: Double) {
        binding.tvDashboardGpsBadge.text = "GPS AKTIF"

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                if (Geocoder.isPresent()) {
                    val geocoder = Geocoder(this@MainActivity, Locale("id", "ID"))
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        geocoder.getFromLocation(lat, lng, 1) { addressList ->
                            val resolved = formatAddress(addressList, lat, lng)
                            runOnUiThread { binding.tvDashboardAddress.text = resolved }
                        }
                    } else {
                        @Suppress("DEPRECATION")
                        val addresses = geocoder.getFromLocation(lat, lng, 1)
                        val resolved = formatAddress(addresses, lat, lng)
                        runOnUiThread { binding.tvDashboardAddress.text = resolved }
                    }
                } else {
                    val fallback = "GPS: %.5f, %.5f".format(Locale.US, lat, lng)
                    runOnUiThread { binding.tvDashboardAddress.text = fallback }
                }
            } catch (e: Exception) {
                val fallback = "GPS: %.5f, %.5f".format(Locale.US, lat, lng)
                runOnUiThread { binding.tvDashboardAddress.text = fallback }
            }
        }
    }

    private fun formatAddress(addresses: List<Address>?, lat: Double, lng: Double): String {
        if (!addresses.isNullOrEmpty()) {
            val addr = addresses[0]
            val thoroughfare = addr.thoroughfare ?: addr.subThoroughfare ?: addr.featureName
            val subLocality = addr.subLocality ?: addr.locality
            val adminArea = addr.subAdminArea ?: addr.adminArea

            val parts = listOfNotNull(thoroughfare, subLocality, adminArea).filter { it.isNotBlank() }
            if (parts.isNotEmpty()) {
                return parts.joinToString(", ")
            } else if (!addr.getAddressLine(0).isNullOrBlank()) {
                return addr.getAddressLine(0)
            }
        }
        return "GPS: %.5f, %.5f".format(Locale.US, lat, lng)
    }

    private fun exportOrdersToExcel() {
        if (allOrders.isEmpty()) {
            Toast.makeText(this, "Belum ada riwayat order untuk di-export", Toast.LENGTH_SHORT).show()
            return
        }

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val exportedFile = ExcelExporter.exportOrdersToCsv(this@MainActivity, allOrders)
                val shareIntent = ExcelExporter.getShareIntent(this@MainActivity, exportedFile)

                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@MainActivity,
                        "Berhasil export ${allOrders.size} order ke file Excel (.csv)",
                        Toast.LENGTH_SHORT
                    ).show()

                    val chooser = Intent.createChooser(shareIntent, "Bagikan / Buka File Excel Riwayat Order")
                    startActivity(chooser)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@MainActivity,
                        "Gagal mengekspor file: ${e.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun confirmDeleteOrder(order: OrderEntity) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Hapus Orderan")
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
        val dialogBinding = DialogOrderDetailBinding.inflate(LayoutInflater.from(this))
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

        val fullDateFormat = SimpleDateFormat("EEEE, dd MMMM yyyy HH:mm:ss 'WIB'", Locale("id", "ID"))
        val dateText = fullDateFormat.format(Date(order.timestamp))
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
