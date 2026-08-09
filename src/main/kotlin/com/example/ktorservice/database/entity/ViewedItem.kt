package com.example.ktorservice.database.entity


data class ViewedItem(
    val id: Long,
    val type: String,
    val value: String,
    val createdAt: Long
)