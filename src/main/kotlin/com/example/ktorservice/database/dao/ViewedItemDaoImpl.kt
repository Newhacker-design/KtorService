package com.example.ktorservice.database.dao

import com.example.ktorservice.database.table.ViewedItems
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteAll
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq

class ViewedItemDaoImpl : ViewedItemDao {

    override suspend fun getAllIds(): List<Long> =
        withContext(Dispatchers.IO) {
            transaction {
                ViewedItems
                    .selectAll()
                    .where { ViewedItems.type eq "id" }
                    .map { it[ViewedItems.value].toLong() }
            }
        }

    override suspend fun getAllHashes(): List<String> =
        withContext(Dispatchers.IO) {
            transaction {
                ViewedItems
                    .selectAll()
                    .where { ViewedItems.type eq "hash" }
                    .map { it[ViewedItems.value] }
            }
        }

    override suspend fun insertId(id: Long) {

        withContext(Dispatchers.IO) {

            transaction {

                val exists =
                    ViewedItems
                        .selectAll()
                        .where {
                            (ViewedItems.type eq "id") and
                                    (ViewedItems.value eq id.toString())
                        }
                        .any()

                if (exists) {
                    return@transaction
                }

                ViewedItems.insert {

                    it[type] = "id"
                    it[value] = id.toString()
                    it[createdAt] =
                        System.currentTimeMillis()
                }
            }
        }
    }
    override suspend fun insertIds(ids: List<Long>) {

        if (ids.isEmpty()) {
            return
        }

        withContext(Dispatchers.IO) {

            transaction {

                ids
                    .distinct()
                    .forEach { id ->

                        val exists =
                            ViewedItems
                                .selectAll()
                                .where {
                                    (ViewedItems.type eq "id") and
                                            (ViewedItems.value eq id.toString())
                                }
                                .any()

                        if (!exists) {

                            ViewedItems.insert {

                                it[type] = "id"
                                it[value] = id.toString()
                                it[createdAt] =
                                    System.currentTimeMillis()
                            }
                        }
                    }
            }
        }
    }
    override suspend fun insertHash(hash: String) {

        withContext(Dispatchers.IO) {

            transaction {

                val exists =
                    ViewedItems
                        .selectAll()
                        .where {
                            (ViewedItems.type eq "hash") and
                                    (ViewedItems.value eq hash)
                        }
                        .any()

                if (exists) {
                    return@transaction
                }

                ViewedItems.insert {

                    it[type] = "hash"
                    it[value] = hash
                    it[createdAt] =
                        System.currentTimeMillis()
                }
            }
        }
    }

    override suspend fun existsId(id: Long): Boolean =
        withContext(Dispatchers.IO) {
            transaction {
                ViewedItems
                    .selectAll()
                    .where {
                        (ViewedItems.type eq "id") and
                                (ViewedItems.value eq id.toString())
                    }
                    .any()
            }
        }

    override suspend fun existsHash(hash: String): Boolean =
        withContext(Dispatchers.IO) {
            transaction {
                ViewedItems
                    .selectAll()
                    .where {
                        (ViewedItems.type eq "hash") and
                                (ViewedItems.value eq hash)
                    }
                    .any()
            }
        }

    override suspend fun clear() {
        withContext(Dispatchers.IO) {
            transaction {
                ViewedItems.deleteAll()
            }
        }
    }
}