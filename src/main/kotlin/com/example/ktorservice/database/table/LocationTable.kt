package com.example.ktorservice.database.table

import org.jetbrains.exposed.sql.Table

object LocationTable : Table("locations") {

    val id =
        long("id")
            .autoIncrement()

    val deviceName =
        varchar(
            "device_name",
            255
        )

    val latitude =
        double("latitude")

    val longitude =
        double("longitude")

    val timestamp =
        long("timestamp")

    override val primaryKey =
        PrimaryKey(id)
}