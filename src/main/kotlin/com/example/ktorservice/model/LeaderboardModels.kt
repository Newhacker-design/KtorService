package com.example.ktorservice.model

import kotlinx.serialization.Serializable

@Serializable
enum class LeaderboardGroup(
    val label: String,
    val grades: List<Int>
) {
    GRADE_1_2("Khối 1-2", listOf(1, 2)),
    GRADE_3_4("Khối 3-4", listOf(3, 4)),
    GRADE_5("Khối 5", listOf(5));

    companion object {
        fun fromGrade(grade: Int): LeaderboardGroup = when (grade) {
            in 1..2 -> GRADE_1_2
            in 3..4 -> GRADE_3_4
            else -> GRADE_5
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
    val completedCount: Int
)

@Serializable
data class LeaderboardResponse(
    val success: Boolean = true,
    val group: String,
    val groupLabel: String,
    val periodDays: Int,
    val entries: List<LeaderboardEntry> = emptyList(),
    val message: String? = null
)