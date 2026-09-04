package com.example.ktorservice.database.table

import org.jetbrains.exposed.sql.Table

object ParentChildrenTable : Table("parent_children") {

    val id =
        integer("id")
            .autoIncrement()

    val parentUserId =
        integer("parent_user_id")
            .index()

    val childUserId =
        integer("child_user_id")
            .uniqueIndex()

    val createdAt =
        long("created_at")

    override val primaryKey =
        PrimaryKey(id)

    init {

        uniqueIndex(
            "uq_parent_child",
            parentUserId,
            childUserId
        )
    }
}