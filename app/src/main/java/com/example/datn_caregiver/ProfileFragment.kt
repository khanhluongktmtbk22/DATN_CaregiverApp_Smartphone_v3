package com.example.datn_caregiver

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.core.content.edit
import androidx.fragment.app.Fragment
import com.example.datn_caregiver.databinding.FragmentProfileBinding
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase

class ProfileFragment : Fragment(R.layout.fragment_profile) {

    private var _binding: FragmentProfileBinding? = null
    private val binding get() = _binding!!
    private lateinit var auth: FirebaseAuth
    private lateinit var database: DatabaseReference
    private var isEditMode = false

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        _binding = FragmentProfileBinding.bind(view)
        auth = FirebaseAuth.getInstance()
        database = FirebaseDatabase.getInstance().getReference("Users")

        // Set email & tên NGAY LẬP TỨC từ FirebaseAuth (không chờ DB)
        // Vì login yêu cầu isEmailVerified, currentUser.email chắc chắn không null
        binding.tvProfileEmail.text = auth.currentUser?.email ?: ""
        binding.tvProfileName.text = "Đang tải..."

        // Load toàn bộ thông tin profile từ Realtime Database
        loadUserProfile()

        binding.btnUpdateProfile.setOnClickListener {
            if (isEditMode) saveUserProfile() else enableEditMode()
        }

        binding.btnLogout.setOnClickListener { performLogout() }
    }

    // ────────────────────────────────────────────────
    // Load dữ liệu
    // ────────────────────────────────────────────────

    private fun loadUserProfile() {
        val uid = auth.currentUser?.uid ?: return

        database.child(uid).get()
            .addOnSuccessListener { snapshot ->
                val b = _binding ?: return@addOnSuccessListener   // Fragment đã destroy

                val user = snapshot.getValue(UserInfor::class.java)

                // Tên: ưu tiên fullName lưu lúc đăng ký trong DB
                b.tvProfileName.text = user?.fullName
                    ?.takeIf { it.isNotBlank() } ?: "Người dùng"

                // Email: nếu DB có thì hiện, fallback về Auth email (luôn có)
                val emailFromDb = user?.email?.takeIf { it.isNotBlank() }
                b.tvProfileEmail.text = emailFromDb ?: (auth.currentUser?.email ?: "")

                // Điền các trường profile (cho phép rỗng)
                b.edtRobotName.setText(user?.robotName ?: "")
                b.edtRobotPronoun.setText(user?.robotPronoun ?: "")
                b.edtElderlyPronoun.setText(user?.elderlyPronoun ?: "")
                b.edtCaregiverName.setText(user?.caregiverName ?: "")
                b.edtCaregiverRelation.setText(user?.caregiverRelation ?: "")
                b.edtEmergencyPhone.setText(user?.emergencyPhone ?: "")
                b.edtMedicalConditions.setText(user?.medicalConditions ?: "")
                b.edtSpecialNotes.setText(user?.specialNotes ?: "")
                b.edtHobbies.setText(user?.hobbies ?: "")
            }
            .addOnFailureListener { e ->
                if (isAdded) {
                    Toast.makeText(requireContext(), "Lỗi tải thông tin: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
    }

    // ────────────────────────────────────────────────
    // Chế độ Chỉnh sửa / Lưu
    // ────────────────────────────────────────────────

    private fun enableEditMode() {
        isEditMode = true
        binding.btnUpdateProfile.text = "Lưu hồ sơ"
        setFieldsEditable(true)
    }

    private fun saveUserProfile() {
        val uid = auth.currentUser?.uid ?: return

        // Không bắt buộc — cho phép lưu kể cả khi để trống hoặc điền dở
        val updates = mapOf<String, Any>(
            "robotName"         to binding.edtRobotName.text.toString().trim(),
            "robotPronoun"      to binding.edtRobotPronoun.text.toString().trim(),
            "elderlyPronoun"    to binding.edtElderlyPronoun.text.toString().trim(),
            "caregiverName"     to binding.edtCaregiverName.text.toString().trim(),
            "caregiverRelation" to binding.edtCaregiverRelation.text.toString().trim(),
            "emergencyPhone"    to binding.edtEmergencyPhone.text.toString().trim(),
            "medicalConditions" to binding.edtMedicalConditions.text.toString().trim(),
            "specialNotes"      to binding.edtSpecialNotes.text.toString().trim(),
            "hobbies"           to binding.edtHobbies.text.toString().trim()
        )

        binding.btnUpdateProfile.isEnabled = false

        database.child(uid).updateChildren(updates)
            .addOnCompleteListener { task ->
                val b = _binding ?: return@addOnCompleteListener  // Fragment đã destroy
                b.btnUpdateProfile.isEnabled = true

                if (task.isSuccessful) {
                    // Luôn gọi disableEditMode() trước — không phụ thuộc isAdded
                    disableEditMode()
                    if (isAdded) {
                        Toast.makeText(requireContext(), "Lưu hồ sơ thành công!", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    if (isAdded) {
                        Toast.makeText(requireContext(), "Lưu thất bại: ${task.exception?.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
    }

    private fun disableEditMode() {
        isEditMode = false
        binding.btnUpdateProfile.text = "Chỉnh sửa hồ sơ"
        setFieldsEditable(false)
    }

    private fun setFieldsEditable(editable: Boolean) {
        binding.edtRobotName.isEnabled = editable
        binding.edtRobotPronoun.isEnabled = editable
        binding.edtElderlyPronoun.isEnabled = editable
        binding.edtCaregiverName.isEnabled = editable
        binding.edtCaregiverRelation.isEnabled = editable
        binding.edtEmergencyPhone.isEnabled = editable
        binding.edtMedicalConditions.isEnabled = editable
        binding.edtSpecialNotes.isEnabled = editable
        binding.edtHobbies.isEnabled = editable
    }

    // ────────────────────────────────────────────────
    // Đăng xuất
    // ────────────────────────────────────────────────

    private fun performLogout() {
        // Remove the listener bound to the old UID before FirebaseAuth changes account.
        IncomingCallService.stop(requireContext())
        auth.signOut()

        requireActivity().getSharedPreferences("LoginPrefs", Context.MODE_PRIVATE)
            .edit { clear() }

        Toast.makeText(requireContext(), "Đã đăng xuất tài khoản", Toast.LENGTH_SHORT).show()

        val intent = Intent(requireContext(), LoginActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        startActivity(intent)
        requireActivity().finish()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
