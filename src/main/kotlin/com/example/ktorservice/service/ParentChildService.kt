package com.example.ktorservice.service

import com.example.ktorservice.database.UsersTable
import com.example.ktorservice.database.table.ParentChildrenTable
import com.example.ktorservice.security.PasswordHasher
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction

class ParentChildService {

    companion object {
         const val MAX_CHILDREN = 5

         const val ROLE_ADMIN = "ADMIN"
         const val ROLE_PARENT = "PARENT"
         const val ROLE_CHILD = "CHILD"
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

            // ====================================================
            // CHECK PARENT STATUS
            // ====================================================

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
            // CHECK PARENT ROLE
            // ====================================================

            val parentRole =
                parent[UsersTable.role]
                    .uppercase()

            /*
             * Chỉ PARENT hoặc ADMIN được phép tạo Child.
             */
            if (
                parentRole != ROLE_PARENT &&
                parentRole != ROLE_ADMIN
            ) {

                return@transaction RegisterChildResult(
                    success = false,
                    message = "Only parent or admin can create child accounts"
                )
            }

            // ====================================================
            // CHECK MAX CHILDREN
            // ====================================================

            /*
             * ADMIN không bị giới hạn số Child.
             *
             * PARENT tối đa 5 Child.
             */
            if (parentRole != ROLE_ADMIN) {

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

                    /*
                     * BẮT BUỘC:
                     * User được tạo ở đây là CHILD.
                     */
                    it[UsersTable.role] =
                        ROLE_CHILD

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

            val childUserIds =
                ParentChildrenTable
                    .select(
                        ParentChildrenTable.childUserId
                    )
                    .where {
                        ParentChildrenTable.parentUserId eq
                                parentUserId
                    }
                    .orderBy(
                        ParentChildrenTable.id to SortOrder.ASC
                    )
                    .map {
                        it[ParentChildrenTable.childUserId]
                    }

            if (childUserIds.isEmpty()) {
                return@transaction emptyList()
            }

            UsersTable
                .selectAll()
                .where {
                    UsersTable.id inList childUserIds
                }
                .associateBy {
                    it[UsersTable.id]
                }
                .let { usersById ->

                    childUserIds.mapNotNull { childUserId ->

                        val user =
                            usersById[childUserId]
                                ?: return@mapNotNull null

                        ChildAccount(
                            userId =
                                user[UsersTable.id],

                            username =
                                user[UsersTable.username],

                            status =
                                user[UsersTable.status]
                        )
                    }
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
