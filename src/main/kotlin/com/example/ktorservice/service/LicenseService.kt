package com.example.ktorservice.service

import com.example.ktorservice.database.LicensesTable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction

class LicenseService {

    fun getLicense(
        userId: Int,
        deviceId: Int
    ): LicenseResult {

        val now =
            System.currentTimeMillis()

        return transaction {

            val row =
                LicensesTable
                    .selectAll()
                    .where {
                        (LicensesTable.userId eq userId) and
                                (
                                        LicensesTable.deviceId eq deviceId
                                        )
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
}

data class LicenseResult(
    val active: Boolean,
    val licenseKey: String? = null,
    val type: String? = null,
    val expiresAt: Long? = null,
    val message: String? = null
)