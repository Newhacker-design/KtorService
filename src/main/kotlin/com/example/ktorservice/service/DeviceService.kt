package com.example.ktorservice.service

import com.example.ktorservice.database.DevicesTable
import com.example.ktorservice.database.UsersTable
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update

class DeviceService {

    companion object {

        private const val ROLE_ADMIN = "ADMIN"
        private const val ROLE_PARENT = "PARENT"
        private const val ROLE_CHILD = "CHILD"

        private const val MAX_DEVICES_PER_USER = 1
    }


    /*
     * ============================================================
     * REGISTER / UPDATE DEVICE
     * ============================================================
     */

    fun registerDevice(
        userId: Int,
        deviceId: String,
        deviceName: String,
        appVersion: String
    ): Int {

        return transaction {

            val now =
                System.currentTimeMillis()


            // ====================================================
            // Kiểm tra User
            // ====================================================

            val user =
                UsersTable
                    .selectAll()
                    .where {
                        UsersTable.id eq userId
                    }
                    .singleOrNull()
                    ?: throw IllegalStateException(
                        "User not found"
                    )


            val role =
                user[UsersTable.role]
                    .uppercase()


            // ====================================================
            // User phải ACTIVE
            // ====================================================

            if (
                user[UsersTable.status]
                    .uppercase() != "ACTIVE"
            ) {

                throw IllegalStateException(
                    "Account is disabled"
                )
            }


            // ====================================================
            // Tìm device hiện tại của chính user
            //
            // Nếu đã tồn tại:
            // -> update
            // -> không tính là device mới
            // ====================================================

            val existing =
                DevicesTable
                    .selectAll()
                    .where {
                        (DevicesTable.userId eq userId) and
                                (DevicesTable.deviceId eq deviceId)
                    }
                    .singleOrNull()


            if (existing != null) {

                DevicesTable.update(
                    where = {
                        (DevicesTable.userId eq userId) and
                                (DevicesTable.deviceId eq deviceId)
                    }
                ) {

                    it[DevicesTable.deviceName] =
                        deviceName

                    it[DevicesTable.appVersion] =
                        appVersion

                    it[DevicesTable.lastSeen] =
                        now

                    it[DevicesTable.status] =
                        "ACTIVE"
                }


                return@transaction existing[
                    DevicesTable.id
                ].value
            }


            // ====================================================
            // Device này đã thuộc user khác?
            //
            // Nếu có -> từ chối
            // ====================================================

            val existingOtherUser =
                DevicesTable
                    .selectAll()
                    .where {
                        (DevicesTable.deviceId eq deviceId) and
                                (DevicesTable.userId neq userId)
                    }
                    .singleOrNull()


            if (existingOtherUser != null) {

                throw IllegalStateException(
                    "Device is already registered to another user"
                )
            }


            // ====================================================
            // ADMIN
            //
            // Không giới hạn số device
            // ====================================================

            if (role != ROLE_ADMIN) {

                // =================================================
                // PARENT / CHILD
                //
                // Chỉ được có tối đa 1 device
                // =================================================

                if (
                    role != ROLE_PARENT &&
                    role != ROLE_CHILD
                ) {

                    throw IllegalStateException(
                        "Invalid user role"
                    )
                }


                val deviceCount =
                    DevicesTable
                        .selectAll()
                        .where {
                            DevicesTable.userId eq userId
                        }
                        .count()


                if (
                    deviceCount >=
                    MAX_DEVICES_PER_USER
                ) {

                    throw IllegalStateException(
                        "Maximum $MAX_DEVICES_PER_USER device allowed for this account"
                    )
                }
            }


            // ====================================================
            // Device mới
            // ====================================================

            val inserted =
                DevicesTable.insertAndGetId {

                    it[DevicesTable.userId] =
                        userId

                    it[DevicesTable.deviceId] =
                        deviceId

                    it[DevicesTable.deviceName] =
                        deviceName

                    it[DevicesTable.appVersion] =
                        appVersion

                    it[DevicesTable.lastSeen] =
                        now

                    it[DevicesTable.status] =
                        "ACTIVE"

                    it[DevicesTable.createdAt] =
                        now
                }


            inserted.value
        }
    }


    /*
     * ============================================================
     * UPDATE LAST SEEN
     * ============================================================
     */

    fun updateLastSeen(
        userId: Int,
        deviceId: String
    ) {

        transaction {

            DevicesTable.update(
                where = {
                    (DevicesTable.userId eq userId) and
                            (DevicesTable.deviceId eq deviceId)
                }
            ) {

                it[DevicesTable.lastSeen] =
                    System.currentTimeMillis()

                it[DevicesTable.status] =
                    "ACTIVE"
            }
        }
    }


    /*
     * ============================================================
     * GET DEVICES OF USER
     * ============================================================
     */

    fun getDevices(
        userId: Int
    ): List<DeviceInfo> {

        return transaction {

            DevicesTable
                .selectAll()
                .where {
                    DevicesTable.userId eq userId
                }
                .map {

                    DeviceInfo(

                        id =
                            it[DevicesTable.id].value,

                        userId =
                            it[DevicesTable.userId],

                        deviceId =
                            it[DevicesTable.deviceId],

                        deviceName =
                            it[DevicesTable.deviceName],

                        appVersion =
                            it[DevicesTable.appVersion],

                        lastSeen =
                            it[DevicesTable.lastSeen],

                        status =
                            it[DevicesTable.status],

                        createdAt =
                            it[DevicesTable.createdAt]
                    )
                }
        }
    }


    /*
     * ============================================================
     * GET ONE DEVICE
     * ============================================================
     */

    fun getDevice(
        userId: Int,
        deviceId: String
    ): DeviceInfo? {

        return transaction {

            DevicesTable
                .selectAll()
                .where {
                    (DevicesTable.userId eq userId) and
                            (DevicesTable.deviceId eq deviceId)
                }
                .singleOrNull()
                ?.let {

                    DeviceInfo(

                        id =
                            it[DevicesTable.id].value,

                        userId =
                            it[DevicesTable.userId],

                        deviceId =
                            it[DevicesTable.deviceId],

                        deviceName =
                            it[DevicesTable.deviceName],

                        appVersion =
                            it[DevicesTable.appVersion],

                        lastSeen =
                            it[DevicesTable.lastSeen],

                        status =
                            it[DevicesTable.status],

                        createdAt =
                            it[DevicesTable.createdAt]
                    )
                }
        }
    }


    /*
     * ============================================================
     * DISABLE DEVICE
     * ============================================================
     */

    fun disableDevice(
        userId: Int,
        deviceId: String
    ): Boolean {

        return transaction {

            val updated =
                DevicesTable.update(
                    where = {
                        (DevicesTable.userId eq userId) and
                                (DevicesTable.deviceId eq deviceId)
                    }
                ) {

                    it[DevicesTable.status] =
                        "DISABLED"

                    it[DevicesTable.lastSeen] =
                        System.currentTimeMillis()
                }

            updated > 0
        }
    }
}


/*
 * ================================================================
 * DEVICE DTO
 * ================================================================
 */

data class DeviceInfo(

    val id: Int,

    val userId: Int,

    val deviceId: String,

    val deviceName: String,

    val appVersion: String,

    val lastSeen: Long,

    val status: String,

    val createdAt: Long
)
