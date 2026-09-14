package com.example.ktorservice.repository

import com.example.ktorservice.WeekUtils
import com.example.ktorservice.model.*
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.LocalDate
import java.time.ZoneId

class LeaderboardRepository {

    fun getLeaderboard(
        group: LeaderboardGroup,
        weekOffset: Int = 0,      // 0 = tuần này, -1 = tuần trước
        minCompleted: Int = 1
    ): LeaderboardResponse {

        // Mốc đầu tuần theo giờ VN, không phải "7 ngày trước"
        val sinceMillis = WeekUtils.startOfWeekMillis(weekOffset)

        val gradeListSql = group.grades.joinToString(",")

        val now = LocalDate.now(ZoneId.of("Asia/Ho_Chi_Minh"))
        val schoolYearStart =
            if (now.monthValue > 9 ||
                (now.monthValue == 9 && now.dayOfMonth >= 5)
            ) now.year
            else now.year - 1

        /**
         * CÁCH B — rank_in_day tính RIÊNG cho mỗi độ khó.
         *
         * contribution = score
         *     × hệ_số_khó              (EASY=0.8, MEDIUM=1.0, HARD=1.5)
         *     × min(0.8^(lớp_hs − lớp_bài), 1.5)   ← gap lớp vẫn giữ nguyên
         *     ÷ rank_trong_ngày_theo_độ_khó
         *
         * Ví dụ 1 ngày làm: 3 dễ, 2 TB, 1 khó
         *   - dễ 1  → rank 1 → không chia
         *   - dễ 2  → rank 2 → chia 2
         *   - dễ 3  → rank 3 → chia 3
         *   - TB 1  → rank 1 → không chia
         *   - TB 2  → rank 2 → chia 2
         *   - khó 1 → rank 1 → không chia      ← KHÔNG bị ảnh hưởng bởi 5 bài trước
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
                AND ua.status IN ('COMPLETE', 'COMPLETED')
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
            weekLabel = WeekUtils.labelCurrentWeek(),
            entries = entries
        )
    }

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