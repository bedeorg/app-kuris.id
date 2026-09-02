package com.example

import android.Manifest
import android.app.DatePickerDialog
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.location.Address
import android.location.Geocoder
import android.location.Location
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.data.AppDatabase
import com.example.data.OrderEntity
import com.example.databinding.ActivityNewOrderBinding
import com.example.util.ImageUtils
import com.example.util.PlateOcrUtils
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.google.android.material.snackbar.Snackbar
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class NewOrderActivity : AppCompatActivity() {

    private lateinit var binding: ActivityNewOrderBinding
    private lateinit var database: AppDatabase
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var sharedPreferences: SharedPreferences

    private var imageCapture: ImageCapture? = null
    private var currentCaptureTarget: CaptureTarget = CaptureTarget.GOODS

    private var currentLatitude: Double = 0.0
    private var currentLongitude: Double = 0.0
    private var currentAddress: String = ""
    private var selectedDeliveryTimestamp: Long = System.currentTimeMillis()
    private var goodsPhotoPath: String? = null
    private var waybillPhotoPath: String? = null

    private var editOrderId: Long = -1L
    private var editingOrder: OrderEntity? = null

    private val quickDateFormat = SimpleDateFormat("dd MMM yyyy", Locale("id", "ID"))

    private enum class CaptureTarget {
        GOODS,
        WAYBILL
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val cameraGranted = permissions[Manifest.permission.CAMERA] == true
        val fineLocGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true
        val coarseLocGranted = permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true

        if (fineLocGranted || coarseLocGranted) {
            fetchCurrentLocation()
        } else {
            binding.tvGpsStatusBadge.text = "GPS OFF"
            binding.tvMetadataLatitude.text = "Disabled"
            binding.tvMetadataLongitude.text = "Disabled"
            binding.tvQuickAddress.text = "Izin GPS tidak diberikan"
        }

        if (!cameraGranted) {
            Toast.makeText(this, "Izin kamera diperlukan untuk mengambil foto bukti", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityNewOrderBinding.inflate(layoutInflater)
        setContentView(binding.root)

        database = AppDatabase.getDatabase(this)
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        cameraExecutor = Executors.newSingleThreadExecutor()
        sharedPreferences = getSharedPreferences("abedem_courier_prefs", Context.MODE_PRIVATE)

        setupQuickEntry()
        setupListeners()
        checkAndRequestPermissions()

        editOrderId = intent.getLongExtra("EXTRA_EDIT_ORDER_ID", -1L)
        if (editOrderId != -1L) {
            loadOrderForEditing(editOrderId)
        }
    }

    private fun loadOrderForEditing(orderId: Long) {
        lifecycleScope.launch(Dispatchers.IO) {
            val order = database.orderDao().getOrderById(orderId)
            if (order != null) {
                editingOrder = order
                withContext(Dispatchers.Main) {
                    binding.tvNewOrderTitle.text = "Edit Order Pengiriman"
                    binding.tvNewOrderSubtitle.text = "Perbarui Foto Barang, Surat Jalan, Nopol, Alamat & OCR"
                    binding.btnSubmitOrder.text = "SIMPAN PERUBAHAN ORDER"

                    selectedDeliveryTimestamp = order.timestamp
                    binding.tvQuickDateValue.text = quickDateFormat.format(Date(selectedDeliveryTimestamp))

                    binding.etQuickNopol.setText(order.nopol)
                    binding.etOrderName.setText(order.orderName)
                    binding.tvQuickAddress.text = order.address
                    binding.etOcrResult.setText(order.ocrResultText)

                    if (order.latitude != 0.0 || order.longitude != 0.0) {
                        currentLatitude = order.latitude
                        currentLongitude = order.longitude
                        currentAddress = order.address
                        binding.tvMetadataLatitude.text = String.format(Locale.US, "%.5f", currentLatitude)
                        binding.tvMetadataLongitude.text = String.format(Locale.US, "%.5f", currentLongitude)
                        binding.tvQuickAddress.text = currentAddress
                    }

                    if (order.goodsPhotoUri.isNotBlank()) {
                        val file = File(order.goodsPhotoUri)
                        if (file.exists()) {
                            goodsPhotoPath = order.goodsPhotoUri
                            val bmp = BitmapFactory.decodeFile(order.goodsPhotoUri)
                            binding.ivGoodsPreview.setImageBitmap(bmp)
                            binding.tvGoodsStatus.text = "Foto tersimpan (Ketuk untuk preview)"
                            binding.tvGoodsStatus.setTextColor(ContextCompat.getColor(this@NewOrderActivity, R.color.status_online))
                            binding.tvGoodsBadge.text = "TERSEDIA"
                            binding.tvGoodsBadge.setBackgroundResource(R.drawable.bg_badge_green)
                            binding.tvGoodsBadge.setTextColor(ContextCompat.getColor(this@NewOrderActivity, R.color.badge_green_text))
                            binding.btnCaptureGoods.text = "Ganti / Foto Ulang Barang"
                        }
                    }

                    if (order.waybillPhotoUri.isNotBlank()) {
                        val file = File(order.waybillPhotoUri)
                        if (file.exists()) {
                            waybillPhotoPath = order.waybillPhotoUri
                            val bmp = BitmapFactory.decodeFile(order.waybillPhotoUri)
                            binding.ivWaybillPreview.setImageBitmap(bmp)
                            binding.tvWaybillStatus.text = "Foto & OCR tersimpan (Ketuk untuk preview)"
                            binding.tvWaybillStatus.setTextColor(ContextCompat.getColor(this@NewOrderActivity, R.color.status_online))
                            binding.tvWaybillBadge.text = "TERSEDIA"
                            binding.tvWaybillBadge.setBackgroundResource(R.drawable.bg_badge_green)
                            binding.tvWaybillBadge.setTextColor(ContextCompat.getColor(this@NewOrderActivity, R.color.badge_green_text))
                            binding.btnCaptureWaybill.text = "Ganti / Scan Ulang Surat Jalan"
                        }
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }

    private fun setupQuickEntry() {
        selectedDeliveryTimestamp = System.currentTimeMillis()
        binding.tvQuickDateValue.text = quickDateFormat.format(Date(selectedDeliveryTimestamp))

        val savedPengurus = sharedPreferences.getString("key_pengurus_name", "Abedem") ?: "Abedem"
        binding.tvQuickPengurus.text = "Pengurus: $savedPengurus"

        val savedNopol = sharedPreferences.getString("key_nopol", "B 1234 ABC") ?: "B 1234 ABC"
        binding.etQuickNopol.setText(savedNopol)

        binding.etQuickNopol.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val nopol = s?.toString()?.trim() ?: ""
                sharedPreferences.edit().putString("key_nopol", nopol).apply()
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        binding.cardQuickDate.setOnClickListener {
            showQuickDatePicker()
        }

        binding.tvQuickPengurus.setOnClickListener {
            showEditPengurusDialog()
        }
    }

    private fun showEditPengurusDialog() {
        val currentPengurus = sharedPreferences.getString("key_pengurus_name", "Abedem") ?: "Abedem"
        val input = android.widget.EditText(this).apply {
            setText(currentPengurus)
            setSelection(currentPengurus.length)
            setSingleLine(true)
            setPadding(48, 32, 48, 32)
        }

        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("Ubah Nama Pengurus / Kurir")
            .setMessage("Masukkan nama pengurus/petugas untuk order pengiriman ini:")
            .setView(input)
            .setPositiveButton("Simpan") { _, _ ->
                val newPengurus = input.text.toString().trim().ifBlank { "Abedem" }
                sharedPreferences.edit().putString("key_pengurus_name", newPengurus).apply()
                binding.tvQuickPengurus.text = "Pengurus: $newPengurus"
                Toast.makeText(this, "Pengurus diperbarui: $newPengurus", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun showQuickDatePicker() {
        val cal = Calendar.getInstance().apply { timeInMillis = selectedDeliveryTimestamp }
        val datePicker = DatePickerDialog(
            this,
            { _, year, month, dayOfMonth ->
                val selectedCal = Calendar.getInstance().apply {
                    set(Calendar.YEAR, year)
                    set(Calendar.MONTH, month)
                    set(Calendar.DAY_OF_MONTH, dayOfMonth)
                }
                selectedDeliveryTimestamp = selectedCal.timeInMillis
                binding.tvQuickDateValue.text = quickDateFormat.format(Date(selectedDeliveryTimestamp))
            },
            cal.get(Calendar.YEAR),
            cal.get(Calendar.MONTH),
            cal.get(Calendar.DAY_OF_MONTH)
        )
        datePicker.show()
    }

    private fun setupListeners() {
        binding.btnBackNewOrder.setOnClickListener {
            finish()
        }

        binding.btnCaptureGoods.setOnClickListener {
            checkCameraPermissionAndLaunch(CaptureTarget.GOODS)
        }

        binding.btnCaptureWaybill.setOnClickListener {
            checkCameraPermissionAndLaunch(CaptureTarget.WAYBILL)
        }

        binding.btnCloseCamera.setOnClickListener {
            closeCameraOverlay()
        }

        binding.btnShutter.setOnClickListener {
            takePhoto()
        }

        binding.ivGoodsPreview.setOnClickListener {
            goodsPhotoPath?.let { path ->
                showFullscreenPhotoDialog(path, "Foto Barang")
            }
        }

        binding.ivWaybillPreview.setOnClickListener {
            waybillPhotoPath?.let { path ->
                showFullscreenPhotoDialog(path, "Foto Surat Jalan")
            }
        }

        binding.btnRefreshLocation.setOnClickListener {
            fetchCurrentLocation()
        }

        binding.btnSubmitOrder.setOnClickListener {
            submitOrderToDatabase()
        }
    }

    private fun checkAndRequestPermissions() {
        val permissions = arrayOf(
            Manifest.permission.CAMERA,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )

        val needRequest = permissions.any {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (needRequest) {
            permissionLauncher.launch(permissions)
        } else {
            fetchCurrentLocation()
        }
    }

    private fun fetchCurrentLocation() {
        val hasFine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val hasCoarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

        if (!hasFine && !hasCoarse) {
            binding.tvGpsStatusBadge.text = "GPS OFF"
            binding.tvMetadataLatitude.text = "No Permission"
            binding.tvMetadataLongitude.text = "No Permission"
            binding.tvQuickAddress.text = "Izin lokasi tidak tersedia"
            return
        }

        binding.tvGpsStatusBadge.text = "SEARCHING..."
        binding.tvQuickAddress.text = "Mencari koordinat & alamat GPS..."

        try {
            val cancellationTokenSource = CancellationTokenSource()
            fusedLocationClient.getCurrentLocation(
                Priority.PRIORITY_HIGH_ACCURACY,
                cancellationTokenSource.token
            ).addOnSuccessListener { location: Location? ->
                if (location != null) {
                    handleLocationUpdate(location.latitude, location.longitude)
                } else {
                    fusedLocationClient.lastLocation.addOnSuccessListener { lastLoc ->
                        if (lastLoc != null) {
                            handleLocationUpdate(lastLoc.latitude, lastLoc.longitude)
                        } else {
                            binding.tvGpsStatusBadge.text = "READY"
                            binding.tvQuickAddress.text = "GPS standby (menunggu sinyal satelit)"
                        }
                    }
                }
            }.addOnFailureListener {
                binding.tvGpsStatusBadge.text = "GPS ERROR"
                binding.tvQuickAddress.text = "Gagal mengambil koordinat GPS"
            }
        } catch (e: SecurityException) {
            binding.tvGpsStatusBadge.text = "DENIED"
            binding.tvQuickAddress.text = "Akses lokasi ditolak"
        }
    }

    private fun handleLocationUpdate(lat: Double, lng: Double) {
        currentLatitude = lat
        currentLongitude = lng
        val latStr = String.format(Locale.US, "%.6f", lat)
        val lngStr = String.format(Locale.US, "%.6f", lng)

        binding.tvMetadataLatitude.text = latStr
        binding.tvMetadataLongitude.text = lngStr
        binding.tvGpsStatusBadge.text = "GPS ACTIVE"
        binding.tvCameraGpsIndicator.text = "GPS: $latStr, $lngStr"

        resolveAddressFromCoordinates(lat, lng)
    }

    private fun resolveAddressFromCoordinates(latitude: Double, longitude: Double) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                if (Geocoder.isPresent()) {
                    val geocoder = Geocoder(this@NewOrderActivity, Locale("id", "ID"))
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        geocoder.getFromLocation(latitude, longitude, 1) { addressList ->
                            val resolved = formatAddressFromList(addressList, latitude, longitude)
                            updateAddressUi(resolved)
                        }
                    } else {
                        @Suppress("DEPRECATION")
                        val addresses = geocoder.getFromLocation(latitude, longitude, 1)
                        val resolved = formatAddressFromList(addresses, latitude, longitude)
                        updateAddressUi(resolved)
                    }
                } else {
                    val fallback = "GPS: %.5f, %.5f".format(Locale.US, latitude, longitude)
                    updateAddressUi(fallback)
                }
            } catch (e: Exception) {
                val fallback = "GPS: %.5f, %.5f".format(Locale.US, latitude, longitude)
                updateAddressUi(fallback)
            }
        }
    }

    private fun formatAddressFromList(addresses: List<Address>?, lat: Double, lng: Double): String {
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

    private fun updateAddressUi(addressText: String) {
        currentAddress = addressText
        runOnUiThread {
            binding.tvQuickAddress.text = addressText
            binding.tvCameraAddressIndicator.text = "Alamat: $addressText"
        }
    }

    private fun checkCameraPermissionAndLaunch(target: CaptureTarget) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            openCamera(target)
        } else {
            permissionLauncher.launch(arrayOf(Manifest.permission.CAMERA))
        }
    }

    private fun openCamera(target: CaptureTarget) {
        currentCaptureTarget = target
        binding.layoutCameraOverlay.visibility = View.VISIBLE

        val timeFormat = SimpleDateFormat("dd MMM yyyy - HH:mm 'WIB'", Locale("id", "ID"))
        binding.tvCameraTimeIndicator.text = "Waktu & Tgl: ${timeFormat.format(Date())}"

        val dispAddress = if (currentAddress.isNotBlank()) currentAddress else "Mencari lokasi GPS..."
        binding.tvCameraAddressIndicator.text = "Alamat: $dispAddress"

        when (target) {
            CaptureTarget.GOODS -> {
                binding.tvCameraTitle.text = "Item Photo (Barang)"
                binding.tvCameraInstruction.text = "Arahkan kamera ke paket/barang yang diantar"
            }
            CaptureTarget.WAYBILL -> {
                binding.tvCameraTitle.text = "Label Scan (Surat Jalan)"
                binding.tvCameraInstruction.text = "Arahkan kamera ke teks/kertas surat jalan agar terbaca jelas"
            }
        }

        startCameraX()
    }

    private fun closeCameraOverlay() {
        binding.layoutCameraOverlay.visibility = View.GONE
        binding.layoutProcessing.visibility = View.GONE
    }

    private fun startCameraX() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(binding.previewView.surfaceProvider)
            }

            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this,
                    cameraSelector,
                    preview,
                    imageCapture
                )
            } catch (exc: Exception) {
                Toast.makeText(this, "Gagal menginisialisasi kamera: ${exc.message}", Toast.LENGTH_SHORT).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun takePhoto() {
        val imageCapture = this.imageCapture ?: return

        binding.layoutProcessing.visibility = View.VISIBLE
        binding.tvProcessingStatus.text = "Memproses Watermark (Alamat & Nopol)..."

        imageCapture.takePicture(
            cameraExecutor,
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(imageProxy: ImageProxy) {
                    processCapturedImageProxy(imageProxy)
                }

                override fun onError(exception: ImageCaptureException) {
                    runOnUiThread {
                        binding.layoutProcessing.visibility = View.GONE
                        Toast.makeText(
                            this@NewOrderActivity,
                            "Gagal mengambil gambar: ${exception.message}",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
        )
    }

    private fun processCapturedImageProxy(imageProxy: ImageProxy) {
        val rotationDegrees = imageProxy.imageInfo.rotationDegrees
        val buffer = imageProxy.planes[0].buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        imageProxy.close()

        lifecycleScope.launch(Dispatchers.Default) {
            try {
                withContext(Dispatchers.Main) {
                    binding.tvProcessingStatus.text = "Menganalisis Foto & Membaca Plat Nopol..."
                }

                var rawBitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                if (rotationDegrees != 0) {
                    val matrix = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
                    rawBitmap = Bitmap.createBitmap(rawBitmap, 0, 0, rawBitmap.width, rawBitmap.height, matrix, true)
                }

                val downscaledBitmap = ImageUtils.downscaleBitmap(rawBitmap, 1200)

                // 1. Ekstraksi Teks (OCR) via Google ML Kit
                val visionText = recognizeTextSuspend(downscaledBitmap)
                val detectedText = visionText?.text.orEmpty()
                val detectedNopol = PlateOcrUtils.extractIndonesianNopol(detectedText)

                // 2. Jika Nopol terdeteksi di foto (contoh: "b 9033 bek" -> "B 9033 BEK"), gunakan otomatis
                val effectiveNopol = if (!detectedNopol.isNullOrBlank()) {
                    withContext(Dispatchers.Main) {
                        binding.etQuickNopol.setText(detectedNopol)
                        sharedPreferences.edit().putString("key_nopol", detectedNopol).apply()
                        Toast.makeText(
                            this@NewOrderActivity,
                            "🎯 Plat Nopol otomatis terdeteksi: $detectedNopol",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                    detectedNopol
                } else {
                    val currentInputNopol = binding.etQuickNopol.text?.toString()?.trim().orEmpty()
                    if (currentInputNopol.isNotBlank()) currentInputNopol else sharedPreferences.getString("key_nopol", "B 1234 ABC") ?: "B 1234 ABC"
                }

                val pengurus = sharedPreferences.getString("key_pengurus_name", "Abedem") ?: "Abedem"

                withContext(Dispatchers.Main) {
                    binding.tvProcessingStatus.text = "Mencetak Watermark (Nopol: $effectiveNopol)..."
                }

                val timestamp = System.currentTimeMillis()
                val watermarkTag = if (currentCaptureTarget == CaptureTarget.GOODS) {
                    "abde.kurir - FOTO BARANG"
                } else {
                    "abde.kurir - SURAT JALAN"
                }

                val watermarkedBitmap = ImageUtils.addWatermark(
                    context = this@NewOrderActivity,
                    source = downscaledBitmap,
                    latitude = currentLatitude,
                    longitude = currentLongitude,
                    timestamp = timestamp,
                    address = currentAddress,
                    nopol = effectiveNopol,
                    pengurus = pengurus,
                    tag = watermarkTag
                )

                val prefix = if (currentCaptureTarget == CaptureTarget.GOODS) "barang" else "surat_jalan"
                val savedFilePath = ImageUtils.saveBitmapToInternalStorage(
                    this@NewOrderActivity,
                    watermarkedBitmap,
                    prefix
                )

                withContext(Dispatchers.Main) {
                    applyOcrAndPhotoResult(
                        bitmap = watermarkedBitmap,
                        savedFilePath = savedFilePath,
                        detectedText = detectedText,
                        blockCount = visionText?.textBlocks?.size ?: 0,
                        detectedNopol = detectedNopol,
                        isWaybill = (currentCaptureTarget == CaptureTarget.WAYBILL)
                    )
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    binding.layoutProcessing.visibility = View.GONE
                    Toast.makeText(this@NewOrderActivity, "Gagal memproses gambar: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private suspend fun recognizeTextSuspend(bitmap: Bitmap): Text? = suspendCancellableCoroutine { continuation ->
        try {
            val image = InputImage.fromBitmap(bitmap, 0)
            val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            recognizer.process(image)
                .addOnSuccessListener { visionText ->
                    if (continuation.isActive) continuation.resume(visionText)
                }
                .addOnFailureListener {
                    if (continuation.isActive) continuation.resume(null)
                }
        } catch (e: Exception) {
            if (continuation.isActive) continuation.resume(null)
        }
    }

    private fun applyOcrAndPhotoResult(
        bitmap: Bitmap,
        savedFilePath: String,
        detectedText: String,
        blockCount: Int,
        detectedNopol: String?,
        isWaybill: Boolean
    ) {
        if (isWaybill) {
            waybillPhotoPath = savedFilePath
            binding.ivWaybillPreview.setImageBitmap(bitmap)
            binding.tvWaybillStatus.text = "Foto & OCR Siap ✔"
            binding.tvWaybillStatus.setTextColor(ContextCompat.getColor(this@NewOrderActivity, R.color.status_online))

            binding.tvWaybillBadge.text = "DONE"
            binding.tvWaybillBadge.setBackgroundResource(R.drawable.bg_badge_green)
            binding.tvWaybillBadge.setTextColor(ContextCompat.getColor(this@NewOrderActivity, R.color.badge_green_text))

            if (detectedText.isNotBlank()) {
                val currentText = binding.etOcrResult.text?.toString() ?: ""
                val combinedText = if (currentText.isBlank() || currentText.startsWith("Surat Jalan")) detectedText else "$currentText\n[SURAT JALAN]:\n$detectedText"
                binding.etOcrResult.setText(combinedText)
                binding.tvOcrStatusBadge.text = "OCR Terdeteksi ($blockCount blok)"
                binding.tvOcrStatusBadge.setTextColor(ContextCompat.getColor(this@NewOrderActivity, R.color.primary_tonal_text))

                if (binding.etOrderName.text.isNullOrBlank()) {
                    val extractedNumber = extractWaybillNumber(detectedText)
                    binding.etOrderName.setText(extractedNumber)
                }
            } else {
                if (binding.etOcrResult.text.isNullOrBlank()) {
                    binding.etOcrResult.setText("Surat Jalan terfoto. Teks siap diverifikasi.")
                }
                binding.tvOcrStatusBadge.text = "Foto Tersimpan"
                if (binding.etOrderName.text.isNullOrBlank()) {
                    val autoId = "SJ-${SimpleDateFormat("ddMMyy-HHmm", Locale.US).format(Date())}"
                    binding.etOrderName.setText(autoId)
                }
            }
            closeCameraOverlay()
            val nopolMsg = if (detectedNopol != null) " (Nopol: $detectedNopol)" else ""
            Toast.makeText(this@NewOrderActivity, "Surat jalan otomatis terbaca & data siap$nopolMsg!", Toast.LENGTH_SHORT).show()
        } else {
            // Goods Photo
            goodsPhotoPath = savedFilePath
            binding.ivGoodsPreview.setImageBitmap(bitmap)
            binding.tvGoodsStatus.text = "Foto tersimpan ✔"
            binding.tvGoodsStatus.setTextColor(ContextCompat.getColor(this@NewOrderActivity, R.color.status_online))

            binding.tvGoodsBadge.text = "DONE"
            binding.tvGoodsBadge.setBackgroundResource(R.drawable.bg_badge_green)
            binding.tvGoodsBadge.setTextColor(ContextCompat.getColor(this@NewOrderActivity, R.color.badge_green_text))

            if (detectedText.isNotBlank()) {
                val currentText = binding.etOcrResult.text?.toString() ?: ""
                val combinedText = if (currentText.isBlank()) "[LABEL BARANG]:\n$detectedText" else "$currentText\n\n[LABEL BARANG]:\n$detectedText"
                binding.etOcrResult.setText(combinedText)

                if (binding.etOrderName.text.isNullOrBlank()) {
                    val extracted = extractWaybillNumber(detectedText)
                    binding.etOrderName.setText(extracted)
                }
            }

            closeCameraOverlay()
            val nopolMsg = if (detectedNopol != null) " (Nopol terdeteksi: $detectedNopol)" else ""
            Toast.makeText(this@NewOrderActivity, "Foto barang tersimpan & ber-watermark$nopolMsg!", Toast.LENGTH_SHORT).show()
        }
    }

    private fun extractWaybillNumber(fullText: String): String {
        // Look for patterns like "No: 12345", "SJ: AB-999", "DO/2026/...", "RESI: 888..."
        val regex = Regex("(?i)(?:NO(?:MOR)?|RESI|SJ|SURAT\\s*JALAN|DO|SPK|AWB|INV|ID)[:\\s.#-]*([A-Z0-9/\\-]{4,25})")
        val match = regex.find(fullText)
        if (match != null && match.groupValues.size > 1) {
            val candidate = match.groupValues[1].trim()
            if (candidate.isNotBlank()) return candidate
        }

        // Fallback: search for first line containing uppercase alphanumeric string with at least 5 chars
        val lines = fullText.lines()
        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.length in 5..30 && trimmed.any { it.isDigit() } && !trimmed.contains("http", ignoreCase = true)) {
                return trimmed
            }
        }

        return "SJ-${SimpleDateFormat("ddMMyy-HHmm", Locale.US).format(Date())}"
    }

    private fun submitOrderToDatabase() {
        var orderName = binding.etOrderName.text?.toString()?.trim() ?: ""

        if (orderName.isBlank()) {
            orderName = "SJ-${SimpleDateFormat("ddMMyy-HHmmss", Locale.US).format(Date())}"
            binding.etOrderName.setText(orderName)
        }

        val goodsUri = goodsPhotoPath ?: ""
        val waybillUri = waybillPhotoPath ?: ""
        val ocrText = binding.etOcrResult.text?.toString()?.trim() ?: ""
        val nopol = binding.etQuickNopol.text?.toString()?.trim() ?: ""

        if (goodsUri.isBlank() && waybillUri.isBlank()) {
            Snackbar.make(
                binding.root,
                "Silakan ambil minimal salah satu foto (Foto Barang atau Surat Jalan)",
                Snackbar.LENGTH_LONG
            ).show()
            return
        }

        if (nopol.isNotBlank()) {
            sharedPreferences.edit().putString("key_nopol", nopol).apply()
        }

        if (editOrderId != -1L) {
            val updatedOrder = (editingOrder ?: OrderEntity(
                id = editOrderId,
                orderName = orderName,
                goodsPhotoUri = goodsUri,
                waybillPhotoUri = waybillUri,
                ocrResultText = ocrText,
                latitude = currentLatitude,
                longitude = currentLongitude,
                address = currentAddress,
                nopol = nopol,
                timestamp = selectedDeliveryTimestamp
            )).copy(
                orderName = orderName,
                goodsPhotoUri = goodsUri,
                waybillPhotoUri = waybillUri,
                ocrResultText = ocrText,
                latitude = if (currentLatitude != 0.0) currentLatitude else (editingOrder?.latitude ?: 0.0),
                longitude = if (currentLongitude != 0.0) currentLongitude else (editingOrder?.longitude ?: 0.0),
                address = if (currentAddress.isNotBlank()) currentAddress else (editingOrder?.address ?: ""),
                nopol = nopol,
                timestamp = selectedDeliveryTimestamp
            )

            lifecycleScope.launch(Dispatchers.IO) {
                database.orderDao().updateOrder(updatedOrder)
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@NewOrderActivity,
                        "Perubahan data & foto order '$orderName' berhasil disimpan!",
                        Toast.LENGTH_SHORT
                    ).show()
                    finish()
                }
            }
        } else {
            val newOrder = OrderEntity(
                orderName = orderName,
                goodsPhotoUri = goodsUri,
                waybillPhotoUri = waybillUri,
                ocrResultText = ocrText,
                latitude = currentLatitude,
                longitude = currentLongitude,
                address = currentAddress,
                nopol = nopol,
                timestamp = selectedDeliveryTimestamp
            )

            lifecycleScope.launch(Dispatchers.IO) {
                database.orderDao().insertOrder(newOrder)
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@NewOrderActivity,
                        "Order '$orderName' berhasil disimpan dan langsung masuk ke riwayat!",
                        Toast.LENGTH_SHORT
                    ).show()
                    finish()
                }
            }
        }
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
}
