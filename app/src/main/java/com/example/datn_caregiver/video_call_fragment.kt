package com.example.datn_caregiver

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.AlphaAnimation
import android.view.animation.Animation
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.example.datn_caregiver.databinding.VideoCallFragmentBinding
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.*
import org.webrtc.*
import java.util.concurrent.Executors
import kotlin.math.hypot
import kotlin.math.min

/**
 * VideoCallFragment – CAREGIVER APP
 *
 * Firebase node: /webrtc_signal (dùng chung với Robot App, cùng tài khoản Auth)
 *
 * Luồng:
 *  - Caregiver là CALLER: nhấn "Gọi Robot" → tạo Offer → Robot tự động nhận
 *  - Caregiver là CALLEE: Robot gọi đến → hiển thị màn hình "Nhận / Từ chối" (có chuông + rung)
 *
 * ICE Candidate:
 *  - Caregiver luôn ghi vào "caregiverCandidates"
 *  - Caregiver luôn đọc từ "robotCandidates"
 *
 * Robot Control:
 *  - Virtual joystick overlay (bottom-left) khi call active
 *  - Gửi {x, y, ts} lên Firebase /robot_control với throttle 20Hz
 */
class VideoCallFragment : Fragment() {

    private companion object {
        const val TAG = "CaregiverVideoCall"

        // ── Metered TURN config ────────────────────────────────────────────────
        const val METERED_USERNAME   = "a45ea4fbddc012d96b52165a"
        const val METERED_CREDENTIAL = "24yjin88NfdaGLkC"

        // Throttle gửi control command: tối đa 20Hz (50ms)
        const val CONTROL_THROTTLE_MS = 50L
        const val CALL_TIMEOUT_MS = 30000L
        // Shared across fragment instances: finish releasing the old camera before opening another.
        private val mediaExecutor = Executors.newSingleThreadExecutor { task ->
            Thread(task, "CaregiverMediaLifecycle")
        }
    }

    // ─── View Binding ──────────────────────────────────────────────────────────
    private var _binding: VideoCallFragmentBinding? = null
    private val binding get() = _binding!!

    // ─── Firebase ──────────────────────────────────────────────────────────────
    private lateinit var callRef: DatabaseReference
    private lateinit var controlRef: DatabaseReference
    private lateinit var serverTimeOffsetRef: DatabaseReference

    // ─── WebRTC ────────────────────────────────────────────────────────────────
    private var eglBase: EglBase? = null
    private var factory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var localAudioTrack: AudioTrack? = null
    private var localVideoTrack: VideoTrack? = null
    private var videoCapturer: VideoCapturer? = null
    private var localAudioSource: AudioSource? = null
    private var localVideoSource: VideoSource? = null
    private var captureHelper: SurfaceTextureHelper? = null
    private var remoteVideoTrack: VideoTrack? = null
    private var mediaReady = false
    private var viewReleased = true
    private var viewGeneration = 0
    private var callGeneration = 0
    private var offerListener: ValueEventListener? = null

    // ─── Audio Manager ─────────────────────────────────────────────────────────
    private var audioManager: AudioManager? = null
    private var savedAudioMode: Int = AudioManager.MODE_NORMAL
    private var savedSpeakerOn: Boolean = false

    // ─── State ─────────────────────────────────────────────────────────────────
    private var currentRole: String? = null   // "caller" | "callee" | null
    private var isCallActive = false
    private var remoteDescSet = false
    private val pendingIceCandidates = mutableListOf<IceCandidate>()
    private var isMicOn = true
    private var isCamOn = true
    private var hasHandledIncoming = false
    /** True khi duoc mo tu IncomingCallActivity voi nut Nhan – tu dong nhan cuoc goi */
    private var autoAcceptPending = false

    /** Timestamp (ms) khi ICE CONNECTED — dùng để tính thời lượng cuộc gọi */
    private var callStartMs = 0L

    /** Firebase ref ghi lịch sử cuộc gọi vào /call_history */
    private lateinit var callHistoryRef: DatabaseReference

    // ─── Control Panel State ───────────────────────────────────────────────
    private var isControlPanelExpanded = false
    private var isJoystickVisible = false
    private var isAutoFollowActive = false
    /** Firebase ref cho /robot_follow_enabled (gốp với TrackingFragment) */
    private lateinit var followEnabledRef: DatabaseReference

    // ─── Joystick throttle ─────────────────────────────────────────────────────
    private var lastControlSentMs = 0L
    private var joystickHeld = false
    private var joystickX = 0f
    private var joystickY = 0f
    private val joystickHeartbeat = object : Runnable {
        override fun run() {
            if (!joystickHeld || !isCallActive || !isJoystickVisible || isAutoFollowActive) return
            sendControlIfThrottled(joystickX, joystickY)
            callHandler.postDelayed(this, 100L)
        }
    }

    // ─── Firebase Listeners ────────────────────────────────────────────────────
    private var callNodeListener: ValueEventListener? = null
    private var answerListener: ValueEventListener? = null
    private var remoteCandidateListener: ChildEventListener? = null
    private var followFlagListener: ValueEventListener? = null
    private var serverTimeOffsetListener: ValueEventListener? = null
    @Volatile private var serverTimeOffsetMs: Long? = null

    // ─── Ringtone / Vibrator ────────────────────────────────────────────────────────
    private var ringtone: Ringtone? = null
    private var vibrator: Vibrator? = null
    private var pulseAnim: Animation? = null

    // ─── Timeout & Handlers ──────────────────────────────────────────────────
    private val callHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val callTimeoutRunnable = Runnable { onCallTimeout() }

    // ─── Status auto-hide ────────────────────────────────────────────────────
    private val statusHideRunnable = Runnable {
        _binding?.tvStatus?.visibility = View.GONE
    }

