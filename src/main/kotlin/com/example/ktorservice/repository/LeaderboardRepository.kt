package com.example.ktorservice.repository

import com.example.ktorservice.model.*
import org.jetbrains.exposed.sql.transactions.transaction

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

        /**
         * Hệ số lớp:
         *   LEAST(POWER(0.8, u.grade - a.grade), 1.5)
         *
         * Ví dụ:
         *   u.grade=1, a.grade=1 → 0.8^0  = 1.00
         *   u.grade=6, a.grade=1 → 0.8^5  = 0.33
         *   u.grade=1, a.grade=6 → 0.8^-5 = 3.05 → cap về 1.5
         *
         * User chưa nhập năm sinh (u.grade IS NULL) → hệ số 1.0
         * (không bị loại khỏi BXH, chỉ là không được thưởng).
         */
        val sql = """
            WITH ranked AS (
                SELECT
                    ua.user_id,
                    u.name,
                    u.grade,
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
                  AND u.grade IS NOT NULL
                  AND u.grade IN ($gradeListSql)
            )
            SELECT
                user_id,
                name,
                grade,
                SUM(
                    score *
                    CASE difficulty
                        WHEN 'EASY'   THEN 0.8
                        WHEN 'MEDIUM' THEN 1.0
                        WHEN 'HARD'   THEN 1.5
                        ELSE 1.0
                    END
                    * LEAST(
                        POWER(0.8, grade - assignment_grade),
                        1.5
                      )
                    / rank_in_day
                ) AS total_score,
                COUNT(*) AS completed_count
            FROM ranked
            GROUP BY user_id, name, grade
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
     * Lấy lớp của user để route /leaderboard/my-group biết khối nào.
     */
    fun getUserGrade(userId: Int): Int? {
        var grade: Int? = null

        transaction {
            exec("SELECT grade FROM users WHERE id = $userId LIMIT 1") { rs ->
                if (rs.next()) {
                    val g = rs.getInt("grade")
                    grade = if (rs.wasNull()) null else g
                }
            }
        }

        return grade
    }
}