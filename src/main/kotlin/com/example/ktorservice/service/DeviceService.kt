
package com.example.ktorservice.service

import com.example.ktorservice.database.DevicesTable
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.jetbrains.exposed.sql.insertAndGetId

class DeviceService {

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

            val now = System.currentTimeMillis()

            /*
             * Tìm device đã tồn tại
             */
            val existing =
                DevicesTable
                    .selectAll()
                    .where {
                        (DevicesTable.userId eq userId) and
                                (DevicesTable.deviceId eq deviceId)
                    }
                    .singleOrNull()

            if (existing != null) {

                /*
                 * Device đã tồn tại
                 * -> cập nhật thông tin
                 */

                DevicesTable.update(
                    where = {
                        (DevicesTable.userId eq userId) and
                                (DevicesTable.deviceId eq deviceId)
                    }
                ) {

                    it[DevicesTable.deviceName] = deviceName

                    it[DevicesTable.appVersion] = appVersion

                    it[DevicesTable.lastSeen] = now

                    it[DevicesTable.status] = "ACTIVE"
                }

                /*
                 * IntIdTable.id trả về EntityID<Int>
                 * nên phải lấy .value
                 */
                return@transaction existing[
                    DevicesTable.id
                ].value
            }

            /*
             * Device mới
             */
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

            /*
             * inserted đã là EntityID<Int>
             * -> lấy .value để trả về Int
             */
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

