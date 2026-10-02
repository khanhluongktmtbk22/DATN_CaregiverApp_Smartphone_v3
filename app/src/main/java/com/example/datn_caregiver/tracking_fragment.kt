package com.example.datn_caregiver

import android.os.Bundle
import android.util.Log
import android.view.View
import androidx.fragment.app.Fragment
import com.example.datn_caregiver.databinding.TrackingFragmentBinding
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ServerValue
import com.google.firebase.database.ValueEventListener

/**
 * TrackingFragment – CaregiverApp
 *
 * Màn hình điều khiển chế độ "Bám Theo Người Tự Động" của robot.
 * Caregiver bật/tắt công tắc tại đây → RobotApp đọc Firebase flag
 * → robot tự tính toán lệnh di chuyển và gửi xuống ESP32 qua BLE.
 *
 * Firebase nodes sử dụng:
 *   /robot_follow_enabled  (Boolean) – flag bật/tắt chính
 *   /robot_control         (Map)     – lệnh {x, y, ts} – dùng để gửi STOP khi tắt
 */
class TrackingFragment : Fragment(R.layout.tracking_fragment) {

    private var _binding: TrackingFragmentBinding? = null
    private val binding get() = _binding!!

    // ─── Firebase ──────────────────────────────────────────────────────────────
    private lateinit var followEnabledRef: DatabaseReference
    private lateinit var controlRef: DatabaseReference
    private lateinit var fallDetectionEnabledRef: DatabaseReference

    /** Listener đọc trạng thái flag từ Firebase (để đồng bộ UI nếu có thay đổi từ nguồn khác) */
    private var followFlagListener: ValueEventListener? = null
    private var fallDetectionFlagListener: ValueEventListener? = null

    // ─── State ─────────────────────────────────────────────────────────────────
    private var isAutoFollowOn = false
    private var isFallDetectionOn = false
    private var isFirebaseConnected = false

