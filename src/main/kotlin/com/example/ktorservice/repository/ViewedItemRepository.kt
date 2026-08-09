package com.example.ktorservice.repository

import com.example.ktorservice.database.dao.ViewedItemDao

class ViewedItemRepository(
    private val dao: ViewedItemDao
) {

    suspend fun getAllIds(): List<Long> =
        dao.getAllIds()

    suspend fun getAllHashes(): List<String> =
        dao.getAllHashes()

    suspend fun insertId(id: Long) =
        dao.insertId(id)
    suspend fun insertIds(ids: List<Long>) =
        dao.insertIds(ids)
    suspend fun insertHash(hash: String) =
        dao.insertHash(hash)

    suspend fun existsId(id: Long): Boolean =
        dao.existsId(id)

    suspend fun existsHash(hash: String): Boolean =
        dao.existsHash(hash)

    suspend fun clear() =
        dao.clear()
}