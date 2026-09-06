package com.example.ktorservice.database

import org.jetbrains.exposed.sql.Table

object VideosTable : Table("videos") {

    val id = integer("id").autoIncrement()

    val childUserId =
        integer("child_user_id").index()

    val fileName =
        varchar("file_name", 255).uniqueIndex()

    val fileSize =
        long("file_size")

    val createdAt =
        long("created_at")

    override val primaryKey =
        PrimaryKey(id)
}
