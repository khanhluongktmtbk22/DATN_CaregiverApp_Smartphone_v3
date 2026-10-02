package com.example.datn_caregiver

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.*

/**
 * Tab Cảnh Báo bên trong DiaryFragment.
 * Lắng nghe Firebase /fall_alerts realtime và hiển thị danh sách.
 */
class DiaryAlertsFragment : Fragment(R.layout.fragment_diary_alerts) {

    private var rvFallAlerts: RecyclerView? = null
    private var layoutEmpty: View? = null
    private lateinit var adapter: FallAlertAdapter
    private var listener: ValueEventListener? = null
    private lateinit var dbRef: DatabaseReference

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        rvFallAlerts = view.findViewById(R.id.rvFallAlerts)
        layoutEmpty  = view.findViewById(R.id.layoutFallEmpty)
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        dbRef = FirebaseDatabase.getInstance().getReference(FirebasePairPaths.fallAlerts(uid))

        adapter = FallAlertAdapter(emptyList())
        rvFallAlerts?.layoutManager = LinearLayoutManager(requireContext())
        rvFallAlerts?.adapter = adapter

        listenFallAlerts()
    }

    private fun listenFallAlerts() {
        // Giới hạn: chỉ lấy cảnh báo trong 7 ngày gần nhất
        val sevenDaysAgo = System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000
        listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (!isAdded) return
                val list = mutableListOf<FallAlertEntry>()
                for (child in snapshot.children) {
                    val entry = child.getValue(FallAlertEntry::class.java)
                        ?.copy(entryId = child.key ?: "") ?: continue
                    list.add(entry)
                }
                // Sắp xếp mới nhất trước
                list.sortByDescending { it.timestamp }
                val limitedList = list.take(20)
                adapter.updateList(limitedList)

                layoutEmpty?.visibility  = if (limitedList.isEmpty()) View.VISIBLE else View.GONE
                rvFallAlerts?.visibility = if (limitedList.isEmpty()) View.GONE    else View.VISIBLE
            }

            override fun onCancelled(error: DatabaseError) {}
        }
        dbRef.orderByChild("timestamp").startAt(sevenDaysAgo.toDouble())
            .addValueEventListener(listener!!)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        if (::dbRef.isInitialized) listener?.let { dbRef.removeEventListener(it) }
        rvFallAlerts = null
        layoutEmpty  = null
    }
}
