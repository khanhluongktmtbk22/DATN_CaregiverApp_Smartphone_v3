package com.example.datn_caregiver

data class ScheduleItem(
    val scheduleId: String = "",
    val title: String = "",
    val note: String = "",
    val dateMillis: Long = 0L,
    val hour: Int = 8,
    val minute: Int = 0,
    val repeatMode: String = "none", // "none" | "daily" | "weekly" | "monthly"
    val createdAt: Long = 0L,
    val active: Boolean = true
)
