package com.example.datn_caregiver

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.widget.ImageViewCompat
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * RecyclerView Adapter cho danh sách cảnh báo té ngã (tab Cảnh Báo).
 *
 * Mỗi item hiển thị:
 *  • Viền trái màu đỏ (Falling) hoặc cam (Lying)
 *  • Biểu tượng người ngã với màu tương ứng từng trạng thái
 *  • Trạng thái: "Phát hiện té ngã" hoặc "Phát hiện tư thế nằm"
 *  • Badge xác suất + Thời gian phát hiện
 */
class FallAlertAdapter(
    private var items: List<FallAlertEntry>
) : RecyclerView.Adapter<FallAlertAdapter.VH>() {

    private val sdf = SimpleDateFormat("dd/MM/yyyy  HH:mm", Locale.forLanguageTag("vi-VN"))

    inner class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val viewBar     : View     = itemView.findViewById(R.id.viewAlertBar)
        val iconFrame   : FrameLayout = itemView.findViewById(R.id.frameAlertIcon)
        val icon        : ImageView = itemView.findViewById(R.id.ivAlertIcon)
        val tvState     : TextView = itemView.findViewById(R.id.tvAlertState)
        val tvProb      : TextView = itemView.findViewById(R.id.tvAlertProb)
        val tvTime      : TextView = itemView.findViewById(R.id.tvAlertTime)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_fall_alert, parent, false)
        return VH(v)
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]

        val isLying = item.state == "Lying"

        // Màu bar trái: đỏ (Falling) hoặc cam (Lying)
        val barColor = if (isLying) Color.parseColor("#FFFFA500") else Color.parseColor("#FFEE3333")
        holder.viewBar.setBackgroundColor(barColor)

        // Label trạng thái
        holder.tvState.text = if (isLying) "Phát hiện tư thế nằm" else "Phát hiện té ngã"
        holder.tvState.setTextColor(if (isLying) Color.parseColor("#B54708") else Color.parseColor("#B4232C"))
        holder.iconFrame.setBackgroundResource(
            if (isLying) R.drawable.bg_lying_alert_icon else R.drawable.bg_fall_alert_icon
        )
        ImageViewCompat.setImageTintList(
            holder.icon,
            ContextCompat.getColorStateList(
                holder.itemView.context,
                if (isLying) R.color.fall_alert_lying else R.color.fall_alert_falling
            )
        )

        // Badge xác suất
        val pct = String.format("%.0f%%", item.probFall * 100)
        holder.tvProb.text = pct
        holder.tvProb.setBackgroundResource(
            if (isLying) R.drawable.ic_status_badge_lying else R.drawable.ic_status_badge_fall
        )

        // Thời gian
        holder.tvTime.text = sdf.format(Date(item.timestamp))
    }

    fun updateList(newItems: List<FallAlertEntry>) {
        items = newItems
        notifyDataSetChanged()
    }
}
