package com.example.ktorservice.model

import kotlinx.serialization.Serializable

@Serializable
enum class LeaderboardGroup(
    val label: String,
    val grades: List<Int>
) {
    GRADE_1_2("Khối 1-2", listOf(1, 2)),
    GRADE_3_4("Khối 3-4", listOf(3, 4)),
    GRADE_5_6("Khối 5-6", listOf(5, 6)),
    GRADE_7_9("Khối 7-9", listOf(7, 8, 9)),
    GRADE_10_12("Khối 10-12", listOf(10, 11, 12));

    companion object {
        fun fromGrade(grade: Int): LeaderboardGroup = when (grade) {
            in 1..2 -> GRADE_1_2
            in 3..4 -> GRADE_3_4
            in 5..6 -> GRADE_5_6
            in 7..9 -> GRADE_7_9
            else -> GRADE_10_12
        }

        fun fromKey(key: String): LeaderboardGroup? =
            entries.firstOrNull { it.name == key }
    }
}

@Serializable
data class LeaderboardEntry(
    val rank: Int,
    val userId: Int,
    val name: String,
    val grade: Int,
    val totalScore: Double,
    val completedCount: Int,
    val hasAvatar: Boolean = false
)

@Serializable
data class LeaderboardResponse(
    val success: Boolean = true,
    val group: String,
    val groupLabel: String,
    val weekLabel: String? = null,          // "15/09 - 21/09/2025"
    val entries: List<LeaderboardEntry> = emptyList(),
    val message: String? = null
)