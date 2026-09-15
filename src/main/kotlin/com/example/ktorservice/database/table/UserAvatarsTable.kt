package com.example.ktorservice.database.table

import com.example.ktorservice.database.UsersTable
import org.jetbrains.exposed.sql.Table

object UserAvatarsTable : Table("user_avatars") {
    val userId = integer("user_id").references(UsersTable.id)
    val imageData = binary("image_data")
    val contentType = varchar("content_type", 50).default("image/jpeg")
    val updatedAt = long("updated_at")

    override val primaryKey = PrimaryKey(userId)
}