package com.example.datn_caregiver

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.cardview.widget.CardView
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * RecyclerView Adapter cho danh sách lịch sử cuộc gọi (tab Cuộc Gọi).
 *
 * Mỗi item hiển thị:
 *  • Icon điện thoại (màu xanh)
 *  • Người gọi: "Caregiver gọi đến Robot" hoặc "Robot gọi đến"
 *  • Thời gian diễn ra cuộc gọi
 *  • Thời lượng (định dạng mm:ss) + Badge trạng thái
 */
class CallHistoryAdapter(
    private var items: List<CallHistoryEntry>
) : RecyclerView.Adapter<CallHistoryAdapter.VH>() {

    private val sdf = SimpleDateFormat("dd/MM/yyyy  HH:mm", Locale.forLanguageTag("vi-VN"))

    inner class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val tvInitiator : TextView = itemView.findViewById(R.id.tvCallInitiator)
        val tvTime      : TextView = itemView.findViewById(R.id.tvCallTime)
        val tvDuration  : TextView = itemView.findViewById(R.id.tvCallDuration)
        val tvStatus    : TextView = itemView.findViewById(R.id.tvCallStatus)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_call_history, parent, false)
        return VH(v)
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]

        // Người gọi
        holder.tvInitiator.text = when (item.initiator) {
            "robot"     -> "Robot gọi đến"
            else        -> "Caregiver gọi đến Robot"
        }

        // Thời gian
        holder.tvTime.text = sdf.format(Date(item.timestamp))

        // Thời lượng
        holder.tvDuration.text = if (item.durationSec > 0) {
            val m = item.durationSec / 60
            val s = item.durationSec % 60
            String.format("%02d:%02d", m, s)
        } else {
            "--:--"
        }

        // Badge trạng thái
        val (statusText, statusBgRes) = when (item.status) {
            "missed"   -> Pair("Nhỡ",     R.drawable.ic_status_badge_missed)
            "rejected" -> Pair("Từ chối", R.drawable.ic_status_badge_rejected)
            else       -> Pair("Kết thúc",  R.drawable.ic_status_badge_ended)
        }
        holder.tvStatus.text = statusText
        holder.tvStatus.setBackgroundResource(statusBgRes)
    }

    fun updateList(newItems: List<CallHistoryEntry>) {
        items = newItems
        notifyDataSetChanged()
    }
}
