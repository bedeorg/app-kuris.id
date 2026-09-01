package com.example.ui

import android.graphics.BitmapFactory
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.R
import com.example.data.OrderEntity
import com.example.databinding.ItemSavedOrderBinding
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class OrderAdapter(
    private val onItemClick: (OrderEntity) -> Unit,
    private val onDeleteClick: (OrderEntity) -> Unit
) : ListAdapter<OrderEntity, OrderAdapter.OrderViewHolder>(OrderDiffCallback()) {

    private val dateFormat = SimpleDateFormat("dd MMM yyyy, HH:mm 'WIB'", Locale("id", "ID"))

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): OrderViewHolder {
        val binding = ItemSavedOrderBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return OrderViewHolder(binding)
    }

    override fun onBindViewHolder(holder: OrderViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class OrderViewHolder(
        private val binding: ItemSavedOrderBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: OrderEntity) {
            binding.tvItemOrderName.text = item.orderName
            binding.tvItemTimestamp.text = dateFormat.format(Date(item.timestamp))

            // Nopol
            if (item.nopol.isNotBlank()) {
                binding.tvItemNopol.text = item.nopol.uppercase()
                binding.tvItemNopol.visibility = View.VISIBLE
            } else {
                binding.tvItemNopol.visibility = View.GONE
            }

            // Address
            if (item.address.isNotBlank()) {
                binding.layoutItemAddress.visibility = View.VISIBLE
                binding.tvItemAddress.text = item.address
            } else {
                binding.layoutItemAddress.visibility = View.GONE
            }

            if (item.latitude != 0.0 || item.longitude != 0.0) {
                binding.tvItemCoordinates.text = String.format(
                    Locale.US,
                    "GPS: %.5f, %.5f",
                    item.latitude,
                    item.longitude
                )
            } else {
                binding.tvItemCoordinates.text = "GPS: Lokasi tidak tersedia"
            }

            if (item.ocrResultText.isNotBlank()) {
                binding.layoutItemOcr.visibility = View.VISIBLE
                binding.tvItemOcrText.text = item.ocrResultText.trim()
            } else {
                binding.layoutItemOcr.visibility = View.GONE
            }

            // Load thumbnails from internal storage
            loadThumbnail(item.goodsPhotoUri, binding.ivItemGoods)
            loadThumbnail(item.waybillPhotoUri, binding.ivItemWaybill)

            binding.btnDeleteItem.setOnClickListener {
                onDeleteClick(item)
            }

            binding.root.setOnClickListener {
                onItemClick(item)
            }
        }

        private fun loadThumbnail(path: String, imageView: com.google.android.material.imageview.ShapeableImageView) {
            if (path.isNotBlank() && File(path).exists()) {
                try {
                    val options = BitmapFactory.Options().apply {
                        inSampleSize = 4 // Downsample for thumbnail
                    }
                    val bitmap = BitmapFactory.decodeFile(path, options)
                    if (bitmap != null) {
                        imageView.setImageBitmap(bitmap)
                    } else {
                        imageView.setImageResource(R.drawable.ic_camera)
                    }
                } catch (e: Exception) {
                    imageView.setImageResource(R.drawable.ic_camera)
                }
            } else {
                imageView.setImageResource(R.drawable.ic_camera)
            }
        }
    }

    class OrderDiffCallback : DiffUtil.ItemCallback<OrderEntity>() {
        override fun areItemsTheSame(oldItem: OrderEntity, newItem: OrderEntity): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: OrderEntity, newItem: OrderEntity): Boolean {
            return oldItem == newItem
        }
    }
}