    // ─── Permission Launcher ───────────────────────────────────────────────────
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants.all { it.value }) {
            initWebRTC()
        } else {
            Toast.makeText(
                context,
                "Can quyen Camera va Microphone de dung Video Call",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Lifecycle
    // ══════════════════════════════════════════════════════════════════════════

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        // Ép xoay ngang màn hình cho chức năng gọi video
        activity?.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE
        _binding = VideoCallFragmentBinding.inflate(inflater, container, false)
        viewReleased = false
        viewGeneration++
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: run {
            Toast.makeText(requireContext(), "Phiên đăng nhập không hợp lệ", Toast.LENGTH_LONG).show()
            return
        }
        val database = FirebaseDatabase.getInstance()
        callRef = database.getReference(FirebasePairPaths.activeCall(uid))
        controlRef = database.getReference(FirebasePairPaths.robotControl(uid))
        callHistoryRef = database.getReference(FirebasePairPaths.callHistory(uid))
        followEnabledRef = database.getReference(FirebasePairPaths.followEnabled(uid))
        serverTimeOffsetRef = database.getReference(".info/serverTimeOffset")
        setupButtonListeners()
        setupVirtualJoystick()
        showIdleState()
        listenToFollowFlag()
        listenToServerTimeOffset()

        // Phai set truoc checkAndRequestPermissions() vi initWebRTC() se kiem tra autoAcceptPending
        if (arguments?.getBoolean("auto_accept", false) == true) {
            arguments?.remove("auto_accept")
            autoAcceptPending = true
            hasHandledIncoming = true
        }

        checkAndRequestPermissions()
    }

    override fun onDestroyView() {
        viewReleased = true
        viewGeneration++
        autoAcceptPending = false
        callHandler.removeCallbacksAndMessages(null)
        super.onDestroyView()
        // Trả lại xoay màn hình mặc định khi thoát
        activity?.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        stopRingtone()
        // Gỡ listener follow flag
        if (::followEnabledRef.isInitialized) {
            followFlagListener?.let { followEnabledRef.removeEventListener(it) }
        }
        followFlagListener = null
        if (::serverTimeOffsetRef.isInitialized) {
            serverTimeOffsetListener?.let { serverTimeOffsetRef.removeEventListener(it) }
        }
        serverTimeOffsetListener = null
        serverTimeOffsetMs = null
        // Gửi lệnh dừng khi thoát
        sendStopCommand()
        releaseAll()
        _binding = null
    }

    // ══════════════════════════════════════════════════════════════════════════
    // UI State
    // ══════════════════════════════════════════════════════════════════════════

    private fun setupButtonListeners() {
        binding.btnCallRobot.setOnClickListener    { startCallAsCaregiver() }
        binding.btnEndCall.setOnClickListener      { endCall(notifyRemote = true) }
        binding.btnToggleMic.setOnClickListener    { toggleMic() }
        binding.btnToggleCamera.setOnClickListener { toggleCamera() }
        binding.btnAcceptCall.setOnClickListener   { acceptIncomingCall() }
        binding.btnDeclineCall.setOnClickListener  { declineIncomingCall() }

        // Panel expand/collapse toggle
        binding.btnExpandControls.setOnClickListener { toggleControlPanel() }

        // Joystick visibility toggle
        binding.btnToggleJoystick.setOnClickListener { toggleJoystick() }

        // Auto-Follow toggle (mutual exclusion với Joystick)
        binding.btnToggleAutoFollow.setOnClickListener { toggleAutoFollow() }
    }

    private fun showIdleState() {
        stopRingtone()
        currentRole        = null
        isCallActive       = false
        remoteDescSet      = false
        hasHandledIncoming = false
        isMicOn            = true
        isCamOn            = true
        pendingIceCandidates.clear()

        // Luôn bật mic và cam khi về trạng thái idle, đảm bảo cuộc gọi mới luôn mở
        localAudioTrack?.setEnabled(true)
        localVideoTrack?.setEnabled(true)

        // Reset control panel
        isControlPanelExpanded = false
        isJoystickVisible = false
        isAutoFollowActive = false
        binding.layoutExpandedControls.visibility = View.GONE
        binding.btnExpandControls.setImageResource(R.drawable.vc_ic_expand_panel)
        binding.layoutJoystick.visibility = View.GONE
        // Reset auto-follow button UI
        binding.btnToggleAutoFollow.alpha = 0.6f
        binding.tvAutoFollowLabel.text = "Bám theo"

        binding.layoutIdle.visibility        = View.VISIBLE
        binding.layoutIncomingCall.visibility = View.GONE
        binding.layoutCallControls.visibility = View.GONE
        binding.remoteSurfaceView.visibility  = View.INVISIBLE
        binding.localVideoCard.visibility     = View.GONE

        // Khôi phục nút gọi về trạng thái bình thường
        binding.btnCallRobot.isEnabled = true
        binding.btnCallRobot.alpha = 1.0f
        binding.btnCallRobot.text = "Gọi Video Robot"

        // Hiển status và reset text
        binding.tvStatus.visibility = View.VISIBLE
        binding.tvStatus.text = "Sẵn sàng kết nối"
        binding.root.removeCallbacks(statusHideRunnable)

        // Reset mic/cam button icons
        binding.btnToggleMic.setImageResource(R.drawable.vc_ic_mic_on)
        binding.btnToggleMic.alpha = 1.0f
        binding.btnToggleCamera.setImageResource(R.drawable.vc_ic_cam_on)
        binding.btnToggleCamera.alpha = 1.0f

        // Clear last video frame
        try { binding.remoteSurfaceView.clearImage() } catch (_: Exception) {}
    }

    private fun showIncomingCallState() {
        binding.layoutIdle.visibility        = View.GONE
        binding.layoutIncomingCall.visibility = View.VISIBLE
        binding.layoutCallControls.visibility = View.GONE
        binding.tvStatus.visibility = View.VISIBLE
        binding.tvStatus.text = "Robot đang gọi đến..."
        binding.root.removeCallbacks(statusHideRunnable)

        // Pulse animation for incoming call ring
        startPulseAnimation()
    }

    private fun showActiveCallState(statusText: String, autoHide: Boolean = false) {
        stopRingtone()
        binding.layoutIdle.visibility        = View.GONE
        binding.layoutIncomingCall.visibility = View.GONE
        binding.layoutCallControls.visibility = View.VISIBLE
        binding.tvStatus.visibility = View.VISIBLE
        binding.tvStatus.text = statusText
        binding.root.removeCallbacks(statusHideRunnable)
        if (autoHide) binding.root.postDelayed(statusHideRunnable, 3000)
    }

    private fun updateStatus(text: String, autoHide: Boolean = false) {
        activity?.runOnUiThread {
            if (viewReleased || _binding == null) return@runOnUiThread
            binding.tvStatus.visibility = View.VISIBLE
            binding.tvStatus.text = text
            binding.root.removeCallbacks(statusHideRunnable)
            if (autoHide) binding.root.postDelayed(statusHideRunnable, 3000)
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Control Panel – Expand / Collapse
    // ══════════════════════════════════════════════════════════════════════════

    private fun toggleControlPanel() {
        isControlPanelExpanded = !isControlPanelExpanded
        if (isControlPanelExpanded) {
            binding.layoutExpandedControls.visibility = View.VISIBLE
            binding.btnExpandControls.setImageResource(R.drawable.vc_ic_collapse_panel)
        } else {
            binding.layoutExpandedControls.visibility = View.GONE
            binding.btnExpandControls.setImageResource(R.drawable.vc_ic_expand_panel)
            // Không tự động tắt joystick khi thu gọn panel theo yêu cầu
        }
    }

    private fun toggleJoystick() {
        isJoystickVisible = !isJoystickVisible
        if (isJoystickVisible) {
            // Xóa lệnh trước và công bố chế độ thủ công kể cả khi UI chưa kịp đồng bộ cờ.
            sendStopCommand()
            followEnabledRef.setValue(false)
            binding.layoutJoystick.visibility = View.VISIBLE
            binding.btnToggleJoystick.alpha = 1.0f
            // Joystick bật → tắt Auto-Follow để tránh xung đột lệnh di chuyển
            if (isAutoFollowActive) {
                isAutoFollowActive = false
                binding.btnToggleAutoFollow.alpha = 0.6f
                binding.tvAutoFollowLabel.text = "Bám theo"
                Log.d(TAG, "Joystick on → Auto-Follow disabled")
            }
        } else {
            binding.layoutJoystick.visibility = View.GONE
            binding.btnToggleJoystick.alpha = 0.6f
            sendStopCommand()
        }
    }

    /**
     * Bật/tắt chế độ bám theo người tự động trong cuộc gọi.
     * Mutual exclusion với Joystick: bật Auto-Follow → ẩn Joystick.
     * Ghi vào Firebase /robot_follow_enabled (chung với TrackingFragment).
     */
    private fun toggleAutoFollow() {
        isAutoFollowActive = !isAutoFollowActive
        if (isAutoFollowActive) {
            // Bật Auto-Follow → tắt Joystick
            if (isJoystickVisible) {
                isJoystickVisible = false
                binding.layoutJoystick.visibility = View.GONE
                binding.btnToggleJoystick.alpha = 0.6f
                sendStopCommand()
            }
            followEnabledRef.setValue(true)
            binding.btnToggleAutoFollow.alpha = 1.0f
            binding.tvAutoFollowLabel.text = "Bám • ON"
            Log.d(TAG, "Auto-Follow: Enabled via VideoCall panel")
        } else {
            // Tắt Auto-Follow → gửi STOP
            followEnabledRef.setValue(false)
            sendStopCommand()
            binding.btnToggleAutoFollow.alpha = 0.6f
            binding.tvAutoFollowLabel.text = "Bám theo"
            Log.d(TAG, "Auto-Follow: Disabled via VideoCall panel")
        }
    }

    private fun listenToFollowFlag() {
        followFlagListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val enabled = snapshot.getValue(Boolean::class.java) ?: false
                if (isAutoFollowActive != enabled) {
                    isAutoFollowActive = enabled
                    activity?.runOnUiThread {
                        val b = _binding ?: return@runOnUiThread
                        if (enabled) {
                            if (isJoystickVisible) {
                                isJoystickVisible = false
                                b.layoutJoystick.visibility = View.GONE
                                b.btnToggleJoystick.alpha = 0.6f
                                sendStopCommand()
                            }
                            b.btnToggleAutoFollow.alpha = 1.0f
                            b.tvAutoFollowLabel.text = "Bám • ON"
                        } else {
                            b.btnToggleAutoFollow.alpha = 0.6f
                            b.tvAutoFollowLabel.text = "Bám theo"
                        }
                    }
                }
            }
            override fun onCancelled(error: DatabaseError) {}
        }
        followEnabledRef.addValueEventListener(followFlagListener!!)
    }

    /** Đồng bộ đồng hồ cục bộ với mốc thời gian của Firebase. */
    private fun listenToServerTimeOffset() {
        serverTimeOffsetListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                serverTimeOffsetMs = snapshot.getValue(Long::class.java)
                Log.d(TAG, "Firebase server-time offset=$serverTimeOffsetMs ms")
            }

            override fun onCancelled(error: DatabaseError) {
                serverTimeOffsetMs = null
                Log.e(TAG, "Server-time synchronization cancelled: ${error.message}")
            }
        }
        serverTimeOffsetRef.addValueEventListener(serverTimeOffsetListener!!)
    }

    private fun estimatedServerNow(): Long? =
        serverTimeOffsetMs?.let { offset -> System.currentTimeMillis() + offset }

    // ══════════════════════════════════════════════════════════════════════════
    // Virtual Joystick
    // ══════════════════════════════════════════════════════════════════════════

    @SuppressLint("ClickableViewAccessibility")
    private fun setupVirtualJoystick() {
        val base = binding.joystickBase
        val knob = binding.joystickKnob

        binding.layoutJoystick.setOnTouchListener { _, event ->
            val baseSize   = base.width.toFloat()          // diameter of base in px
            val baseCenterX = baseSize / 2f
            val baseCenterY = baseSize / 2f
            val maxRadius   = baseSize / 2f * 0.75f        // knob max travel = 75% radius
            val knobHalf    = knob.width / 2f

            when (event.action) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                    val dx = event.x - baseCenterX
                    val dy = event.y - baseCenterY
                    val dist = hypot(dx, dy)

                    // Clamp knob to circle boundary
                    val clampedDx: Float
                    val clampedDy: Float
                    if (dist > maxRadius) {
                        val scale = maxRadius / dist
                        clampedDx = dx * scale
                        clampedDy = dy * scale
                    } else {
                        clampedDx = dx
                        clampedDy = dy
                    }

                    // Move knob visually
                    knob.x = baseCenterX + clampedDx - knobHalf
                    knob.y = baseCenterY + clampedDy - knobHalf

                    // Mapping đã hiệu chỉnh trên robot thật:
                    // kéo lên (dy < 0)  -> kênh x âm
                    // kéo trái (dx < 0) -> kênh y dương
                    val normX = clampedDy / maxRadius
                    val normY = -clampedDx / maxRadius

                    joystickX = normX
                    joystickY = normY
                    if (!joystickHeld) {
                        joystickHeld = true
                        callHandler.postDelayed(joystickHeartbeat, 100L)
                    }
                    sendControlIfThrottled(normX, normY)
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    // Return knob to center with animation
                    knob.animate()
                        .x(baseCenterX - knobHalf)
                        .y(baseCenterY - knobHalf)
                        .setDuration(150)
                        .start()
                    sendStopCommand()
                }
            }
            true
        }
    }

    override fun onStop() {
        if (joystickHeld) sendStopCommand()
        super.onStop()
    }

    private fun sendControlIfThrottled(x: Float, y: Float) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastControlSentMs < CONTROL_THROTTLE_MS) return
        lastControlSentMs = now
        sendControlCommand(x, y)
    }

    /**
     * Ghi tín hiệu điều khiển lên Firebase.
     * x: [-1.0, +1.0] – kênh tiến/lùi
     * y: [-1.0, +1.0] – kênh quay đã bù theo hướng joystick
     */
    private fun sendControlCommand(x: Float, y: Float) {
        if (!isCallActive || !isJoystickVisible || isAutoFollowActive || !joystickHeld) return
        val timestamp = estimatedServerNow() ?: run {
            Log.w(TAG, "Control command ignored: Firebase server time is not synchronized")
            return
        }
        val cmd = mapOf(
            "x"  to x.toDouble(),
            "y"  to y.toDouble(),
            "ts" to timestamp
        )
        controlRef.setValue(cmd).addOnFailureListener { e ->
            Log.e(TAG, "Failed to send control: ${e.message}")
        }
    }

    private fun sendStopCommand() {
        joystickHeld = false
        joystickX = 0f
        joystickY = 0f
        callHandler.removeCallbacks(joystickHeartbeat)
        if (!::controlRef.isInitialized) return
        try {
            val timestamp: Any = estimatedServerNow() ?: ServerValue.TIMESTAMP
            controlRef.setValue(mapOf("x" to 0.0, "y" to 0.0, "ts" to timestamp))
        } catch (_: Exception) {}
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Ringtone + Vibration
    // ══════════════════════════════════════════════════════════════════════════

    private fun startRingtone() {
        try {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            ringtone = RingtoneManager.getRingtone(requireContext(), uri)
            ringtone?.audioAttributes = android.media.AudioAttributes.Builder()
                .setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            ringtone?.play()
        } catch (e: Exception) {
            Log.e(TAG, "Ringtone error: ${e.message}")
        }

        try {
            vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = requireContext().getSystemService(VibratorManager::class.java)
                vm.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                requireContext().getSystemService(android.content.Context.VIBRATOR_SERVICE) as Vibrator
            }
            val pattern = longArrayOf(0, 700, 500, 700, 500)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(VibrationEffect.createWaveform(pattern, 0))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(pattern, 0)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Vibrator error: ${e.message}")
        }
    }

    private fun stopRingtone() {
        pulseAnim?.cancel()
        pulseAnim = null
        try { if (ringtone?.isPlaying == true) ringtone?.stop() } catch (_: Exception) {}
        ringtone = null
        try { vibrator?.cancel() } catch (_: Exception) {}
        vibrator = null
    }

    private fun startPulseAnimation() {
        val anim = AlphaAnimation(0.2f, 0.8f).apply {
            duration = 800
            repeatMode = Animation.REVERSE
            repeatCount = Animation.INFINITE
        }
        pulseAnim = anim
        binding.pulseRing.startAnimation(anim)
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Permissions
    // ══════════════════════════════════════════════════════════════════════════

    private fun checkAndRequestPermissions() {
        val perms = arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        val missing = perms.filter {
            ContextCompat.checkSelfPermission(requireContext(), it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) initWebRTC() else permissionLauncher.launch(missing.toTypedArray())
    }

    // ══════════════════════════════════════════════════════════════════════════
    // WebRTC Initialization
    // ══════════════════════════════════════════════════════════════════════════

    @Suppress("DEPRECATION")
    private fun initWebRTC() {
        if (viewReleased || _binding == null || factory != null) return
        Log.d(TAG, "Initializing media for view $viewGeneration")
        // ── Cấu hình AudioManager để kích hoạt AEC phần cứng ──────────────────
        audioManager = requireContext().getSystemService(android.content.Context.AUDIO_SERVICE) as AudioManager
        savedAudioMode = audioManager!!.mode
        savedSpeakerOn = audioManager!!.isSpeakerphoneOn
        audioManager!!.mode = AudioManager.MODE_IN_COMMUNICATION
        audioManager!!.isSpeakerphoneOn = true

        eglBase = EglBase.create()

        binding.localSurfaceView.apply {
            init(eglBase!!.eglBaseContext, null)
            setEnableHardwareScaler(true)
            setMirror(true)        // Caregiver dùng camera trước → mirror
            setZOrderMediaOverlay(true) // Đảm bảo PiP hiển thị đúng lớp trên cùng
        }
        binding.remoteSurfaceView.apply {
            init(eglBase!!.eglBaseContext, null)
            setEnableHardwareScaler(true)
            setMirror(false)
        }

        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions
                .builder(requireContext())
                .setEnableInternalTracer(false)
                .createInitializationOptions()
        )
        factory = PeerConnectionFactory.builder()
            .setOptions(PeerConnectionFactory.Options())
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglBase!!.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase!!.eglBaseContext))
            .createPeerConnectionFactory()

        setupLocalMedia()
        listenForCallState()

        // Neu duoc kich hoat tu IncomingCallActivity: tu dong nhan cuoc goi den tu Robot
        acceptPendingCallIfReady()
    }

    fun requestAcceptIncomingCall() {
        if (currentRole != null || isCallActive) return
        autoAcceptPending = true
        hasHandledIncoming = true
        acceptPendingCallIfReady()
    }

    private fun acceptPendingCallIfReady() {
        if (!autoAcceptPending || !mediaReady || viewReleased || _binding == null) return
        autoAcceptPending = false
        if (currentRole == null && !isCallActive) acceptIncomingCall()
    }

    private fun buildAudioConstraints(): MediaConstraints = MediaConstraints().apply {
        mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
        mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation2", "true"))
        mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
        mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression2", "true"))
        mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", "true"))
        mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl2", "true"))
        mandatory.add(MediaConstraints.KeyValuePair("googHighpassFilter", "true"))
        mandatory.add(MediaConstraints.KeyValuePair("googAudioMirroring", "false"))
    }

    private fun setupLocalMedia() {
        localAudioSource = factory!!.createAudioSource(buildAudioConstraints())
        localAudioTrack = factory!!.createAudioTrack(
            "CAREGIVER_AUDIO",
            localAudioSource!!
        )

        val enumerator = Camera2Enumerator(requireContext())
        val cameraId = enumerator.deviceNames.firstOrNull { enumerator.isFrontFacing(it) }
            ?: enumerator.deviceNames.firstOrNull()
            ?: run { Log.w(TAG, "No camera found on device"); mediaReady = true; return }

        val capturer = enumerator.createCapturer(cameraId, null)
            ?: run { Log.w(TAG, "Cannot create camera capturer"); mediaReady = true; return }
        videoCapturer = capturer

        val helper = SurfaceTextureHelper.create("CaregiverCaptureThread", eglBase!!.eglBaseContext)
        val videoSource = factory!!.createVideoSource(capturer.isScreencast)
        captureHelper = helper
        localVideoSource = videoSource

        localVideoTrack = factory!!.createVideoTrack("CAREGIVER_VIDEO", videoSource)
        localVideoTrack!!.addSink(binding.localSurfaceView)
        val appContext = requireContext().applicationContext
        val generation = viewGeneration
        mediaExecutor.execute {
            try {
                capturer.initialize(helper, appContext, videoSource.capturerObserver)
                capturer.startCapture(640, 480, 30)
                callHandler.post {
                    if (!viewReleased && generation == viewGeneration) {
                        mediaReady = true
                        acceptPendingCallIfReady()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Cannot start camera", e)
                callHandler.post {
                    if (!viewReleased && generation == viewGeneration) {
                        updateStatus("Không mở được camera. Vui lòng mở lại màn hình gọi.")
                    }
                }
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // ICE Server Configuration
    // ══════════════════════════════════════════════════════════════════════════

    private fun getIceServers(): List<PeerConnection.IceServer> = listOf(
        PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
        PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer(),
        PeerConnection.IceServer.builder("stun:global.relay.metered.ca:80").createIceServer(),
        PeerConnection.IceServer.builder("turn:global.relay.metered.ca:80")
            .setUsername(METERED_USERNAME).setPassword(METERED_CREDENTIAL).createIceServer(),
        PeerConnection.IceServer.builder("turn:global.relay.metered.ca:80?transport=tcp")
            .setUsername(METERED_USERNAME).setPassword(METERED_CREDENTIAL).createIceServer(),
        PeerConnection.IceServer.builder("turn:global.relay.metered.ca:443")
            .setUsername(METERED_USERNAME).setPassword(METERED_CREDENTIAL).createIceServer(),
        PeerConnection.IceServer.builder("turns:global.relay.metered.ca:443?transport=tcp")
            .setUsername(METERED_USERNAME).setPassword(METERED_CREDENTIAL).createIceServer()
    )

    // ══════════════════════════════════════════════════════════════════════════
    // PeerConnection
    // ══════════════════════════════════════════════════════════════════════════

    private fun createPeerConnection(): PeerConnection? {
        val generation = ++callGeneration
        val config = PeerConnection.RTCConfiguration(getIceServers()).apply {
            sdpSemantics            = PeerConnection.SdpSemantics.UNIFIED_PLAN
            iceTransportsType       = PeerConnection.IceTransportsType.ALL
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }
        return factory!!.createPeerConnection(config, object : PeerConnection.Observer {
            override fun onSignalingChange(s: PeerConnection.SignalingState?) {}
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
                Log.d(TAG, "ICE state: $state")
                activity?.runOnUiThread {
                    if (viewReleased || generation != callGeneration || _binding == null) return@runOnUiThread
                    when (state) {
                        PeerConnection.IceConnectionState.CHECKING ->
                            updateStatus("Đang kết nối...")

                        PeerConnection.IceConnectionState.CONNECTED -> {
                            callHandler.removeCallbacks(callTimeoutRunnable)
                            isCallActive = true
                            callStartMs  = System.currentTimeMillis()  // bắt đầu tính thời lượng
                            updateStatus("Đã kết nối", autoHide = true)
                            binding.remoteSurfaceView.visibility  = View.VISIBLE
                            binding.localVideoCard.visibility     = View.VISIBLE
                            binding.layoutCallControls.visibility = View.VISIBLE
                            binding.layoutIdle.visibility        = View.GONE
                            binding.layoutIncomingCall.visibility = View.GONE
                        }

                        PeerConnection.IceConnectionState.COMPLETED ->
                            updateStatus("Đang gọi - Đã kết nối", autoHide = true)

                        PeerConnection.IceConnectionState.DISCONNECTED ->
                            updateStatus("Mất tín hiệu, đang kết nối lại...")

                        PeerConnection.IceConnectionState.FAILED -> {
                            updateStatus("Kết nối thất bại")
                            Toast.makeText(
                                context,
                                "Không kết nối được. Kiểm tra mạng hoặc TURN server.",
                                Toast.LENGTH_LONG
                            ).show()
                            endCall(notifyRemote = true)
                        }

                        PeerConnection.IceConnectionState.CLOSED ->
                            endCall(notifyRemote = false)

                        else -> {}
                    }
                }
            }
            override fun onIceConnectionReceivingChange(b: Boolean) {}
            override fun onIceGatheringChange(s: PeerConnection.IceGatheringState?) {}

            override fun onIceCandidate(candidate: IceCandidate?) {
                candidate ?: return
                callHandler.post {
                    if (viewReleased || generation != callGeneration) return@post
                    callRef.child("caregiverCandidates").push().setValue(
                        mapOf(
                            "candidate"     to candidate.sdp,
                            "sdpMid"        to candidate.sdpMid,
                            "sdpMLineIndex" to candidate.sdpMLineIndex
                        )
                    )
                }
            }
            override fun onIceCandidatesRemoved(arr: Array<out IceCandidate>?) {}
            override fun onAddStream(s: MediaStream?) {}
            override fun onRemoveStream(s: MediaStream?) {}
            override fun onDataChannel(dc: DataChannel?) {}
            override fun onRenegotiationNeeded() {}

            override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {
                val track = receiver?.track() ?: return
                if (track is VideoTrack) {
                    activity?.runOnUiThread {
                        if (viewReleased || generation != callGeneration || _binding == null) return@runOnUiThread
                        remoteVideoTrack = track
                        track.addSink(binding.remoteSurfaceView)
                        binding.remoteSurfaceView.visibility = View.VISIBLE
                    }
                }
            }
        })
    }

    private fun addLocalTracks() {
        localAudioTrack?.let { peerConnection?.addTrack(it, listOf("CAREGIVER_STREAM")) }
        localVideoTrack?.let { peerConnection?.addTrack(it, listOf("CAREGIVER_STREAM")) }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Scenario 1: Caregiver là CALLER (gọi đến Robot)
    // ══════════════════════════════════════════════════════════════════════════

    private fun startCallAsCaregiver() {
        if (viewReleased || currentRole != null) return
        if (factory == null || !mediaReady) {
            Toast.makeText(context, "Đang khởi tạo WebRTC, vui lòng thử lại...", Toast.LENGTH_SHORT).show()
            return
        }
        currentRole = "caller"
        val generation = callGeneration

        binding.btnCallRobot.isEnabled = false
        binding.btnCallRobot.alpha = 0.5f
        binding.btnCallRobot.text = "Đang gọi..."
        updateStatus("Đang gọi Robot...")

        callRef.setValue(mapOf(
                "callerRole" to "caregiver",
                "status" to "calling"
            )).addOnCompleteListener {
                if (viewReleased || generation != callGeneration || currentRole != "caller" || peerConnection != null) return@addOnCompleteListener
                peerConnection = createPeerConnection() ?: return@addOnCompleteListener
                addLocalTracks()

            val constraints = MediaConstraints().apply {
                mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
                mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
            }

            peerConnection?.createOffer(makeSdpObserver(
                onCreateSuccess = { offer ->
                    peerConnection?.setLocalDescription(makeSdpObserver(
                        onSetSuccess = {
                            callRef.child("offer").setValue(
                                mapOf("type" to offer.type.canonicalForm(), "sdp" to offer.description)
                            )
                            listenForAnswer()
                            listenForRobotCandidates()
                            // Bat dau dem nguoc timeout 30s
                            callHandler.postDelayed(callTimeoutRunnable, CALL_TIMEOUT_MS)
                        }
                    ), offer)
                }
            ), constraints)
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Scenario 2: Caregiver là CALLEE (Robot gọi đến)
    // ══════════════════════════════════════════════════════════════════════════

    private fun showIncomingFromRobot() {
        if (hasHandledIncoming) return
        hasHandledIncoming = true
        activity?.runOnUiThread {
            showIncomingCallState()
            startRingtone()
        }
    }

    private fun acceptIncomingCall() {
        if (viewReleased || currentRole != null || isCallActive) return
        if (factory == null || !mediaReady) {
            autoAcceptPending = true
            return
        }
        stopRingtone()
        currentRole = "callee"
        showActiveCallState("Đang kết nối...")
        callHandler.postDelayed(callTimeoutRunnable, CALL_TIMEOUT_MS)

        peerConnection = createPeerConnection() ?: return
        addLocalTracks()

        val generation = callGeneration
        offerListener = callRef.child("offer").addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snap: DataSnapshot) {
                if (viewReleased || generation != callGeneration) return
                if (!snap.exists()) return
                callRef.child("offer").removeEventListener(this)
                offerListener = null

                val sdp  = snap.child("sdp").getValue(String::class.java)  ?: return
                val type = snap.child("type").getValue(String::class.java) ?: return
                val offer = SessionDescription(SessionDescription.Type.fromCanonicalForm(type), sdp)

                peerConnection?.setRemoteDescription(makeSdpObserver(
                    onSetSuccess = {
                        remoteDescSet = true
                        drainPendingIceCandidates()

                        peerConnection?.createAnswer(makeSdpObserver(
                            onCreateSuccess = { answer ->
                                peerConnection?.setLocalDescription(makeSdpObserver(
                                    onSetSuccess = {
                                        callRef.child("answer").setValue(
                                            mapOf("type" to answer.type.canonicalForm(), "sdp" to answer.description)
                                        )
                                        callRef.child("status").setValue("connected")
                                        activity?.runOnUiThread { updateStatus("Đang kết nối...") }
                                    }
                                ), answer)
                            }
                        ), MediaConstraints())
                    }
                ), offer)
            }
            override fun onCancelled(error: DatabaseError) {}
        })

        listenForRobotCandidates()
    }

    private fun declineIncomingCall() {
        autoAcceptPending = false
        stopRingtone()
        callRef.child("status").setValue("ended")
        hasHandledIncoming = false
        // Ghi nhật ký: cuộc gọi bị từ chối (Caregiver từ chối Robot gọi đến)
        writeCallHistory(status = "rejected", initiator = "robot", durationSec = 0)
        activity?.runOnUiThread { showIdleState() }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Firebase Signaling Listeners
    // ══════════════════════════════════════════════════════════════════════════

    private fun listenForCallState() {
        callNodeListener = callRef.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (viewReleased) return
                val status     = snapshot.child("status").getValue(String::class.java)
                val callerRole = snapshot.child("callerRole").getValue(String::class.java)

                if (status == "ended" || !snapshot.exists()) {
                    autoAcceptPending = false
                }

                when {
                    status == "calling" && callerRole == "robot" && currentRole == null ->
                        showIncomingFromRobot()

                    (status == "ended" || !snapshot.exists()) && (currentRole != null || isCallActive) ->
                        activity?.runOnUiThread { endCall(notifyRemote = false) }
                }
            }
            override fun onCancelled(e: DatabaseError) {
                Log.e(TAG, "listenForCallState cancelled: ${e.message}")
            }
        })
    }

    private fun listenForAnswer() {
        val generation = callGeneration
        answerListener = callRef.child("answer").addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (viewReleased || generation != callGeneration) return
                if (!snapshot.exists() || peerConnection?.remoteDescription != null) return
                val sdp  = snapshot.child("sdp").getValue(String::class.java)  ?: return
                val type = snapshot.child("type").getValue(String::class.java) ?: return
                val answer = SessionDescription(SessionDescription.Type.fromCanonicalForm(type), sdp)

                peerConnection?.setRemoteDescription(makeSdpObserver(
                    onSetSuccess = {
                        remoteDescSet = true
                        drainPendingIceCandidates()
                        callRef.child("status").setValue("connected")
                        updateStatus("Dang ket noi...")
                    }
                ), answer)
            }
            override fun onCancelled(e: DatabaseError) {}
        })
    }

    private fun listenForRobotCandidates() {
        val generation = callGeneration
        remoteCandidateListener = callRef.child("robotCandidates")
            .addChildEventListener(object : ChildEventListener {
                override fun onChildAdded(snapshot: DataSnapshot, prev: String?) {
                    if (viewReleased || generation != callGeneration) return
                    val sdp         = snapshot.child("candidate").getValue(String::class.java)  ?: return
                    val sdpMid      = snapshot.child("sdpMid").getValue(String::class.java)     ?: return
                    val sdpMLineIdx = snapshot.child("sdpMLineIndex").getValue(Int::class.java) ?: 0
                    val ice = IceCandidate(sdpMid, sdpMLineIdx, sdp)
                    if (remoteDescSet) peerConnection?.addIceCandidate(ice)
                    else pendingIceCandidates.add(ice)
                }
                override fun onChildChanged(s: DataSnapshot, p: String?) {}
                override fun onChildRemoved(s: DataSnapshot) {}
                override fun onChildMoved(s: DataSnapshot, p: String?) {}
                override fun onCancelled(e: DatabaseError) {}
            })
    }

    private fun drainPendingIceCandidates() {
        pendingIceCandidates.forEach { peerConnection?.addIceCandidate(it) }
        pendingIceCandidates.clear()
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Controls
    // ══════════════════════════════════════════════════════════════════════════

    private fun toggleMic() {
        isMicOn = !isMicOn
        localAudioTrack?.setEnabled(isMicOn)
        binding.btnToggleMic.setImageResource(
            if (isMicOn) R.drawable.vc_ic_mic_on else R.drawable.vc_ic_mic_off
        )
        binding.btnToggleMic.alpha = if (isMicOn) 1.0f else 0.5f
    }

    private fun toggleCamera() {
        isCamOn = !isCamOn
        localVideoTrack?.setEnabled(isCamOn)
        binding.btnToggleCamera.setImageResource(
            if (isCamOn) R.drawable.vc_ic_cam_on else R.drawable.vc_ic_cam_off
        )
        binding.btnToggleCamera.alpha = if (isCamOn) 1.0f else 0.5f
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Call End & Cleanup
    // ══════════════════════════════════════════════════════════════════════════

    private fun onCallTimeout() {
        Log.d(TAG, "Call timeout - khong phan hoi")
        Toast.makeText(context, "Cuộc gọi không phản hồi (30s)", Toast.LENGTH_LONG).show()
        endCall(notifyRemote = true)
    }

    private fun endCall(notifyRemote: Boolean) {
        autoAcceptPending = false
        if (currentRole == null && !isCallActive) return
        callHandler.removeCallbacks(callTimeoutRunnable)
        stopRingtone()
        sendStopCommand()

        // Ghi nhật ký cuộc gọi vào Firebase /call_history
        val durationSec = if (callStartMs > 0L)
            (System.currentTimeMillis() - callStartMs) / 1000L
        else 0L
        val initiator = if (currentRole == "caller") "caregiver" else "robot"
        writeCallHistory(
            status      = if (isCallActive) "ended" else "missed",
            initiator   = initiator,
            durationSec = durationSec
        )
        callStartMs = 0L

        activity?.runOnUiThread {
            binding.remoteSurfaceView.visibility = View.INVISIBLE
            binding.localVideoCard.visibility    = View.GONE
            showIdleState()
        }

        if (notifyRemote) callRef.child("status").setValue("ended")
        cleanupPeerConnection()
        cleanupFirebaseSignalingListeners()
    }

    /**
     * Ghi một bản ghi vào Firebase /call_history/{timestamp}.
     *
     * @param status      "ended" | "missed" | "rejected"
     * @param initiator   "caregiver" | "robot"
     * @param durationSec thời lượng tính bằng giây (0 nếu nhỡ/từ chối)
     */
    private fun writeCallHistory(status: String, initiator: String, durationSec: Long) {
        val nowMs = System.currentTimeMillis()
        callHistoryRef.push().setValue(
            mapOf(
                "timestamp"   to nowMs,
                "initiator"   to initiator,
                "durationSec" to durationSec,
                "status"      to status
            )
        ).addOnSuccessListener {
            Log.i(TAG, "Ghi lịch sử cuộc gọi OK: $initiator -> $status (${durationSec}s)")
        }.addOnFailureListener { e ->
            Log.e(TAG, "Ghi lịch sử thất bại: ${e.message}")
        }
    }

    private fun cleanupPeerConnection() {
        callGeneration++
        remoteDescSet = false
        pendingIceCandidates.clear()
        try {
            _binding?.remoteSurfaceView?.clearImage()
            _binding?.localSurfaceView?.clearImage()
        } catch (_: Exception) {}
        _binding?.remoteSurfaceView?.let { remoteVideoTrack?.removeSink(it) }
        remoteVideoTrack = null
        val oldPeer = peerConnection
        peerConnection = null
        isCallActive = false
        mediaExecutor.execute {
            try {
                oldPeer?.close()
                oldPeer?.dispose()
            } catch (e: Exception) {
                Log.e(TAG, "Cannot release peer connection", e)
            }
        }
    }

    private fun cleanupFirebaseSignalingListeners() {
        if (!::callRef.isInitialized) return
        offerListener?.let { callRef.child("offer").removeEventListener(it) }
        offerListener = null
        answerListener?.let { callRef.child("answer").removeEventListener(it) }
        answerListener = null
        remoteCandidateListener?.let { callRef.child("robotCandidates").removeEventListener(it) }
        remoteCandidateListener = null
    }

    @Suppress("DEPRECATION")
    private fun releaseAll() {
        endCall(notifyRemote = false)
        cleanupFirebaseSignalingListeners()
        // Also invalidate callbacks when the screen was idle, without an active call.
        cleanupPeerConnection()
        if (::callRef.isInitialized) callNodeListener?.let { callRef.removeEventListener(it) }
        callNodeListener = null

        // Snapshot ownership before clearing the fields. A later view must never be
        // released by this task. Camera2 stopCapture can wait for opening callbacks;
        // keeping the main looper free is essential while that wait is in progress.
        val oldCapturer = videoCapturer
        val oldVideoTrack = localVideoTrack
        val oldAudioTrack = localAudioTrack
        val oldVideoSource = localVideoSource
        val oldAudioSource = localAudioSource
        val oldHelper = captureHelper
        val oldFactory = factory
        val oldEgl = eglBase
        val oldLocalRenderer = _binding?.localSurfaceView
        val oldRemoteRenderer = _binding?.remoteSurfaceView
        oldLocalRenderer?.let { oldVideoTrack?.removeSink(it) }
        videoCapturer = null
        localVideoTrack = null
        localAudioTrack = null
        localVideoSource = null
        localAudioSource = null
        captureHelper = null
        factory = null
        eglBase = null
        mediaReady = false
        mediaExecutor.execute {
            fun releaseStep(name: String, action: () -> Unit) {
                try {
                    action()
                } catch (e: Exception) {
                    Log.e(TAG, "Media release failed: $name", e)
                }
            }
            Log.d(TAG, "Releasing media off the main thread")
            releaseStep("stop capture") { oldCapturer?.stopCapture() }
            releaseStep("capturer") { oldCapturer?.dispose() }
            releaseStep("video track") { oldVideoTrack?.dispose() }
            releaseStep("audio track") { oldAudioTrack?.dispose() }
            releaseStep("video source") { oldVideoSource?.dispose() }
            releaseStep("audio source") { oldAudioSource?.dispose() }
            releaseStep("capture helper") { oldHelper?.dispose() }
            releaseStep("factory") { oldFactory?.dispose() }
            releaseStep("local renderer") { oldLocalRenderer?.release() }
            releaseStep("remote renderer") { oldRemoteRenderer?.release() }
            releaseStep("EGL") { oldEgl?.release() }
            Log.d(TAG, "Media release complete")
        }
        try {
            audioManager?.mode = savedAudioMode
            audioManager?.isSpeakerphoneOn = savedSpeakerOn
        } catch (_: Exception) {}
        audioManager = null
    }

    // ══════════════════════════════════════════════════════════════════════════
    // SDP Observer Helper
    // ══════════════════════════════════════════════════════════════════════════

    private fun makeSdpObserver(
        onCreateSuccess: ((SessionDescription) -> Unit)? = null,
        onSetSuccess: (() -> Unit)? = null
    ): SdpObserver {
        val generation = callGeneration
        return object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription?) {
                callHandler.post {
                    if (!viewReleased && generation == callGeneration) {
                        sdp?.let { onCreateSuccess?.invoke(it) }
                    }
                }
            }
            override fun onSetSuccess() {
                callHandler.post {
                    if (!viewReleased && generation == callGeneration) onSetSuccess?.invoke()
                }
            }
            override fun onCreateFailure(err: String?) {
                Log.e(TAG, "SDP Create Failure: $err")
            }
            override fun onSetFailure(err: String?) {
                Log.e(TAG, "SDP Set Failure: $err")
            }
        }
    }
}
