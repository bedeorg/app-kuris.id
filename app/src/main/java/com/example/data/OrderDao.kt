package com.example.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface OrderDao {
    @Query("SELECT * FROM orders ORDER BY timestamp DESC")
    fun getAllOrders(): Flow<List<OrderEntity>>

    @Query("SELECT * FROM orders WHERE timestamp >= :startTimestamp AND timestamp <= :endTimestamp ORDER BY timestamp DESC")
    fun getOrdersByDateRange(startTimestamp: Long, endTimestamp: Long): Flow<List<OrderEntity>>

    @Query("SELECT * FROM orders WHERE orderName LIKE '%' || :query || '%' OR ocrResultText LIKE '%' || :query || '%' OR nopol LIKE '%' || :query || '%' OR address LIKE '%' || :query || '%' ORDER BY timestamp DESC")
    fun searchOrders(query: String): Flow<List<OrderEntity>>

    @Query("SELECT * FROM orders WHERE (timestamp >= :startTimestamp AND timestamp <= :endTimestamp) AND (orderName LIKE '%' || :query || '%' OR ocrResultText LIKE '%' || :query || '%' OR nopol LIKE '%' || :query || '%' OR address LIKE '%' || :query || '%') ORDER BY timestamp DESC")
    fun searchOrdersWithDateRange(query: String, startTimestamp: Long, endTimestamp: Long): Flow<List<OrderEntity>>

    @Query("SELECT * FROM orders ORDER BY timestamp DESC")
    suspend fun getAllOrdersList(): List<OrderEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrder(order: OrderEntity): Long

    @Delete
    suspend fun deleteOrder(order: OrderEntity)

    @Query("SELECT * FROM orders WHERE id = :id LIMIT 1")
    suspend fun getOrderById(id: Long): OrderEntity?
}
