package com.example.ktorservice.database.table

import org.jetbrains.exposed.sql.Table

object ViewedItems : Table("viewed_items") {

    val id = long("id").autoIncrement()

    val type = varchar("type", 20)

    val value = text("value")

    val createdAt = long("created_at")

    override val primaryKey = PrimaryKey(id)

    init {
        index(false, type)
        uniqueIndex(type, value)
    }
}