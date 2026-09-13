package com.example.ktorservice.database

import org.jetbrains.exposed.sql.Table

object UsersTable : Table("users") {

    val id =
        integer("id")
            .autoIncrement()

    val username =
        varchar(
            "username",
            100
        )
            .uniqueIndex()

    val passwordHash =
        varchar(
            "password_hash",
            255
        )

    val role =
        varchar(
            "role",
            20
        )
            .default("PARENT")

    val status =
        varchar(
            "status",
            20
        )
            .default("ACTIVE")

    val createdAt =
        long(
            "created_at"
        )
    // THÊM DÒNG NÀY
    val grade = integer("grade").nullable()

    override val primaryKey =
        PrimaryKey(id)
}