package com.example.datn_caregiver

/**
 * Một bản ghi lịch sử cuộc gọi video.
 *
 * Firebase node: /call_history/{entryId}
 *   timestamp   : Unix ms, dùng để sort mới nhất lên đầu
 *   initiator   : "caregiver" | "robot"
 *   durationSec : thời lượng cuộc gọi tính bằng giây (0 nếu missed/rejected)
 *   status      : "ended" | "missed" | "rejected"
 */
data class CallHistoryEntry(
    val entryId     : String = "",
    val timestamp   : Long   = 0L,
    val initiator   : String = "caregiver",   // "caregiver" | "robot"
    val durationSec : Long   = 0L,
    val status      : String = "ended"        // "ended" | "missed" | "rejected"
)
