package com.example.datn_caregiver

import android.app.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.*

/**
 * IncomingCallService – Foreground Service chay nen de lang nghe Firebase.
 * Khi Robot goi den (status="calling", callerRole="robot"):
 *   - Hien Full-Screen Intent (bat dau IncomingCallActivity) de danh thuc man hinh.
 *   - Phat notification cuoc goi den ngay ca khi Caregiver app dang o background.
 * Khi cuoc goi ket thuc (status="ended" hoac node xoa):
 *   - Huy notification.
 *   - Gui broadcast de IncomingCallActivity tu dong dong neu dang mo.
 */
class IncomingCallService : Service() {

    companion object {
        const val TAG = "IncomingCallService"
        const val CHANNEL_CALL = "incoming_call_channel"
        const val CHANNEL_SERVICE = "service_channel"
        const val NOTIF_ID_CALL = 1001
        const val NOTIF_ID_SERVICE = 1002
        const val ACTION_CALL_ENDED = "com.example.datn_caregiver.CALL_ENDED"

        fun start(context: Context) {
            val intent = Intent(context, IncomingCallService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, IncomingCallService::class.java))
        }
    }

    private lateinit var callRef: DatabaseReference
    private var callListener: ValueEventListener? = null
    private var isShowingCall = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
        startForeground(NOTIF_ID_SERVICE, buildServiceNotification())
        
        // Register decline receiver
        val filter = IntentFilter("com.example.datn_caregiver.ACTION_DECLINE_CALL")
        ContextCompat.registerReceiver(
            this,
            declineReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        listenForIncomingCall()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        if (::callRef.isInitialized) callListener?.let { callRef.removeEventListener(it) }
        try { unregisterReceiver(declineReceiver) } catch (_: Exception) {}
        super.onDestroy()
    }

    // ── Receiver for decline action from notification ────────────────────────
    
    private val declineReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "com.example.datn_caregiver.ACTION_DECLINE_CALL") {
                // Reject call
                if (::callRef.isInitialized) callRef.child("status").setValue("ended")
                writeCallHistory("rejected", "robot", 0L)
                cancelCallNotification()
            }
        }
    }

    // ── Firebase listener ──────────────────────────────────────────────────────

    private fun listenForIncomingCall() {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: run {
            Log.e(TAG, "Incoming-call listener stopped: no authenticated pair")
            stopSelf()
            return
        }
        callRef = FirebaseDatabase.getInstance().getReference(FirebasePairPaths.activeCall(uid))
        callListener = callRef.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val status     = snapshot.child("status").getValue(String::class.java)
                val callerRole = snapshot.child("callerRole").getValue(String::class.java)
                val emergencyReason = snapshot.child("emergencyReason").getValue(String::class.java)

                when {
                    status == "calling" && callerRole == "robot" && !isShowingCall -> {
                        isShowingCall = true
                        showIncomingCallNotification(emergencyReason)
                    }
                    (status == "ended" || status == "connected" || !snapshot.exists()) && isShowingCall -> {
                        isShowingCall = false
                        if (status == "ended" || !snapshot.exists()) {
                            writeCallHistory("missed", "robot", 0L)
                        }
                        cancelCallNotification()
                    }
                }
            }
            override fun onCancelled(e: DatabaseError) {
                Log.e(TAG, "Firebase listener cancelled: ${e.message}")
            }
        })
    }

    // ── Notifications ──────────────────────────────────────────────────────────

    private fun showIncomingCallNotification(emergencyReason: String?) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager

        // Intent mo IncomingCallActivity chi de hien thi toan man hinh (danh thuc may)
        val fullScreenIntent = Intent(this, IncomingCallActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val fullScreenPi = PendingIntent.getActivity(
            this, 0, fullScreenIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Intent cho nut "Nhan" tren notification (Mo thang MainActivity, bo qua IncomingCallActivity)
        val acceptIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("navigate_to", "video_call")
            putExtra("auto_accept", true)
        }
        val acceptPi = PendingIntent.getActivity(
            this, 1, acceptIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Intent cho nut "Tu choi" tren notification (Xu ly ngam bang BroadcastReceiver, khong mo Activity)
        val declineIntent = Intent("com.example.datn_caregiver.ACTION_DECLINE_CALL").apply {
            setPackage(packageName)
        }
        val declinePi = PendingIntent.getBroadcast(
            this, 2, declineIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notif = NotificationCompat.Builder(this, CHANNEL_CALL)
            .setSmallIcon(R.drawable.vc_ic_phone_accept)
            .setContentTitle(
                if (emergencyReason == "fall") "Cảnh báo nghi té ngã"
                else "Robot đang gọi đến"
            )
            .setContentText(
                if (emergencyReason == "fall") "Robot không xác nhận được an toàn và đang gọi khẩn cấp"
                else "Cuộc gọi video từ Robot tại nhà"
            )
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setAutoCancel(false)
            .setOngoing(true)
            .setFullScreenIntent(fullScreenPi, true)
            .setContentIntent(acceptPi) // Bam vao than thong bao => Nhan cuoc goi
            .addAction(R.drawable.vc_ic_call_end, "Từ chối", declinePi)
            .addAction(R.drawable.vc_ic_phone_accept, "Nhận", acceptPi)
            .build()

        nm.notify(NOTIF_ID_CALL, notif)

        // Bo dong startActivity(callIntent) de tranh viec app tu dong mo chanh choe khi dang dung app khac
    }

    private fun cancelCallNotification() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(NOTIF_ID_CALL)
        // Bao cho IncomingCallActivity biet cuoc goi da ket thuc
        sendBroadcast(Intent(ACTION_CALL_ENDED))
    }

    private fun writeCallHistory(status: String, initiator: String, durationSec: Long) {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        val nowMs = System.currentTimeMillis()
        FirebaseDatabase.getInstance()
            .getReference(FirebasePairPaths.callHistory(uid))
            .push().setValue(
            mapOf(
                "timestamp"   to nowMs,
                "initiator"   to initiator,
                "durationSec" to durationSec,
                "status"      to status
            )
        )
    }

    private fun buildServiceNotification(): Notification {
        val mainIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_SERVICE)
            .setSmallIcon(R.drawable.ic_video_call_24)
            .setContentTitle("Caregiver App")
            .setContentText("Đang chờ cuộc gọi từ Robot...")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(mainIntent)
            .build()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)

            // Kenh cuoc goi den – priority cao nhat
            val callChannel = NotificationChannel(
                CHANNEL_CALL,
                "Cuộc gọi đến",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Thông báo khi Robot gọi video"
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            nm.createNotificationChannel(callChannel)

            val serviceChannel = NotificationChannel(
                CHANNEL_SERVICE,
                "Caregiver Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Service lắng nghe cuộc gọi"
            }
            nm.createNotificationChannel(serviceChannel)
        }
    }
}
