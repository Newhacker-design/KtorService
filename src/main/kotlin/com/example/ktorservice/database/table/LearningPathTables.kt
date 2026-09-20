package com.example.ktorservice.database.table

import org.jetbrains.exposed.sql.Table

object LearningPathsTable : Table("learning_paths") {

    val id =
        integer("id")
            .autoIncrement()

    val grade =
        integer("grade")

    val subject =
        varchar("subject", 100)

    val name =
        varchar("name", 255)

    val description =
        text("description")
            .nullable()

    val createdAt =
        long("created_at")

    override val primaryKey =
        PrimaryKey(id)
}