package com.example.ktorservice.database

import org.jetbrains.exposed.sql.Table

object DevicesTable : Table("devices") {

    val id =
        integer("id")
            .autoIncrement()

    val userId =
        integer("user_id")
            .index()

    val deviceId =
        varchar(
            "device_id",
            255
        )

    val deviceName =
        varchar(
            "device_name",
            255
        )

    val appVersion =
        varchar(
            "app_version",
            50
        )

    val lastSeen =
        long("last_seen")

    val status =
        varchar(
            "status",
            20
        )
            .default("ACTIVE")

    val createdAt =
        long("created_at")

    override val primaryKey =
        PrimaryKey(id)
}