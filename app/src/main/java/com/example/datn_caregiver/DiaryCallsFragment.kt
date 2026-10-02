package com.example.datn_caregiver

import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.*

/**
 * Tab Cuộc Gọi bên trong DiaryFragment.
 * Lắng nghe Firebase /call_history realtime và hiển thị danh sách.
 */
class DiaryCallsFragment : Fragment(R.layout.fragment_diary_calls) {

    private var rvCallHistory: RecyclerView? = null
    private var layoutEmpty: View? = null
    private lateinit var adapter: CallHistoryAdapter
    private var listener: ValueEventListener? = null
    private lateinit var dbRef: DatabaseReference

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        rvCallHistory = view.findViewById(R.id.rvCallHistory)
        layoutEmpty   = view.findViewById(R.id.layoutCallEmpty)
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        dbRef = FirebaseDatabase.getInstance().getReference(FirebasePairPaths.callHistory(uid))

        adapter = CallHistoryAdapter(emptyList())
        rvCallHistory?.layoutManager = LinearLayoutManager(requireContext())
        rvCallHistory?.adapter = adapter

        listenCallHistory()
    }

    private fun listenCallHistory() {
        // Giới hạn: chỉ lấy các cuộc gọi trong 7 ngày gần nhất
        val sevenDaysAgo = System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000
        listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (!isAdded) return
                val list = mutableListOf<CallHistoryEntry>()
                for (child in snapshot.children) {
                    val entry = child.getValue(CallHistoryEntry::class.java)
                        ?.copy(entryId = child.key ?: "") ?: continue
                    list.add(entry)
                }
                // Sắp xếp mới nhất trước
                list.sortByDescending { it.timestamp }
                val limitedList = list.take(20)
                adapter.updateList(limitedList)

                layoutEmpty?.visibility  = if (limitedList.isEmpty()) View.VISIBLE else View.GONE
                rvCallHistory?.visibility = if (limitedList.isEmpty()) View.GONE   else View.VISIBLE
            }

            override fun onCancelled(error: DatabaseError) {}
        }
        dbRef.orderByChild("timestamp").startAt(sevenDaysAgo.toDouble())
            .addValueEventListener(listener!!)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        if (::dbRef.isInitialized) listener?.let { dbRef.removeEventListener(it) }
        rvCallHistory = null
        layoutEmpty   = null
    }
}
