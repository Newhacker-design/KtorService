
package com.example.ktorservice.database

import org.jetbrains.exposed.dao.id.IntIdTable

object DevicesTable : IntIdTable("devices") {

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
}
