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
import com.example.util.ImageUtils
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
        updatePengurusAndNopolDisplay()
    }

    private fun setupDashboardHeader() {
        binding.tvDashboardDate.text = dateFormat.format(Date())
        updatePengurusAndNopolDisplay()

        binding.btnEditNopol.setOnClickListener {
            showEditPengurusAndNopolDialog()
        }

        binding.cardVehicleInfo.setOnClickListener {
            showEditPengurusAndNopolDialog()
        }

        binding.btnRefreshDashboardLocation.setOnClickListener {
            fetchDashboardLocation()
        }
    }

    private fun updatePengurusAndNopolDisplay() {
        val savedPengurus = sharedPreferences.getString("key_pengurus_name", "Abedem") ?: "Abedem"
        binding.tvDashboardPengurusCard.text = savedPengurus
    }

    private fun showEditPengurusAndNopolDialog() {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_edit_pengurus_nopol, null)
        val etPengurus = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etDialogPengurus)
        val etNopol = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etDialogNopol)

        val currentPengurus = sharedPreferences.getString("key_pengurus_name", "Abedem") ?: "Abedem"
        val currentNopol = sharedPreferences.getString("key_nopol", "B 1234 ABC") ?: "B 1234 ABC"

        etPengurus.setText(currentPengurus)
        etNopol.setText(currentNopol)

        MaterialAlertDialogBuilder(this)
            .setView(dialogView)
            .setPositiveButton("Simpan") { _, _ ->
                val newPengurus = etPengurus.text?.toString()?.trim().orEmpty().ifBlank { "Abedem" }
                val newNopol = etNopol.text?.toString()?.trim().orEmpty().ifBlank { "B 1234 ABC" }

                sharedPreferences.edit()
                    .putString("key_pengurus_name", newPengurus)
                    .putString("key_nopol", newNopol)
                    .apply()

                updatePengurusAndNopolDisplay()
                Toast.makeText(this, "Data diperbarui: $newPengurus ($newNopol)", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun setupRecyclerView() {
        orderAdapter = OrderAdapter(
            onItemClick = { order -> showOrderDetailDialog(order) },
            onDeleteClick = { order -> confirmDeleteOrder(order) },
            onEditClick = { order -> showEditOrderDialog(order) }
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

        // Google Drive Button
        binding.cardBtnGoogleDrive.setOnClickListener {
            openGoogleDriveFolder()
        }
    }

    private fun openGoogleDriveFolder() {
        try {
            val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(ExcelExporter.GOOGLE_DRIVE_FOLDER_URL))
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "Tidak dapat membuka link Google Drive: ${e.message}", Toast.LENGTH_SHORT).show()
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

        val viewerBinding = com.example.databinding.DialogFullscreenPhotoBinding.inflate(LayoutInflater.from(this))
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

    private fun showEditOrderDialog(order: OrderEntity) {
        val editBinding = com.example.databinding.DialogEditOrderBinding.inflate(LayoutInflater.from(this))
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
                    Toast.makeText(this@MainActivity, "Data & foto pengiriman berhasil diperbarui!", Toast.LENGTH_SHORT).show()
                }
            }
        }

        dialog.show()
    }
}
