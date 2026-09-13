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
     * Năm học VN bắt đầu 5/9. Vào lớp 1 lúc 6 tuổi.
     *
     * Hàm này LUÔN cho ra lớp hiện tại dựa vào ngày hôm nay.
     * Sang năm sau, gọi lại sẽ tự ra lớp mới.
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
        return age in 5..12
    }

    /**
     * Lấy lớp HIỆN TẠI của user, tính từ birth_year.
     * Tự cập nhật mỗi năm — không cần lưu grade vào DB.
     */
    fun getGrade(userId: Int): Int? {
        val birthYear = getBirthYear(userId) ?: return null
        return computeGrade(birthYear)
    }

    fun getBirthYear(userId: Int): Int? {
        var birthYear: Int? = null

        transaction {
            UsersTable
                .selectAll()
                .where { UsersTable.id eq userId }
                .limit(1)
                .forEach { row ->
                    birthYear = row[UsersTable.birthYear]
                }
        }

        return birthYear
    }

    /**
     * Lưu năm sinh. Không lưu lớp.
     */
    fun setBirthYear(userId: Int, birthYear: Int) {
        transaction {
            UsersTable.update({ UsersTable.id eq userId }) {
                it[UsersTable.birthYear] = birthYear
            }
        }
    }
}