    companion object {
        private const val TAG = "CaregiverTracking"
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Lifecycle
    // ══════════════════════════════════════════════════════════════════════════

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = TrackingFragmentBinding.bind(view)

        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: run {
            binding.switchAutoFollow.isEnabled = false
            binding.switchFallDetection.isEnabled = false
            Log.e(TAG, "Tracking unavailable: no authenticated pair")
            return
        }
        val database = FirebaseDatabase.getInstance()
        followEnabledRef = database.getReference(FirebasePairPaths.followEnabled(uid))
        controlRef = database.getReference(FirebasePairPaths.robotControl(uid))
        fallDetectionEnabledRef = database.getReference(FirebasePairPaths.fallDetectionEnabled(uid))

        setupSwitch()
        setupFallDetectionSwitch()
        listenToFollowFlag()
        listenToFallDetectionFlag()
        checkFirebaseConnection()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        // Gỡ listener Firebase
        if (::followEnabledRef.isInitialized) {
            followFlagListener?.let { followEnabledRef.removeEventListener(it) }
        }
        followFlagListener = null
        if (::fallDetectionEnabledRef.isInitialized) {
            fallDetectionFlagListener?.let { fallDetectionEnabledRef.removeEventListener(it) }
        }
        fallDetectionFlagListener = null
        // KHÔNG tắt auto-follow khi rời màn hình — người dùng phải chủ động tắt
        // Điều này cho phép caregiver chuyển sang tab khác mà robot vẫn đang bám theo
        _binding = null
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Firebase setup
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Lắng nghe Firebase flag — đồng bộ UI nếu flag thay đổi từ nguồn ngoài.
     * Ví dụ: khi fragment mở lại, hiển thị đúng trạng thái ON/OFF hiện tại.
     */
    private fun listenToFollowFlag() {
        followFlagListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val enabled = snapshot.getValue(Boolean::class.java) ?: false
                // Đồng bộ UI từ Firebase (tránh vòng lặp set lại Firebase)
                if (isAutoFollowOn != enabled) {
                    isAutoFollowOn = enabled
                    updateUI(animate = false)
                }
                Log.d(TAG, "Firebase flag synced: $enabled")
            }
            override fun onCancelled(error: DatabaseError) {
                Log.e(TAG, "followFlag listener cancelled: ${error.message}")
                setFirebaseConnected(false)
            }
        }
        followEnabledRef.addValueEventListener(followFlagListener!!)
    }

    private fun listenToFallDetectionFlag() {
        fallDetectionFlagListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val enabled = snapshot.getValue(Boolean::class.java) ?: false
                if (isFallDetectionOn != enabled) {
                    isFallDetectionOn = enabled
                    updateFallDetectionUI(animate = false)
                }
            }
            override fun onCancelled(error: DatabaseError) {
                Log.e(TAG, "fallDetection listener cancelled: ${error.message}")
            }
        }
        fallDetectionEnabledRef.addValueEventListener(fallDetectionFlagListener!!)
    }

    private fun checkFirebaseConnection() {
        FirebaseDatabase.getInstance().getReference(".info/connected")
            .addValueEventListener(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val connected = snapshot.getValue(Boolean::class.java) ?: false
                    setFirebaseConnected(connected)
                }
                override fun onCancelled(error: DatabaseError) {
                    setFirebaseConnected(false)
                }
            })
    }

    private fun setFirebaseConnected(connected: Boolean) {
        isFirebaseConnected = connected
        val b = _binding ?: return
        if (connected) {
            b.tvFirebaseStatus.text = "Đã kết nối"
            b.tvFirebaseStatus.setTextColor(android.graphics.Color.parseColor("#4CAF50"))
        } else {
            b.tvFirebaseStatus.text = "Mất kết nối"
            b.tvFirebaseStatus.setTextColor(android.graphics.Color.parseColor("#F44336"))
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Switch / Toggle
    // ══════════════════════════════════════════════════════════════════════════

    private fun setupSwitch() {
        binding.switchAutoFollow.setOnCheckedChangeListener { _, isChecked ->
            // Tránh loop nếu UI đang được update từ Firebase
            if (isChecked == isAutoFollowOn) return@setOnCheckedChangeListener
            toggleAutoFollow(isChecked)
        }
    }

    private fun setupFallDetectionSwitch() {
        binding.switchFallDetection.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked == isFallDetectionOn) return@setOnCheckedChangeListener
            toggleFallDetection(isChecked)
        }
    }

    /**
     * Bật/tắt chế độ auto-follow.
     * Ghi flag lên Firebase → RobotApp nhận → tính toán lệnh → gửi BLE.
     */
    private fun toggleAutoFollow(enable: Boolean) {
        isAutoFollowOn = enable

        followEnabledRef.setValue(enable)
            .addOnSuccessListener {
                Log.d(TAG, "Auto-Follow set to $enable")
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Failed to set follow flag: ${e.message}")
                // Rollback nếu Firebase lỗi
                isAutoFollowOn = !enable
                updateUI(animate = false)
            }

        if (!enable) {
            // Gửi STOP ngay để đảm bảo robot không tiếp tục chạy
            try {
                controlRef.setValue(
                    mapOf("x" to 0.0, "y" to 0.0, "ts" to ServerValue.TIMESTAMP)
                )
            } catch (e: Exception) {
                Log.e(TAG, "sendStop error: ${e.message}")
            }
        }

        updateUI(animate = true)
    }

    // ══════════════════════════════════════════════════════════════════════════
    // UI Update
    // ══════════════════════════════════════════════════════════════════════════

    private fun updateUI(animate: Boolean) {
        val b = _binding ?: return

        // Đồng bộ switch (tránh trigger listener khi set từ code)
        b.switchAutoFollow.setOnCheckedChangeListener(null)
        b.switchAutoFollow.isChecked = isAutoFollowOn
        b.switchAutoFollow.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked == isAutoFollowOn) return@setOnCheckedChangeListener
            toggleAutoFollow(isChecked)
        }

        if (isAutoFollowOn) {
            // ── Trạng thái BẬT ──
            b.tvFollowModeDesc.text = "Đang bật — Robot đang bám theo người"
            b.tvFollowModeDesc.setTextColor(android.graphics.Color.parseColor("#4CAF50"))

            b.viewStatusDot.background = requireContext().getDrawable(R.drawable.tracking_dot_active)
            b.tvFollowStatus.text = "Robot đang trong chế độ tự động di chuyển"
            b.tvFollowStatus.setTextColor(android.graphics.Color.parseColor("#A0A0C0"))

            // Icon background sáng hơn khi bật
            b.frameFollowIconBg.background = requireContext().getDrawable(R.drawable.tracking_bg_icon_circle_active)
            b.ivFollowIcon.alpha = 0.9f

        } else {
            // ── Trạng thái TẮT ──
            b.tvFollowModeDesc.text = "Đang tắt"
            b.tvFollowModeDesc.setTextColor(android.graphics.Color.parseColor("#5E5E7A"))

            b.viewStatusDot.background = requireContext().getDrawable(R.drawable.tracking_dot_inactive)
            b.tvFollowStatus.text = "Robot đang chờ lệnh"
            b.tvFollowStatus.setTextColor(android.graphics.Color.parseColor("#5E5E7A"))

            b.frameFollowIconBg.background = requireContext().getDrawable(R.drawable.tracking_bg_icon_circle)
            b.ivFollowIcon.alpha = 0.15f
        }

        // Animation nhẹ cho card
        if (animate) {
            b.cardToggle.animate()
                .scaleX(0.97f).scaleY(0.97f)
                .setDuration(80)
                .withEndAction {
                    b.cardToggle.animate()
                        .scaleX(1f).scaleY(1f)
                        .setDuration(120)
                        .start()
                }.start()
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Fall Detection Toggle
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Bật/tắt pipeline YOLO + LSTM phát hiện té ngã.
     * Ghi flag lên Firebase → RobotApp nhận → bật/tắt LSTM inference.
     */
    private fun toggleFallDetection(enable: Boolean) {
        isFallDetectionOn = enable

        fallDetectionEnabledRef.setValue(enable)
            .addOnSuccessListener {
                Log.d(TAG, "Fall Detection set to $enable")
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Failed to set fall detection flag: ${e.message}")
                // Rollback nếu Firebase lỗi
                isFallDetectionOn = !enable
                updateFallDetectionUI(animate = false)
            }

        updateFallDetectionUI(animate = true)
    }

    private fun updateFallDetectionUI(animate: Boolean = true) {
        val b = _binding ?: return

        // Đồng bộ switch (tránh trigger listener khi set từ code)
        b.switchFallDetection.setOnCheckedChangeListener(null)
        b.switchFallDetection.isChecked = isFallDetectionOn
        b.switchFallDetection.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked == isFallDetectionOn) return@setOnCheckedChangeListener
            toggleFallDetection(isChecked)
        }

        if (isFallDetectionOn) {
            b.tvFallDetectionDesc.text = "Đang bật — YOLO + LSTM đang chạy"
            b.tvFallDetectionDesc.setTextColor(android.graphics.Color.parseColor("#E53935"))
            b.viewFallDetectionDot.background = requireContext().getDrawable(R.drawable.tracking_dot_active)
            b.tvFallDetectionStatus.text = "Phát hiện té ngã đang hoạt động"
            b.tvFallDetectionStatus.setTextColor(android.graphics.Color.parseColor("#A0A0C0"))
            b.frameFallIconBg.background = requireContext().getDrawable(R.drawable.tracking_bg_fall_icon_circle_active)
        } else {
            b.tvFallDetectionDesc.text = "Đang tắt"
            b.tvFallDetectionDesc.setTextColor(android.graphics.Color.parseColor("#5E5E7A"))
            b.viewFallDetectionDot.background = requireContext().getDrawable(R.drawable.tracking_dot_inactive)
            b.tvFallDetectionStatus.text = "YOLO + LSTM đang tắt"
            b.tvFallDetectionStatus.setTextColor(android.graphics.Color.parseColor("#5E5E7A"))
            b.frameFallIconBg.background = requireContext().getDrawable(R.drawable.tracking_bg_fall_icon_circle)
        }

        if (animate) {
            b.cardFallDetection.animate()
                .scaleX(0.97f).scaleY(0.97f)
                .setDuration(80)
                .withEndAction {
                    b.cardFallDetection.animate()
                        .scaleX(1f).scaleY(1f)
                        .setDuration(120)
                        .start()
                }.start()
        }
    }
}
