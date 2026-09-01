package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "orders")
data class OrderEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val orderName: String,
    val goodsPhotoUri: String,
    val waybillPhotoUri: String,
    val ocrResultText: String,
    val latitude: Double,
    val longitude: Double,
    val address: String = "",
    val nopol: String = "",
    val timestamp: Long = System.currentTimeMillis()
)
