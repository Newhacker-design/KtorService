package com.example.ktorservice.repository

import com.example.ktorservice.model.*
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.LocalDate

class LeaderboardRepository {

    fun getLeaderboard(
        group: LeaderboardGroup,
        periodDays: Int = 7,
        minCompleted: Int = 1
    ): LeaderboardResponse {

        val sinceMillis =
            System.currentTimeMillis() -
                    periodDays.toLong() * 24 * 60 * 60 * 1000

        val gradeListSql = group.grades.joinToString(",")

        // Tính năm học bắt đầu ở phía Kotlin để nhúng vào SQL
        // (tránh phụ thuộc CURRENT_DATE của DB — dễ test hơn)
        val now = LocalDate.now()
        val schoolYearStart =
            if (now.monthValue > 9 ||
                (now.monthValue == 9 && now.dayOfMonth >= 5)
            ) now.year
            else now.year - 1

        /**
         * grade = schoolYearStart - birth_year - 5
         *
         * Ví dụ: schoolYearStart = 2025, birthYear = 2013
         *        grade = 2025 - 2013 - 5 = 7
         *
         * Hệ số lớp:
         *   LEAST(POWER(0.8, grade - a.grade), 1.5)
         */
        val sql = """
            WITH ranked AS (
                SELECT
                    ua.user_id,
                    u.name,
                    ($schoolYearStart - u.birth_year - 5) AS current_grade,
                    ua.score,
                    a.difficulty,
                    a.grade AS assignment_grade,
                    DATE(TO_TIMESTAMP(ua.completed_at / 1000.0)) AS day,
                    ROW_NUMBER() OVER (
                        PARTITION BY
                            ua.user_id,
                            DATE(TO_TIMESTAMP(ua.completed_at / 1000.0)),
                            a.difficulty
                        ORDER BY ua.completed_at
                    ) AS rank_in_day
                FROM user_assignments ua
                JOIN assignments a ON a.id = ua.assignment_id
                JOIN users u ON u.id = ua.user_id
                WHERE ua.status = 'COMPLETE'
                  AND ua.completed_at IS NOT NULL
                  AND ua.completed_at >= $sinceMillis
                  AND u.birth_year IS NOT NULL
                  AND ($schoolYearStart - u.birth_year - 5) BETWEEN 1 AND 12
            )
            SELECT
                user_id,
                name,
                current_grade AS grade,
                SUM(
                    score *
                    CASE difficulty
                        WHEN 'EASY'   THEN 0.8
                        WHEN 'MEDIUM' THEN 1.0
                        WHEN 'HARD'   THEN 1.5
                        ELSE 1.0
                    END
                    * LEAST(
                        POWER(0.8, current_grade - assignment_grade),
                        1.5
                      )
                    / rank_in_day
                ) AS total_score,
                COUNT(*) AS completed_count
            FROM ranked
            WHERE current_grade IN ($gradeListSql)
            GROUP BY user_id, name, current_grade
            HAVING COUNT(*) >= $minCompleted
            ORDER BY total_score DESC
            LIMIT 10
        """

        val entries = mutableListOf<LeaderboardEntry>()

        transaction {
            exec(sql) { rs ->
                var rank = 0
                while (rs.next()) {
                    rank++
                    entries.add(
                        LeaderboardEntry(
                            rank = rank,
                            userId = rs.getInt("user_id"),
                            name = rs.getString("name"),
                            grade = rs.getInt("grade"),
                            totalScore = rs.getDouble("total_score"),
                            completedCount = rs.getInt("completed_count")
                        )
                    )
                }
            }
        }

        return LeaderboardResponse(
            group = group.name,
            groupLabel = group.label,
            periodDays = periodDays,
            entries = entries
        )
    }

    /**
     * Lấy lớp hiện tại của user.
     * Tính từ birth_year, tự cập nhật mỗi năm.
     */
    fun getUserGrade(userId: Int): Int? {
        var birthYear: Int? = null

        transaction {
            exec(
                "SELECT birth_year FROM users WHERE id = $userId LIMIT 1"
            ) { rs ->
                if (rs.next()) {
                    val by = rs.getInt("birth_year")
                    birthYear = if (rs.wasNull()) null else by
                }
            }
        }

        val by = birthYear ?: return null

        val now = LocalDate.now()
        val schoolYearStart =
            if (now.monthValue > 9 ||
                (now.monthValue == 9 && now.dayOfMonth >= 5)
            ) now.year
            else now.year - 1

        val grade = schoolYearStart - by - 5
        return grade.coerceIn(1, 12)
    }
}