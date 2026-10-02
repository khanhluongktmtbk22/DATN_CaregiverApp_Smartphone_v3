package com.example.datn_caregiver

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.switchmaterial.SwitchMaterial
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class ScheduleAdapter(
    private var items: MutableList<ScheduleItem>,
    private val onToggle: (ScheduleItem, Boolean) -> Unit,
    private val onDelete: (ScheduleItem) -> Unit,
    private val onEdit: (ScheduleItem) -> Unit
) : RecyclerView.Adapter<ScheduleAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvTitle: TextView = view.findViewById(R.id.tvScheduleTitle)
        val tvDateTime: TextView = view.findViewById(R.id.tvScheduleDateTime)
        val tvNote: TextView = view.findViewById(R.id.tvScheduleNote)
        val tvRepeat: TextView = view.findViewById(R.id.tvRepeatMode)
        val switchActive: SwitchMaterial = view.findViewById(R.id.switchScheduleActive)
        val btnDelete: ImageView = view.findViewById(R.id.btnDeleteSchedule)
        val btnEdit: ImageView = view.findViewById(R.id.btnEditSchedule)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_schedule, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]

        // Tiêu đề
        holder.tvTitle.text = item.title.ifBlank { "(Không có tiêu đề)" }

        // Ngày giờ
        val cal = Calendar.getInstance().apply { timeInMillis = item.dateMillis }
        val dayOfWeek = when (cal.get(Calendar.DAY_OF_WEEK)) {
            Calendar.MONDAY    -> "Thứ Hai"
            Calendar.TUESDAY   -> "Thứ Ba"
            Calendar.WEDNESDAY -> "Thứ Tư"
            Calendar.THURSDAY  -> "Thứ Năm"
            Calendar.FRIDAY    -> "Thứ Sáu"
            Calendar.SATURDAY  -> "Thứ Bảy"
            else               -> "Chủ Nhật"
        }
        val dateFmt = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
        val timeStr = String.format("%02d:%02d", item.hour, item.minute)
        holder.tvDateTime.text = "$timeStr  ·  $dayOfWeek, ${dateFmt.format(cal.time)}"

        // Ghi chú
        if (item.note.isBlank()) {
            holder.tvNote.visibility = View.GONE
        } else {
            holder.tvNote.visibility = View.VISIBLE
            holder.tvNote.text = item.note
        }

        // Chế độ lặp
        val repeatLabel = when (item.repeatMode) {
            "daily"   -> "Hàng ngày"
            "weekly"  -> "Hàng tuần"
            "monthly" -> "Hàng tháng"
            else      -> "Một lần"
        }
        holder.tvRepeat.text = repeatLabel
        val iconRes = if (item.repeatMode == "none") R.drawable.ic_one_time_24 else R.drawable.ic_repeat_24
        holder.tvRepeat.setCompoundDrawablesWithIntrinsicBounds(iconRes, 0, 0, 0)

        // Alpha khi tắt
        holder.itemView.alpha = if (item.active) 1.0f else 0.55f

        // Switch — tắt listener trước khi set để tránh vòng lặp
        holder.switchActive.setOnCheckedChangeListener(null)
        holder.switchActive.isChecked = item.active
        holder.switchActive.setOnCheckedChangeListener { _, isChecked ->
            onToggle(item, isChecked)
        }

        // Nút xóa
        holder.btnDelete.setOnClickListener { onDelete(item) }

        // Nút sửa
        holder.btnEdit.setOnClickListener { onEdit(item) }
    }

    override fun getItemCount(): Int = items.size

    /** Cập nhật danh sách với DiffUtil */
    fun updateList(newItems: List<ScheduleItem>) {
        val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize() = items.size
            override fun getNewListSize() = newItems.size
            override fun areItemsTheSame(o: Int, n: Int) =
                items[o].scheduleId == newItems[n].scheduleId
            override fun areContentsTheSame(o: Int, n: Int) =
                items[o] == newItems[n]
        })
        items.clear()
        items.addAll(newItems)
        diff.dispatchUpdatesTo(this)
    }
}
