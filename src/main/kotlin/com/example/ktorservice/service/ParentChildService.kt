package com.example.ktorservice.service

import com.example.ktorservice.database.UsersTable
import com.example.ktorservice.database.table.ParentChildrenTable
import com.example.ktorservice.security.PasswordHasher
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction

class ParentChildService {

    companion object {
        private const val MAX_CHILDREN = 5
    }

    // ============================================================
    // REGISTER CHILD
    // ============================================================

    fun registerChild(
        parentUserId: Int,
        username: String,
        password: String
    ): RegisterChildResult {

        return transaction {

            // ====================================================
            // CHECK PARENT
            // ====================================================

            val parent =
                UsersTable
                    .selectAll()
                    .where {
                        UsersTable.id eq parentUserId
                    }
                    .singleOrNull()

            if (parent == null) {

                return@transaction RegisterChildResult(
                    success = false,
                    message = "Parent account not found"
                )
            }

            if (
                parent[UsersTable.status]
                    .uppercase() != "ACTIVE"
            ) {

                return@transaction RegisterChildResult(
                    success = false,
                    message = "Parent account is disabled"
                )
            }

            // ====================================================
            // CHECK MAX 5 CHILDREN
            // ====================================================

            val childCount =
                ParentChildrenTable
                    .selectAll()
                    .where {
                        ParentChildrenTable.parentUserId eq
                                parentUserId
                    }
                    .count()

            if (childCount >= MAX_CHILDREN) {

                return@transaction RegisterChildResult(
                    success = false,
                    message =
                        "Maximum $MAX_CHILDREN child accounts allowed"
                )
            }

            // ====================================================
            // CHECK USERNAME
            // ====================================================

            val existingUser =
                UsersTable
                    .selectAll()
                    .where {
                        UsersTable.username eq username
                    }
                    .singleOrNull()

            if (existingUser != null) {

                return@transaction RegisterChildResult(
                    success = false,
                    message = "Username already exists"
                )
            }

            // ====================================================
            // CREATE CHILD USER
            // ====================================================

            val passwordHash =
                PasswordHasher.hash(password)

            val now =
                System.currentTimeMillis()

            val insertStatement =
                UsersTable.insert {

                    it[UsersTable.username] =
                        username

                    it[UsersTable.passwordHash] =
                        passwordHash

                    it[UsersTable.status] =
                        "ACTIVE"

                    it[UsersTable.createdAt] =
                        now
                }

            val childUserId =
                insertStatement[
                    UsersTable.id
                ]

            // ====================================================
            // CREATE PARENT → CHILD RELATION
            // ====================================================

            ParentChildrenTable.insert {

                it[ParentChildrenTable.parentUserId] =
                    parentUserId

                it[ParentChildrenTable.childUserId] =
                    childUserId

                it[ParentChildrenTable.createdAt] =
                    now
            }

            RegisterChildResult(
                success = true,
                childUserId = childUserId,
                username = username,
                status = "ACTIVE"
            )
        }
    }

    // ============================================================
    // GET CHILDREN
    // ============================================================

    fun getChildren(
        parentUserId: Int
    ): List<ChildAccount> {

        return transaction {

            (ParentChildrenTable innerJoin UsersTable)
                .selectAll()
                .where {
                    ParentChildrenTable.parentUserId eq
                            parentUserId
                }
                .orderBy(
                    ParentChildrenTable.id to SortOrder.ASC
                )
                .map {

                    ChildAccount(
                        userId =
                            it[UsersTable.id],

                        username =
                            it[UsersTable.username],

                        status =
                            it[UsersTable.status]
                    )
                }
        }
    }

    // ============================================================
    // CHECK CHILD BELONGS TO PARENT
    // ============================================================

    fun isChildOfParent(
        parentUserId: Int,
        childUserId: Int
    ): Boolean {

        return transaction {

            ParentChildrenTable
                .selectAll()
                .where {
                    (ParentChildrenTable.parentUserId eq parentUserId) and
                            (ParentChildrenTable.childUserId eq childUserId)
                }
                .count() > 0
        }
    }

    // ============================================================
    // RESULT
    // ============================================================

    data class RegisterChildResult(
        val success: Boolean,
        val childUserId: Int? = null,
        val username: String? = null,
        val status: String? = null,
        val message: String? = null
    )

    data class ChildAccount(
        val userId: Int,
        val username: String,
        val status: String
    )
}