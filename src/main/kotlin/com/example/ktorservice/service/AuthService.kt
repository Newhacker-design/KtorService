package com.example.ktorservice.service

import com.example.ktorservice.database.SessionsTable
import com.example.ktorservice.database.UsersTable
import com.example.ktorservice.security.PasswordHasher
import com.example.ktorservice.security.TokenGenerator
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction

class AuthService {

    fun login(
        username: String,
        password: String
    ): LoginResult {

        return transaction {

            val user =
                UsersTable
                    .selectAll()
                    .where {
                        UsersTable.username eq username
                    }
                    .singleOrNull()

            if (user == null) {

                return@transaction LoginResult(
                    success = false,
                    message = "Invalid username or password"
                )
            }

            if (
                user[UsersTable.status]
                    .uppercase() != "ACTIVE"
            ) {

                return@transaction LoginResult(
                    success = false,
                    message = "Account disabled"
                )
            }

            val valid =
                PasswordHasher.verify(
                    password,
                    user[
                        UsersTable.passwordHash
                    ]
                )

            if (!valid) {

                return@transaction LoginResult(
                    success = false,
                    message = "Invalid username or password"
                )
            }

            val token =
                TokenGenerator.generate()

            val now =
                System.currentTimeMillis()

            val expiresAt =
                now +
                        30L *
                        24L *
                        60L *
                        60L *
                        1000L

            SessionsTable.insert {

                it[SessionsTable.userId] =
                    user[UsersTable.id]

                it[SessionsTable.token] =
                    token

                it[SessionsTable.createdAt] =
                    now

                it[SessionsTable.expiresAt] =
                    expiresAt
            }

            LoginResult(
                success = true,
                token = token,
                userId =
                    user[UsersTable.id]
            )
        }
    }

    fun getUserId(
        token: String
    ): Int? {

        val now =
            System.currentTimeMillis()

        return transaction {

            SessionsTable
                .selectAll()
                .where {
                    (SessionsTable.token eq token) and
                            (
                                    SessionsTable.expiresAt greater now
                                    )
                }
                .singleOrNull()
                ?.get(
                    SessionsTable.userId
                )
        }
    }
}

data class LoginResult(
    val success: Boolean,
    val token: String? = null,
    val userId: Int? = null,
    val message: String? = null
)