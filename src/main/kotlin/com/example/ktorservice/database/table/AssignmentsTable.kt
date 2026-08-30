package com.example.ktorservice.database.table
import org.jetbrains.exposed.sql.Table

object AssignmentsTable : Table("assignments") {

    val id =
        integer("id")
            .autoIncrement()

    val grade =
        integer("grade")

    val subject =
        varchar(
            "subject",
            50
        )

    val topic =
        varchar(
            "topic",
            255
        )
            .nullable()

    val title =
        varchar(
            "title",
            500
        )

    val content =
        text("content")

    val answerKey =
        text("answer_key")

    val gradingGuide =
        text("grading_guide")

    val totalScore =
        double("total_score")
            .default(10.0)

    val createdAt =
        long("created_at")

    override val primaryKey =
        PrimaryKey(id)
}