package com.example.datn_caregiver

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.example.datn_caregiver.databinding.FragmentDiaryBinding
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator

/**
 * DiaryFragment — Nhat Ky
 *
 * Hien thi 3 tab thong qua ViewPager2:
 *   Tab 0 — Cuộc gọi  : lịch sử tất cả cuộc gọi video (Firebase /call_history)
 *   Tab 1 — Nhac Nho  : log loi nhac cua Robot (Firebase /reminder_logs/{uid})
 *   Tab 2 — Canh Bao  : su kien te nga do Robot App phat hien (Firebase /fall_alerts)
 *
 * Du lieu duoc cap nhat realtime nho Firebase ValueEventListener trong moi sub-fragment.
 */
class DiaryFragment : Fragment(R.layout.fragment_diary) {

    private var _binding: FragmentDiaryBinding? = null
    private val binding get() = _binding!!

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentDiaryBinding.bind(view)

        setupViewPager()
    }

    private fun setupViewPager() {
        val pagerAdapter = DiaryPagerAdapter(requireActivity())
        binding.viewPagerDiary.adapter = pagerAdapter

        TabLayoutMediator(binding.tabLayoutDiary, binding.viewPagerDiary) { tab, position ->
            tab.text = when (position) {
                0 -> "Cuộc gọi"
                1 -> "Nhắc nhở"
                2 -> "Cảnh báo"
                else -> ""
            }
        }.attach()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    // ── ViewPager2 Adapter ────────────────────────────────────────────────────
    private inner class DiaryPagerAdapter(activity: FragmentActivity)
        : FragmentStateAdapter(activity) {

        override fun getItemCount() = 3

        override fun createFragment(position: Int): Fragment = when (position) {
            0 -> DiaryCallsFragment()
            1 -> DiaryRemindersFragment()
            2 -> DiaryAlertsFragment()
            else -> DiaryCallsFragment()
        }
    }
}
