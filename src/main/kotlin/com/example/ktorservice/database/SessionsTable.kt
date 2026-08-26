package com.example.ktorservice.database

import org.jetbrains.exposed.sql.Table

object SessionsTable : Table("sessions") {

    val id =
        integer("id")
            .autoIncrement()

    val userId =
        integer("user_id")
            .index()

    val token =
        varchar(
            "token",
            255
        )
            .uniqueIndex()

    val createdAt =
        long("created_at")

    val expiresAt =
        long("expires_at")

    override val primaryKey =
        PrimaryKey(id)
}