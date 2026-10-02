package com.example.datn_caregiver

import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.example.datn_caregiver.databinding.FragmentAddScheduleBinding
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class AddScheduleFragment : Fragment(R.layout.fragment_add_schedule) {

    private var _binding: FragmentAddScheduleBinding? = null
    private val binding get() = _binding!!

    private val auth by lazy { FirebaseAuth.getInstance() }
    private val database by lazy {
        FirebaseDatabase.getInstance().getReference("Schedules")
    }

    // Ngày đang được chọn (millis)
    private var selectedDateMillis: Long = System.currentTimeMillis()
    private var selectedRepeatMode: String = "none"

    // Item đang sửa (null = chế độ thêm mới)
    private var editItem: ScheduleItem? = null

    companion object {
        private const val ARG_SCHEDULE_ITEM = "arg_schedule_item"

        /** Tạo fragment ở chế độ CHỈNH SỬA với dữ liệu item sẵn có */
        fun newInstance(item: ScheduleItem): AddScheduleFragment {
            val fragment = AddScheduleFragment()
            val bundle = Bundle().apply {
                putString(ARG_SCHEDULE_ITEM,
                    "${item.scheduleId}||${item.title}||${item.note}||" +
                    "${item.dateMillis}||${item.hour}||${item.minute}||" +
                    "${item.repeatMode}||${item.createdAt}||${item.active}")
            }
            fragment.arguments = bundle
            return fragment
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentAddScheduleBinding.bind(view)

        // Đọc item từ arguments nếu có (chế độ sửa)
        arguments?.getString(ARG_SCHEDULE_ITEM)?.let { raw ->
            val parts = raw.split("||")
            if (parts.size == 9) {
                editItem = ScheduleItem(
                    scheduleId = parts[0],
                    title      = parts[1],
                    note       = parts[2],
                    dateMillis = parts[3].toLongOrNull() ?: System.currentTimeMillis(),
                    hour       = parts[4].toIntOrNull() ?: 8,
                    minute     = parts[5].toIntOrNull() ?: 0,
                    repeatMode = parts[6],
                    createdAt  = parts[7].toLongOrNull() ?: System.currentTimeMillis(),
                    active     = parts[8].toBooleanStrictOrNull() ?: true
                )
            }
        }

        // Cập nhật header title theo chế độ
        updateHeaderTitle()

        // Pre-fill nếu đang ở chế độ sửa
        editItem?.let { item ->
            selectedDateMillis = item.dateMillis
            selectedRepeatMode = item.repeatMode
        } ?: run {
            selectedDateMillis = System.currentTimeMillis()
        }

        // Đặt ngày trên CalendarView
        binding.calendarView.date = selectedDateMillis
        updateDateLabel(selectedDateMillis)
        updateTimeDisplay()

        setupCalendar()
        setupTimePicker()
        setupRepeatChips()
        setupButtons()

        // Pre-fill các trường văn bản nếu đang sửa
        editItem?.let { item ->
            binding.edtScheduleTitle.setText(item.title)
            binding.edtScheduleNote.setText(item.note)
        }
    }

    // ── Cập nhật tiêu đề header ──────────────────────────────────

    private fun updateHeaderTitle() {
        // Tìm TextView tiêu đề trong header (index cố định trong layout)
        // Layout: ScrollView > LinearLayout > LinearLayout (header) > LinearLayout > TextView[1]
        // Dùng view tag để tránh dùng id trùng — layout đã có id btnBack và tvSelectedDate
        // Cách đơn giản: đặt title qua binding nếu có id, ở đây dùng cách tìm lại
        // (Layout hiện tại không có id cho title header → ta sửa bằng cách set text trực tiếp nếu cần)
        // Không cần thêm id vì header title đủ mô tả; chỉ update nút Lưu text
        if (editItem != null) {
            binding.btnSaveSchedule.text = "Lưu thay đổi"
        } else {
            binding.btnSaveSchedule.text = "Lưu lịch nhắc"
        }
    }

    // ── CalendarView ──────────────────────────────────────────────

    private fun setupCalendar() {
        // Khi sửa, cho phép chọn lại ngày cũ (kể cả quá khứ một chút)
        if (editItem == null) {
            binding.calendarView.minDate = System.currentTimeMillis() - 1000
        }

        binding.calendarView.setOnDateChangeListener { _, year, month, dayOfMonth ->
            val cal = Calendar.getInstance().apply {
                set(year, month, dayOfMonth, 0, 0, 0)
                set(Calendar.MILLISECOND, 0)
            }
            selectedDateMillis = cal.timeInMillis
            updateDateLabel(selectedDateMillis)
        }
    }

    private fun updateDateLabel(millis: Long) {
        val fmt = SimpleDateFormat("EEEE, dd/MM/yyyy", Locale.forLanguageTag("vi-VN"))
        val cal = Calendar.getInstance().apply { timeInMillis = millis }
        binding.tvSelectedDate.text = fmt.format(cal.time)
    }

    // ── TimePicker ────────────────────────────────────────────────

    private fun setupTimePicker() {
        binding.timePicker.setIs24HourView(true)

        // Xác định giờ/phút ban đầu
        val initHour   = editItem?.hour   ?: 8
        val initMinute = editItem?.minute ?: 0

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            binding.timePicker.hour   = initHour
            binding.timePicker.minute = initMinute
        } else {
            @Suppress("DEPRECATION")
            binding.timePicker.currentHour   = initHour
            @Suppress("DEPRECATION")
            binding.timePicker.currentMinute = initMinute
        }

        binding.tvTimeDisplay.text = String.format("%02d:%02d", initHour, initMinute)

        binding.timePicker.setOnTimeChangedListener { _, hour, minute ->
            binding.tvTimeDisplay.text = String.format("%02d:%02d", hour, minute)
        }
    }

    // ── ChipGroup lặp lại ─────────────────────────────────────────

    private fun setupRepeatChips() {
        // Pre-select chip theo repeatMode của item đang sửa
        when (selectedRepeatMode) {
            "daily"   -> binding.chipGroupRepeat.check(R.id.chipRepeatDaily)
            "weekly"  -> binding.chipGroupRepeat.check(R.id.chipRepeatWeekly)
            "monthly" -> binding.chipGroupRepeat.check(R.id.chipRepeatMonthly)
            else      -> binding.chipGroupRepeat.check(R.id.chipRepeatNone)
        }

        binding.chipGroupRepeat.setOnCheckedStateChangeListener { _, checkedIds ->
            selectedRepeatMode = when {
                checkedIds.contains(R.id.chipRepeatDaily)   -> "daily"
                checkedIds.contains(R.id.chipRepeatWeekly)  -> "weekly"
                checkedIds.contains(R.id.chipRepeatMonthly) -> "monthly"
                else                                        -> "none"
            }
        }
    }

    // ── Nút bấm ───────────────────────────────────────────────────

    private fun setupButtons() {
        binding.btnBack.setOnClickListener {
            parentFragmentManager.popBackStack()
        }

        binding.btnSaveSchedule.setOnClickListener {
            if (editItem != null) updateSchedule() else saveSchedule()
        }
    }

    // ── Lưu mới Firebase ─────────────────────────────────────────

    private fun saveSchedule() {
        val title = binding.edtScheduleTitle.text.toString().trim()
        if (title.isBlank()) {
            binding.tilScheduleTitle.error = "Vui lòng nhập tiêu đề nhắc nhở"
            return
        }
        binding.tilScheduleTitle.error = null

        val note = binding.edtScheduleNote.text.toString().trim()
        val (hour, minute) = readTimePicker()

        val uid = auth.currentUser?.uid
        if (uid == null) {
            Toast.makeText(requireContext(), "Bạn chưa đăng nhập", Toast.LENGTH_SHORT).show()
            return
        }

        val ref = database.child(uid).push()
        val scheduleId = ref.key ?: return

        val newItem = ScheduleItem(
            scheduleId  = scheduleId,
            title       = title,
            note        = note,
            dateMillis  = selectedDateMillis,
            hour        = hour,
            minute      = minute,
            repeatMode  = selectedRepeatMode,
            createdAt   = System.currentTimeMillis(),
            active      = true
        )

        binding.btnSaveSchedule.isEnabled = false

        ref.setValue(newItem)
            .addOnSuccessListener {
                val b = _binding ?: return@addOnSuccessListener
                b.btnSaveSchedule.isEnabled = true
                if (isAdded) {
                    Toast.makeText(requireContext(), "Đã lưu lịch nhắc!", Toast.LENGTH_SHORT).show()
                    parentFragmentManager.popBackStack()
                }
            }
            .addOnFailureListener { e ->
                val b = _binding ?: return@addOnFailureListener
                b.btnSaveSchedule.isEnabled = true
                if (isAdded) {
                    Toast.makeText(requireContext(), "Lỗi lưu: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
    }

    // ── Cập nhật lịch đang sửa ───────────────────────────────────

    private fun updateSchedule() {
        val item = editItem ?: return

        val title = binding.edtScheduleTitle.text.toString().trim()
        if (title.isBlank()) {
            binding.tilScheduleTitle.error = "Vui lòng nhập tiêu đề nhắc nhở"
            return
        }
        binding.tilScheduleTitle.error = null

        val note = binding.edtScheduleNote.text.toString().trim()
        val (hour, minute) = readTimePicker()

        val uid = auth.currentUser?.uid
        if (uid == null) {
            Toast.makeText(requireContext(), "Bạn chưa đăng nhập", Toast.LENGTH_SHORT).show()
            return
        }

        val updatedItem = item.copy(
            title      = title,
            note       = note,
            dateMillis = selectedDateMillis,
            hour       = hour,
            minute     = minute,
            repeatMode = selectedRepeatMode
        )

        binding.btnSaveSchedule.isEnabled = false

        database.child(uid).child(item.scheduleId).setValue(updatedItem)
            .addOnSuccessListener {
                val b = _binding ?: return@addOnSuccessListener
                b.btnSaveSchedule.isEnabled = true
                if (isAdded) {
                    Toast.makeText(requireContext(), "Đã cập nhật lịch nhắc!", Toast.LENGTH_SHORT).show()
                    parentFragmentManager.popBackStack()
                }
            }
            .addOnFailureListener { e ->
                val b = _binding ?: return@addOnFailureListener
                b.btnSaveSchedule.isEnabled = true
                if (isAdded) {
                    Toast.makeText(requireContext(), "Lỗi cập nhật: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
    }

    // ── Đọc giờ từ TimePicker ─────────────────────────────────────

    private fun readTimePicker(): Pair<Int, Int> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Pair(binding.timePicker.hour, binding.timePicker.minute)
        } else {
            @Suppress("DEPRECATION")
            Pair(binding.timePicker.currentHour, binding.timePicker.currentMinute)
        }
    }

    private fun updateTimeDisplay() {
        val h = editItem?.hour   ?: 8
        val m = editItem?.minute ?: 0
        binding.tvTimeDisplay.text = String.format("%02d:%02d", h, m)
    }

    // ── Lifecycle ─────────────────────────────────────────────────

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
