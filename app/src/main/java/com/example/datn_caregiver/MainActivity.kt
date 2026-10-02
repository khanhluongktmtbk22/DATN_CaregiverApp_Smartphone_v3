package com.example.datn_caregiver

import android.app.NotificationManager
import android.content.Intent
import android.os.Bundle
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.fragment.app.Fragment
import android.view.View
import com.example.datn_caregiver.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private var selectedTabId = R.id.btnNavProfile

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Khoi dong service lang nghe cuoc goi Robot khi app chay nen
        IncomingCallService.start(this)

        if (savedInstanceState != null) {
            selectedTabId = savedInstanceState.getInt("selected_tab_id", R.id.btnNavProfile)
        }

        // Mac dinh mo ProfileFragment
        if (savedInstanceState == null && intent?.getStringExtra("navigate_to") != "video_call") {
            replaceFragment(ProfileFragment())
            updateSidebarUI(binding.btnNavProfile)
            // Mở sẵn sidebar trong lần đầu vào app để hướng dẫn người dùng
            // Sử dụng post để đảm bảo view đã được render xong trước khi gọi lệnh mở
            binding.drawerLayout.post {
                binding.drawerLayout.openDrawer(binding.sidebar)
            }
        } else {
            // Restore UI state for sidebar
            findViewById<LinearLayout>(selectedTabId)?.let {
                updateSidebarUI(it)
            }
        }

        setupNavigation()

        // Xu ly Intent tu IncomingCallActivity (khi bam "Nhan" tren man hinh khoa)
        handleIncomingIntent(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("selected_tab_id", selectedTabId)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    override fun onPostResume() {
        super.onPostResume()
        // A notification can arrive after FragmentManager has saved its state.
        handleIncomingIntent(intent)
    }

    /**
     * Xu ly Intent navigate_to="video_call" voi auto_accept=true:
     * Chuyen sang VideoCallFragment va bao fragment tu dong nhan cuoc goi.
     */
    private fun handleIncomingIntent(intent: Intent?) {
        if (supportFragmentManager.isStateSaved) return
        if (intent?.getStringExtra("navigate_to") == "video_call") {
            // Ngay lap tuc tat thong bao cuoc goi de UI khong bi "do"
            val nm = getSystemService(android.content.Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.cancel(1001) // IncomingCallService.NOTIF_ID_CALL

            val autoAccept = intent.getBooleanExtra("auto_accept", false)
            // Consume before navigation so recreation cannot accept the same intent again.
            intent.removeExtra("navigate_to")
            intent.removeExtra("auto_accept")
            val current = supportFragmentManager.findFragmentById(R.id.content_frame)
            if (current is VideoCallFragment) {
                if (autoAccept) current.requestAcceptIncomingCall()
            } else {
                val fragment = VideoCallFragment().apply {
                    arguments = Bundle().apply {
                        putBoolean("auto_accept", autoAccept)
                    }
                }
                supportFragmentManager.beginTransaction()
                    .replace(R.id.content_frame, fragment)
                    .commitNow()
            }
            binding.drawerLayout.closeDrawers()
            updateSidebarUI(binding.btnNavVideo)
        }
    }

    private fun setupNavigation() {
        binding.btnNavProfile.setOnClickListener { onSidebarItemSelected(ProfileFragment(), it as LinearLayout) }
        binding.btnNavTracking.setOnClickListener { onSidebarItemSelected(TrackingFragment(), it as LinearLayout) }
        binding.btnNavVideo.setOnClickListener { onSidebarItemSelected(VideoCallFragment(), it as LinearLayout) }
        binding.btnNavSchedule.setOnClickListener { onSidebarItemSelected(ScheduleFragment(), it as LinearLayout) }
        binding.btnNavDiary.setOnClickListener { onSidebarItemSelected(DiaryFragment(), it as LinearLayout) }

        // Remove scrim shadow and elevation
        binding.drawerLayout.setScrimColor(android.graphics.Color.TRANSPARENT)
        binding.drawerLayout.drawerElevation = 0f

        // Add animation for main content when drawer slides
        binding.drawerLayout.addDrawerListener(object : DrawerLayout.DrawerListener {
            override fun onDrawerSlide(drawerView: View, slideOffset: Float) {
                // Set pivot to the right edge so it shrinks from the left
                binding.mainContentCard.pivotX = binding.mainContentCard.width.toFloat()
                binding.mainContentCard.pivotY = binding.mainContentCard.height / 2f

                // Calculate the scale needed to exactly fit the drawer
                val slideX = drawerView.width * slideOffset
                val scale = if (binding.mainContentCard.width > 0) {
                    1f - (slideX / binding.mainContentCard.width)
                } else 1f

                binding.mainContentCard.scaleX = scale
                binding.mainContentCard.scaleY = 1f // Do not shrink vertically
                binding.mainContentCard.translationX = 0f // Pivot handles the positioning
            }

            override fun onDrawerOpened(drawerView: View) {}
            override fun onDrawerClosed(drawerView: View) {}
            override fun onDrawerStateChanged(newState: Int) {}
        })
    }

    private fun onSidebarItemSelected(fragment: Fragment, item: LinearLayout) {
        if (item.id == selectedTabId) {
            binding.drawerLayout.closeDrawers()
            return
        }
        replaceFragment(fragment)
        updateSidebarUI(item)
        binding.drawerLayout.closeDrawers()
    }

    private fun replaceFragment(fragment: Fragment) {
        supportFragmentManager.beginTransaction()
            .replace(R.id.content_frame, fragment)
            .commitAllowingStateLoss()
    }

    private fun updateSidebarUI(selectedItem: LinearLayout) {
        selectedTabId = selectedItem.id
        val menuItems = listOf(
            binding.btnNavProfile, binding.btnNavTracking,
            binding.btnNavVideo,
            binding.btnNavSchedule, binding.btnNavDiary
        )
        val selectedColor = ContextCompat.getColor(this, R.color.color_sidebar_selected)
        menuItems.forEach { item ->
            if (item == selectedItem) {
                item.setBackgroundColor(selectedColor)
                item.alpha = 1.0f
            } else {
                item.setBackgroundColor(0)
                item.alpha = 0.6f
            }
        }
    }
}
