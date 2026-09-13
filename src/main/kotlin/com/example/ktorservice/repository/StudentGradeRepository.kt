package com.example.ktorservice.repository

import com.example.ktorservice.database.UsersTable
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.LocalDate

class StudentGradeRepository {

    /**
     * Tính lớp hiện tại từ năm sinh.
     *
     * Năm học VN bắt đầu 5/9.
     * Vào lớp 1 lúc 6 tuổi.
     */
    fun computeGrade(birthYear: Int): Int {
        val now = LocalDate.now()

        val schoolYearStart =
            if (now.monthValue > 9 ||
                (now.monthValue == 9 && now.dayOfMonth >= 5)
            ) now.year
            else now.year - 1

        val grade = schoolYearStart - (birthYear + 6) + 1
        return grade.coerceIn(1, 12)
    }

    fun validateBirthYear(birthYear: Int): Boolean {
        val currentYear = LocalDate.now().year
        val age = currentYear - birthYear
        // Cho phép 5-12 tuổi (tiểu học có biên độ)
        return age in 5..12
    }

    /**
     * Lấy lớp của user.
     * Trả về null nếu user chưa nhập năm sinh.
     */
    fun getGrade(userId: Int): Int? {
        var grade: Int? = null

        transaction {
            UsersTable
                .selectAll()
                .where { UsersTable.id eq userId }
                .limit(1)
                .forEach { row ->
                    grade = row[UsersTable.grade]
                }
        }

        return grade
    }

    /**
     * Lưu lớp của user.
     */
    fun setGrade(userId: Int, grade: Int) {
        transaction {
            UsersTable.update({ UsersTable.id eq userId }) {
                it[UsersTable.grade] = grade
            }
        }
    }
}