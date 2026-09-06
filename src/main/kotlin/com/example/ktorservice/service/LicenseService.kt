package com.example.ktorservice.service

import com.example.ktorservice.database.LicensesTable
import com.example.ktorservice.database.UsersTable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction

class LicenseService {

    companion object {
        const val ROLE_ADMIN = "ADMIN"
        const val ROLE_PARENT = "PARENT"
        const val ROLE_CHILD = "CHILD"
    }

    fun getLicense(
        userId: Int,
        deviceId: Int
    ): LicenseResult {

        return transaction {

            // ============================================================
            // ADMIN
            // ============================================================
            //
            // ADMIN không cần license.
            // Không cần kiểm tra device/license trong database.
            //
            val user =
                UsersTable
                    .selectAll()
                    .where {
                        UsersTable.id eq userId
                    }
                    .singleOrNull()

            if (user == null) {

                return@transaction LicenseResult(
                    active = false,
                    message = "User not found"
                )
            }

            val role =
                user[UsersTable.role]
                    .uppercase()

            if (role == ROLE_ADMIN) {

                return@transaction LicenseResult(
                    active = true,
                    type = "ADMIN"
                )
            }

            // ============================================================
            // PARENT / CHILD
            // ============================================================

            val now =
                System.currentTimeMillis()

            val row =
                LicensesTable
                    .selectAll()
                    .where {
                        (LicensesTable.userId eq userId) and
                                (LicensesTable.deviceId eq deviceId)
                    }
                    .singleOrNull()

            if (row == null) {

                return@transaction LicenseResult(
                    active = false,
                    message = "No license"
                )
            }

            val status =
                row[LicensesTable.status]
                    .uppercase()

            val expiresAt =
                row[LicensesTable.expiresAt]

            val active =
                status == "ACTIVE" &&
                        expiresAt > now

            LicenseResult(
                active = active,
                licenseKey =
                    row[
                        LicensesTable.licenseKey
                    ],
                type =
                    row[
                        LicensesTable.type
                    ],
                expiresAt =
                    expiresAt,
                message =
                    if (active)
                        null
                    else
                        "License expired or disabled"
            )
        }
    }


    fun createLicense(
        userId: Int,
        deviceId: Int,
        type: String,
        durationDays: Int
    ): LicenseResult {

        return transaction {

            // Kiểm tra user tồn tại
            val user =
                UsersTable
                    .selectAll()
                    .where {
                        UsersTable.id eq userId
                    }
                    .singleOrNull()

            if (user == null) {

                return@transaction LicenseResult(
                    active = false,
                    message = "User not found"
                )
            }

            // ADMIN không cần license.
            // Không nên tạo license cho ADMIN.
            if (
                user[UsersTable.role]
                    .uppercase() == ROLE_ADMIN
            ) {

                return@transaction LicenseResult(
                    active = true,
                    type = "ADMIN",
                    message = "ADMIN does not require a license"
                )
            }

            val now =
                System.currentTimeMillis()

            val expiresAt =
                now +
                        durationDays.toLong() *
                        24L *
                        60L *
                        60L *
                        1000L

            val licenseKey =
                generateLicenseKey()

            LicensesTable.insert {

                it[LicensesTable.userId] =
                    userId

                it[LicensesTable.deviceId] =
                    deviceId

                it[LicensesTable.licenseKey] =
                    licenseKey

                it[LicensesTable.type] =
                    type

                it[LicensesTable.expiresAt] =
                    expiresAt

                it[LicensesTable.status] =
                    "ACTIVE"

                it[LicensesTable.createdAt] =
                    now
            }

            LicenseResult(
                active = true,
                licenseKey = licenseKey,
                type = type,
                expiresAt = expiresAt
            )
        }
    }
}


data class LicenseResult(
    val active: Boolean,
    val licenseKey: String? = null,
    val type: String? = null,
    val expiresAt: Long? = null,
    val message: String? = null
)


private fun generateLicenseKey(): String {

    return java.util.UUID
        .randomUUID()
        .toString()
        .uppercase()
        .replace("-", "")
        .chunked(5)
        .take(4)
        .joinToString("-")
}
