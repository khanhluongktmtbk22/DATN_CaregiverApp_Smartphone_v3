package com.example.datn_caregiver

data class UserInfor(
    val userId: String? = null,
    val fullName: String? = null,
    val email: String? = null,
    val robotName: String? = null,
    val robotPronoun: String? = null,
    val elderlyPronoun: String? = null,
    val emergencyPhone: String? = null,
    val caregiverName: String? = null,
    val caregiverRelation: String? = null,
    val medicalConditions: String? = null,
    val specialNotes: String? = null,
    val hobbies: String? = null
)