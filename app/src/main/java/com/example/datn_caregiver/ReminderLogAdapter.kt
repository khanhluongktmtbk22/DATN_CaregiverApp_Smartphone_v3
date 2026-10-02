package com.example.datn_caregiver

import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.example.datn_caregiver.databinding.ItemReminderLogBinding
import java.text.SimpleDateFormat
import java.util.*

class ReminderLogAdapter(
    private val items: MutableList<ReminderLogEntry> = mutableListOf()
) : RecyclerView.Adapter<ReminderLogAdapter.VH>() {

    inner class VH(val b: ItemReminderLogBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val b = ItemReminderLogBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(b)
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val entry = items[position]
        val b = holder.b

        b.tvReminderTitle.text = entry.title

        // Noi dung ghi chu
        b.tvReminderNote.text = if (entry.note.isNotBlank()) entry.note else "(Không có ghi chú)"

        // Thoi gian
        val sdf = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())
        b.tvReminderTime.text = sdf.format(Date(entry.triggeredAt))

        // Retry count
        b.tvRetryCount.text = if (entry.retryCount > 0) "Retry: ${entry.retryCount}" else ""

        // Phan hoi cua nguoi gia
        if (entry.elderlyResponse.isNotBlank()) {
            b.tvElderlyResponse.text = "Phản hồi: \"${entry.elderlyResponse}\""
            b.tvElderlyResponse.visibility = View.VISIBLE
        } else {
            b.tvElderlyResponse.visibility = View.GONE
        }

        // Badge trang thai
        val (badgeText, badgeColor) = when (entry.status) {
            "confirmed"  -> "Đã xác nhận" to 0xFF4CAF50.toInt()   // Xanh la
            "delivered"  -> "Đã phát"     to 0xFF2196F3.toInt()   // Xanh duong
            "retrying"   -> "Đang thử lại" to 0xFFFF9800.toInt()  // Cam
            "failed"     -> "Thất bại"    to 0xFFF44336.toInt()   // Do
            else         -> entry.status  to 0xFF9E9E9E.toInt()   // Xam
        }
        b.tvReminderStatus.text = badgeText
        val drawable = b.tvReminderStatus.background as? GradientDrawable
            ?: GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 40f
            }
        drawable.setColor(badgeColor)
        b.tvReminderStatus.background = drawable
    }

    fun updateList(newItems: List<ReminderLogEntry>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }
}
