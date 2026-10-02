package com.example.datn_caregiver

/**
 * Một bản ghi cảnh báo té ngã do Robot App phát hiện và gửi lên.
 *
 * Firebase node: /fall_alerts/{entryId}
 *   timestamp          : Unix ms
 *   state              : "Falling" | "Lying"
 *   trackId            : ID người bị phát hiện ngã (từ SORT Tracker)
 *   probFall           : xác suất té ngã [0.0 – 1.0]
 *   confirmationStatus : "pending" | "confirmed_safe" | "confirmed_danger" | "no_response"
 *   elderlyResponse    : câu trả lời nguyên bản của người già
 *   llmClassification  : 0=an toàn, 1=nguy hiểm, -1=chưa xử lý
 *   confirmedAt        : thời điểm xác nhận (Unix ms)
 *   severity           : "low" | "medium" | "high" | "critical"
 */
data class FallAlertEntry(
    val entryId            : String = "",
    val timestamp          : Long   = 0L,
    val state              : String = "Falling",
    val trackId            : Int    = -1,
    val probFall           : Float  = 0f,
    val confirmationStatus : String = "pending",
    val elderlyResponse    : String = "",
    val llmClassification  : Int    = -1,
    val confirmedAt        : Long   = 0L,
    val severity           : String = "medium"
)
