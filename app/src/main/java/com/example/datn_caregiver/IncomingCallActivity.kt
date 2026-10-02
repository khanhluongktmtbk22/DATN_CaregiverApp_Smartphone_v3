package com.example.datn_caregiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.view.animation.AlphaAnimation
import android.view.animation.Animation
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.datn_caregiver.databinding.ActivityIncomingCallBinding
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase

/**
 * IncomingCallActivity – Hien thi khi Robot goi den.
 * Hoat dong nhu mot "cuoc goi den" giong Zalo:
 *  - Hien tren man hinh khoa (lock screen)
 *  - Danh thuc man hinh (turn screen on)
 *  - Bam "Nhan" → mo MainActivity va chuyen sang VideoCallFragment + tu dong nhan cuoc goi
 *  - Bam "Tu choi" → gui Firebase status=ended, dong Activity nay
 */
class IncomingCallActivity : AppCompatActivity() {

    private lateinit var binding: ActivityIncomingCallBinding
    private var actionHandled = false

    private val callEndedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            // Cuoc goi da ket thuc (remote side hung may hoac timeout)
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Cho phep hien tren man hinh khoa va danh thuc may
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            )
        }

        binding = ActivityIncomingCallBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Bat animation nhip cho vong tron
        val pulseAnim = AlphaAnimation(0.2f, 0.9f).apply {
            duration = 800
            repeatMode = Animation.REVERSE
            repeatCount = Animation.INFINITE
        }
        binding.pulseRing.startAnimation(pulseAnim)

        // Xu ly neu duoc mo tu notification nut "Tu choi"
        if (intent?.getStringExtra("action") == "decline") {
            declineCall()
            return
        }

        binding.btnAcceptCall.setOnClickListener { acceptCall() }
        binding.btnDeclineCall.setOnClickListener { declineCall() }

        // Lang nghe broadcast khi cuoc goi bi huy tu xa
        val filter = IntentFilter(IncomingCallService.ACTION_CALL_ENDED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(callEndedReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(callEndedReceiver, filter)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(callEndedReceiver) } catch (_: Exception) {}
    }

    private fun acceptCall() {
        if (actionHandled) return
        actionHandled = true
        binding.btnAcceptCall.isEnabled = false
        binding.btnDeclineCall.isEnabled = false
        // Mo MainActivity voi flag ACCEPT_CALL de VideoCallFragment tu dong nhan
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("navigate_to", "video_call")
            putExtra("auto_accept", true)
        }
        startActivity(intent)
        finish()
    }

    private fun declineCall() {
        if (actionHandled) return
        actionHandled = true
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: run {
            finish()
            return
        }
        FirebaseDatabase.getInstance()
            .getReference(FirebasePairPaths.activeCall(uid))
            .child("status").setValue("ended")
        finish()
    }
}
