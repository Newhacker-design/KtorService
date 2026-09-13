package com.example.ktorservice

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

object WeekUtils {

    private val VN_ZONE = ZoneId.of("Asia/Ho_Chi_Minh")

    /**
     * Trả về epoch millis của 00:00:00 thứ 2 tuần hiện tại (giờ VN).
     *
     * Ví dụ: hôm nay thứ 5 ngày 18/09/2025
     *  → trả về 00:00:00 thứ 2 ngày 15/09/2025
     */
    fun startOfCurrentWeekMillis(): Long {
        val today = LocalDate.now(VN_ZONE)
        val monday = today.with(
            TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)
        )
        return monday
            .atStartOfDay(VN_ZONE)
            .toInstant()
            .toEpochMilli()
    }

    /**
     * Đầu tuần với offset (tuần trước = -1, tuần này = 0).
     */
    fun startOfWeekMillis(weekOffset: Int = 0): Long {
        val today = LocalDate.now(VN_ZONE)
        val monday = today
            .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            .plusWeeks(weekOffset.toLong())
        return monday
            .atStartOfDay(VN_ZONE)
            .toInstant()
            .toEpochMilli()
    }

    /**
     * Nhãn hiển thị "15/09 - 21/09/2025".
     */
    fun labelCurrentWeek(): String {
        val today = LocalDate.now(VN_ZONE)
        val monday = today.with(
            TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)
        )
        val sunday = monday.plusDays(6)
        return "%02d/%02d - %02d/%02d/%d".format(
            monday.dayOfMonth, monday.monthValue,
            sunday.dayOfMonth, sunday.monthValue,
            sunday.year
        )
    }
}