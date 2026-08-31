package com.example.ktorservice.database.table

import org.jetbrains.exposed.sql.Table

object UserAssignmentsTable : Table("user_assignments") {

    val id =
        integer("id")
            .autoIncrement()

    val userId =
        integer("user_id")

    val assignmentId =
        integer("assignment_id")

    val status =
        varchar("status", 20)
            .default("NEW")

    val answer =
        text("answer")
            .nullable()

    val score =
        double("score")
            .nullable()

    val feedback =
        text("feedback")
            .nullable()

    val startedAt =
        long("started_at")
            .nullable()

    val completedAt =
        long("completed_at")
            .nullable()

    override val primaryKey =
        PrimaryKey(id)

    init {
        uniqueIndex(
            "ux_user_assignments_user_assignment",
            userId,
            assignmentId
        )
    }
}