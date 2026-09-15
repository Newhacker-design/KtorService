package com.example.ktorservice.repository

import com.example.ktorservice.WeekUtils
import com.example.ktorservice.model.*

import org.jetbrains.exposed.sql.statements.StatementType
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.LocalDate
import java.time.ZoneId

class LeaderboardRepository {

    fun getLeaderboard(
        group: LeaderboardGroup,
        weekOffset: Int = 0,
        minCompleted: Int = 1
    ): LeaderboardResponse {

        val sinceMillis = WeekUtils.startOfWeekMillis(weekOffset)
        val gradeListSql = group.grades.joinToString(",")

        val now = LocalDate.now(ZoneId.of("Asia/Ho_Chi_Minh"))
        val schoolYearStart =
            if (now.monthValue > 9 ||
                (now.monthValue == 9 && now.dayOfMonth >= 5)
            ) now.year
            else now.year - 1

        val sql = buildString {
            appendLine("WITH ranked AS (")
            appendLine("    SELECT")
            appendLine("        ua.user_id,")
            appendLine("        u.username,")
            appendLine("        ($schoolYearStart - u.birth_year - 5) AS current_grade,")
            appendLine("        ua.score,")
            appendLine("        a.difficulty,")
            appendLine("        a.grade AS assignment_grade,")
            appendLine("        ROW_NUMBER() OVER (")
            appendLine("            PARTITION BY")
            appendLine("                ua.user_id,")
            appendLine("                DATE(TO_TIMESTAMP(ua.completed_at / 1000.0)),")
            appendLine("                a.difficulty")
            appendLine("            ORDER BY ua.completed_at")
            appendLine("        ) AS rank_in_day")
            appendLine("    FROM user_assignments ua")
            appendLine("    JOIN assignments a ON a.id = ua.assignment_id")
            appendLine("    JOIN users u ON u.id = ua.user_id")
            appendLine("    WHERE ua.status IN ('COMPLETE', 'COMPLETED')")
            appendLine("      AND ua.completed_at IS NOT NULL")
            appendLine("      AND ua.completed_at >= $sinceMillis")
            appendLine("      AND u.birth_year IS NOT NULL")
            appendLine("      AND ($schoolYearStart - u.birth_year - 5) BETWEEN 1 AND 12")
            appendLine(")")
            appendLine("SELECT")
            appendLine("    ranked.user_id,")
            appendLine("    ranked.username,")
            appendLine("    ranked.current_grade AS grade,")
            appendLine("    SUM(")
            appendLine("        ranked.score *")
            appendLine("        CASE ranked.difficulty")
            appendLine("            WHEN 'EASY'   THEN 0.8")
            appendLine("            WHEN 'MEDIUM' THEN 1.0")
            appendLine("            WHEN 'HARD'   THEN 1.5")
            appendLine("            ELSE 1.0")
            appendLine("        END")
            appendLine("        * LEAST(POWER(0.8, ranked.current_grade - ranked.assignment_grade), 1.5)")
            appendLine("        / ranked.rank_in_day")
            appendLine("    ) AS total_score,")
            appendLine("    COUNT(*) AS completed_count,")
            appendLine("    (av.user_id IS NOT NULL) AS has_avatar")
            appendLine("FROM ranked")
            appendLine("LEFT JOIN user_avatars av ON av.user_id = ranked.user_id")
            appendLine("WHERE ranked.current_grade IN ($gradeListSql)")
            appendLine("GROUP BY ranked.user_id, ranked.username, ranked.current_grade, av.user_id")
            appendLine("HAVING COUNT(*) >= $minCompleted")
            appendLine("ORDER BY total_score DESC")
            appendLine("LIMIT 10")
        }

        val entries = mutableListOf<LeaderboardEntry>()

        transaction {
            exec(
                sql,
                explicitStatementType = StatementType.SELECT
            ) { rs ->
                var rank = 0
                while (rs.next()) {
                    rank++
                    entries.add(
                        LeaderboardEntry(
                            rank = rank,
                            userId = rs.getInt("user_id"),
                            name = rs.getString("username"),
                            grade = rs.getInt("grade"),
                            totalScore = rs.getDouble("total_score"),
                            completedCount = rs.getInt("completed_count"),
                            hasAvatar = rs.getBoolean("has_avatar")
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
                "SELECT birth_year FROM users WHERE id = $userId LIMIT 1",
                explicitStatementType = StatementType.SELECT
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