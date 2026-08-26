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

    override val primaryKey =
        PrimaryKey(id)
}