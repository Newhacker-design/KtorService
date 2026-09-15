package com.example.ktorservice.repository

import com.example.ktorservice.database.table.UserAvatarsTable
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

class AvatarRepository {

    fun save(userId: Int, data: ByteArray, contentType: String) {
        transaction {
            UserAvatarsTable.deleteWhere { UserAvatarsTable.userId eq userId }
            UserAvatarsTable.insert {
                it[UserAvatarsTable.userId] = userId
                it[imageData] = data
                it[UserAvatarsTable.contentType] = contentType
                it[updatedAt] = System.currentTimeMillis()
            }
        }
    }

    fun get(userId: Int): Pair<ByteArray, String>? {
        return transaction {
            UserAvatarsTable
                .selectAll()
                .where { UserAvatarsTable.userId eq userId }
                .firstOrNull()
                ?.let { row ->
                    row[UserAvatarsTable.imageData] to
                            row[UserAvatarsTable.contentType]
                }
        }
    }

    fun delete(userId: Int) {
        transaction {
            UserAvatarsTable.deleteWhere { UserAvatarsTable.userId eq userId }
        }
    }

    fun existsBatch(userIds: List<Int>): Set<Int> {
        if (userIds.isEmpty()) return emptySet()
        return transaction {
            UserAvatarsTable
                .selectAll()
                .where { UserAvatarsTable.userId inList userIds }
                .map { it[UserAvatarsTable.userId] }
                .toSet()
        }
    }
}