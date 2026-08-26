package com.example.ktorservice.database

import org.jetbrains.exposed.sql.Table

object LicensesTable : Table("licenses") {

    val id =
        integer("id")
            .autoIncrement()

    val userId =
        integer("user_id")
            .index()

    val deviceId =
        integer("device_id")
            .index()

    val licenseKey =
        varchar(
            "license_key",
            100
        )
            .uniqueIndex()

    val type =
        varchar(
            "type",
            30
        )

    val expiresAt =
        long(
            "expires_at"
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