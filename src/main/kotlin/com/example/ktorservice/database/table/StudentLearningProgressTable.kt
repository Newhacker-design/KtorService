package com.example.ktorservice.database.table

import org.jetbrains.exposed.sql.Table

object StudentLearningProgressTable : Table("student_learning_progress") {

    val id =
        integer("id")
            .autoIncrement()

    val userId =
        integer("user_id")

    val stepId =
        integer("step_id")
            .references(LearningStepsTable.id)

    val masteryScore =
        double("mastery_score")
            .default(0.0)

    val attemptCount =
        integer("attempt_count")
            .default(0)

    val lastScore =
        double("last_score")
            .nullable()

    val status =
        varchar("status", 30)
            .default("NOT_STARTED")

    val lastAttemptAt =
        long("last_attempt_at")
            .nullable()

    init {
        index(
            isUnique = true,
            columns = arrayOf(userId, stepId)
        )
    }

    override val primaryKey =
        PrimaryKey(id)
}