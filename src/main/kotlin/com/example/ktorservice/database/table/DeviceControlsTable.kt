package com.example.ktorservice.database

import org.jetbrains.exposed.sql.Table

object DeviceControlsTable : Table("device_controls") {

    val childUserId =
        integer("child_user_id")
            .uniqueIndex()

    val command =
        varchar(
            "command",
            20
        )

    val text =
        text(
            "text"
        ).nullable()

    val videoUrl =
        text(
            "video_url"
        ).nullable()

    val version =
        long(
            "version"
        )

    val updatedAt =
        long(
            "updated_at"
        )

    override val primaryKey =
        PrimaryKey(childUserId)
}