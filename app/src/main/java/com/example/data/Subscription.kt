package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "subscriptions")
data class Subscription(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val name: String,
    val amount: Double,
    val nextDueDateMillis: Long,
    val isAutoRenew: Boolean = true,
    val category: String = "Bill"
)
