package com.example.datn_caregiver

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.datn_caregiver.databinding.FragmentScheduleBinding
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener

class ScheduleFragment : Fragment(R.layout.fragment_schedule) {

    private var _binding: FragmentScheduleBinding? = null
    private val binding get() = _binding!!

    private lateinit var adapter: ScheduleAdapter
    private val allSchedules = mutableListOf<ScheduleItem>()
    private var currentFilter = "all" // "all" | "active" | "inactive"

    private val auth by lazy { FirebaseAuth.getInstance() }
    private val database by lazy {
        FirebaseDatabase.getInstance().getReference("Schedules")
    }
    private var listener: ValueEventListener? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentScheduleBinding.bind(view)

        setupRecyclerView()
        setupFilterChips()
        setupFab()
        loadSchedules()
    }

    // ── RecyclerView ──────────────────────────────────────────────

    private fun setupRecyclerView() {
        adapter = ScheduleAdapter(
            mutableListOf(),
            onToggle = { item, isChecked -> toggleSchedule(item, isChecked) },
            onDelete = { item -> confirmDelete(item) },
            onEdit   = { item -> openEditScreen(item) }
        )
        binding.rvSchedules.layoutManager = LinearLayoutManager(requireContext())
        binding.rvSchedules.adapter = adapter
    }

    // ── Filter chips ──────────────────────────────────────────────

    private fun setupFilterChips() {
        binding.chipAll.setOnCheckedChangeListener { _, checked ->
            if (checked) { currentFilter = "all"; applyFilter() }
        }
        binding.chipActive.setOnCheckedChangeListener { _, checked ->
            if (checked) { currentFilter = "active"; applyFilter() }
        }
        binding.chipInactive.setOnCheckedChangeListener { _, checked ->
            if (checked) { currentFilter = "inactive"; applyFilter() }
        }
    }

    private fun applyFilter() {
        val filtered = when (currentFilter) {
            "active"   -> allSchedules.filter { it.active }
            "inactive" -> allSchedules.filter { !it.active }
            else       -> allSchedules.toList()
        }
        adapter.updateList(filtered)
        updateEmptyState(filtered.isEmpty())
    }

    private fun updateEmptyState(isEmpty: Boolean) {
        binding.layoutEmpty.visibility  = if (isEmpty) View.VISIBLE else View.GONE
        binding.rvSchedules.visibility  = if (isEmpty) View.GONE   else View.VISIBLE
    }

    // ── FAB ───────────────────────────────────────────────────────

    private fun setupFab() {
        binding.fabAddSchedule.setOnClickListener {
            parentFragmentManager.beginTransaction()
                .replace(R.id.content_frame, AddScheduleFragment())
                .addToBackStack(null)
                .commit()
        }
    }

    // ── Firebase load ─────────────────────────────────────────────

    private fun loadSchedules() {
        val uid = auth.currentUser?.uid ?: return
        listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val b = _binding ?: return
                allSchedules.clear()
                for (child in snapshot.children) {
                    child.getValue(ScheduleItem::class.java)?.let { allSchedules.add(it) }
                }
                // Sắp xếp theo ngày + giờ tăng dần
                allSchedules.sortWith(compareBy({ it.dateMillis }, { it.hour }, { it.minute }))
                applyFilter()
            }

            override fun onCancelled(error: DatabaseError) {
                if (isAdded) {
                    Toast.makeText(requireContext(), "Lỗi tải lịch: ${error.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
        database.child(uid).addValueEventListener(listener!!)
    }

    // ── Toggle active ─────────────────────────────────────────────

    private fun toggleSchedule(item: ScheduleItem, isActive: Boolean) {
        val uid = auth.currentUser?.uid ?: return

        // 1. Cập nhật local ngay lập tức để UI không bị revert
        val idx = allSchedules.indexOfFirst { it.scheduleId == item.scheduleId }
        if (idx != -1) {
            allSchedules[idx] = allSchedules[idx].copy(active = isActive)
            applyFilter()
        }

        // 2. Ghi lên Firebase (bất đồng bộ)
        // Lưu ý: Firebase tự động map biến `isActive` (Boolean) trong data class thành key `"active"` trên JSON.
        // Do đó, ta phải set value vào child("active") chứ không phải child("isActive").
        database.child(uid).child(item.scheduleId).child("active").setValue(isActive)
            .addOnFailureListener {
                // Nếu ghi thất bại, hoàn tác lại local
                if (isAdded) {
                    Toast.makeText(requireContext(), "Lỗi cập nhật", Toast.LENGTH_SHORT).show()
                    val i = allSchedules.indexOfFirst { s -> s.scheduleId == item.scheduleId }
                    if (i != -1) {
                        allSchedules[i] = allSchedules[i].copy(active = !isActive)
                        applyFilter()
                    }
                }
            }
    }

    // ── Sửa lịch ─────────────────────────────────────────────────

    private fun openEditScreen(item: ScheduleItem) {
        if (!isAdded) return
        parentFragmentManager.beginTransaction()
            .replace(R.id.content_frame, AddScheduleFragment.newInstance(item))
            .addToBackStack(null)
            .commit()
    }

    // ── Xóa lịch ─────────────────────────────────────────────────

    private fun confirmDelete(item: ScheduleItem) {
        if (!isAdded) return
        AlertDialog.Builder(requireContext())
            .setTitle("Xóa lịch nhắc")
            .setMessage("Bạn có chắc muốn xóa \"${item.title}\"?")
            .setPositiveButton("Xóa") { _, _ -> deleteSchedule(item) }
            .setNegativeButton("Hủy", null)
            .show()
    }

    private fun deleteSchedule(item: ScheduleItem) {
        val uid = auth.currentUser?.uid ?: return
        database.child(uid).child(item.scheduleId).removeValue()
            .addOnSuccessListener {
                if (isAdded) Toast.makeText(requireContext(), "Đã xóa lịch", Toast.LENGTH_SHORT).show()
            }
            .addOnFailureListener {
                if (isAdded) Toast.makeText(requireContext(), "Lỗi xóa: ${it.message}", Toast.LENGTH_SHORT).show()
            }
    }

    // ── Lifecycle ─────────────────────────────────────────────────

    override fun onDestroyView() {
        super.onDestroyView()
        // Hủy listener để tránh memory leak
        listener?.let {
            val uid = auth.currentUser?.uid
            if (uid != null) database.child(uid).removeEventListener(it)
        }
        _binding = null
    }
}
