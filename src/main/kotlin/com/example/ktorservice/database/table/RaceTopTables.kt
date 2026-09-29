package com.example.ktorservice.database.table

import org.jetbrains.exposed.sql.Table

object RaceTopSessionsTable : Table("race_top_sessions") {
    val id = integer("id").autoIncrement()
    val userId = integer("user_id")
    val grade = integer("grade")
    val status = varchar("status", 20).default("ACTIVE")
    val createdAt = long("created_at")
    val weakSubjects = text("weak_subjects").nullable()
    val averageSubjects = text("average_subjects").nullable()
    val strongSubjects = text("strong_subjects").nullable()
    override val primaryKey = PrimaryKey(id)
}

object RaceTopSessionAssignmentsTable : Table("race_top_session_assignments") {
    val id = integer("id").autoIncrement()
    val sessionId = integer("session_id")
    val userAssignmentId = integer("user_assignment_id")
    val subject = varchar("subject", 100)
    override val primaryKey = PrimaryKey(id)
}
