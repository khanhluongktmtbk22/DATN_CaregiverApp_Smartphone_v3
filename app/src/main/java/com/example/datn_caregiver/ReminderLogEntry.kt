package com.example.datn_caregiver

/**
 * Bản ghi log nhắc nhở lưu trên Firebase.
 * Node: /reminder_logs/{uid}/{logId}
 *
 *  status:
 *    "delivered"  – Robot đã phát lời nhắc
 *    "confirmed"  – Người già xác nhận đã nghe
 *    "retrying"   – Đang chờ retry
 *    "failed"     – Hết retry, không xác nhận
 */
data class ReminderLogEntry(
    val logId             : String = "",
    val scheduleId        : String = "",
    val title             : String = "",
    val note              : String = "",
    val triggeredAt       : Long   = 0L,
    val status            : String = "delivered",
    val elderlyResponse   : String = "",
    val retryCount        : Int    = 0,
    val confirmedAt       : Long   = 0L,
    val caregiverName     : String = "",
    val caregiverRelation : String = ""
)
