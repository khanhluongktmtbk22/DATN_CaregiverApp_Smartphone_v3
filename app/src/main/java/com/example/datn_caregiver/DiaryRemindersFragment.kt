package com.example.datn_caregiver

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.datn_caregiver.databinding.FragmentDiaryRemindersBinding
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.*

/**
 * DiaryRemindersFragment — Tab "Nhac Nho"
 *
 * Hien thi lich su cac loi nhac Robot da phat cho nguoi gia.
 * Doc tu Firebase: /reminder_logs/{uid}/{logId}
 * Cap nhat realtime.
 */
class DiaryRemindersFragment : Fragment(R.layout.fragment_diary_reminders) {

    private var _binding: FragmentDiaryRemindersBinding? = null
    private val binding get() = _binding!!

    private lateinit var adapter: ReminderLogAdapter
    private val auth by lazy { FirebaseAuth.getInstance() }
    private var logsRef: DatabaseReference? = null
    private var listener: ValueEventListener? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentDiaryRemindersBinding.bind(view)

        setupRecyclerView()
        loadReminderLogs()
    }

    private fun setupRecyclerView() {
        adapter = ReminderLogAdapter()
        binding.rvReminderLogs.layoutManager = LinearLayoutManager(requireContext())
        binding.rvReminderLogs.adapter = adapter
    }

    private fun loadReminderLogs() {
        val uid = auth.currentUser?.uid ?: return
        logsRef = FirebaseDatabase.getInstance().getReference("reminder_logs/$uid")

        listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val b = _binding ?: return
                val entries = mutableListOf<ReminderLogEntry>()

                for (child in snapshot.children) {
                    val logId             = child.key ?: continue
                    val scheduleId        = child.child("scheduleId").getValue(String::class.java) ?: ""
                    val title             = child.child("title").getValue(String::class.java) ?: ""
                    val note              = child.child("note").getValue(String::class.java) ?: ""
                    val triggeredAt       = child.child("triggeredAt").getValue(Long::class.java) ?: 0L
                    val status            = child.child("status").getValue(String::class.java) ?: "delivered"
                    val elderlyResponse   = child.child("elderlyResponse").getValue(String::class.java) ?: ""
                    val retryCount        = child.child("retryCount").getValue(Int::class.java) ?: 0
                    val confirmedAt       = child.child("confirmedAt").getValue(Long::class.java) ?: 0L
                    val caregiverName     = child.child("caregiverName").getValue(String::class.java) ?: ""
                    val caregiverRelation = child.child("caregiverRelation").getValue(String::class.java) ?: ""

                    entries.add(ReminderLogEntry(
                        logId             = logId,
                        scheduleId        = scheduleId,
                        title             = title,
                        note              = note,
                        triggeredAt       = triggeredAt,
                        status            = status,
                        elderlyResponse   = elderlyResponse,
                        retryCount        = retryCount,
                        confirmedAt       = confirmedAt,
                        caregiverName     = caregiverName,
                        caregiverRelation = caregiverRelation
                    ))
                }

                // Sap xep giam dan theo thoi gian
                entries.sortByDescending { it.triggeredAt }
                val limitedEntries = entries.take(20)
                adapter.updateList(limitedEntries)

                b.layoutEmpty.visibility = if (limitedEntries.isEmpty()) View.VISIBLE else View.GONE
                b.rvReminderLogs.visibility = if (limitedEntries.isEmpty()) View.GONE else View.VISIBLE
            }

            override fun onCancelled(error: DatabaseError) {
                if (isAdded) {
                    Toast.makeText(requireContext(), "Loi tai log: ${error.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }

        logsRef?.addValueEventListener(listener!!)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        listener?.let { logsRef?.removeEventListener(it) }
        _binding = null
    }
}
