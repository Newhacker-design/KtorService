package com.example.ktorservice.database.dao

interface ViewedItemDao {

    suspend fun getAllIds(): List<Long>

    suspend fun getAllHashes(): List<String>

    suspend fun insertId(id: Long)
    suspend fun insertIds(ids: List<Long>)
    suspend fun insertHash(hash: String)

    suspend fun existsId(id: Long): Boolean

    suspend fun existsHash(hash: String): Boolean

    suspend fun clear()
}