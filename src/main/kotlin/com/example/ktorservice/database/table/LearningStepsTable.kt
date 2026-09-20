package com.example.ktorservice.database.table

import org.jetbrains.exposed.sql.Table

object LearningStepsTable : Table("learning_steps") {

    val id =
        integer("id")
            .autoIncrement()

    val pathId =
        integer("path_id")
            .references(LearningPathsTable.id)

    val stepOrder =
        integer("step_order")

    val title =
        varchar("title", 255)

    val skill =
        varchar("skill", 255)

    val description =
        text("description")
            .nullable()

    val prerequisiteStepId =
        integer("prerequisite_step_id")
            .nullable()

    val createdAt =
        long("created_at")

    override val primaryKey =
        PrimaryKey(id)
}