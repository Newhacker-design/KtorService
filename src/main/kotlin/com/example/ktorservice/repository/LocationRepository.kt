package com.example.ktorservice.repository

import com.example.ktorservice.database.table.LocationTable
import com.example.ktorservice.model.LocationRequest
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.transactions.transaction

class LocationRepository {

    fun insert(
        location: LocationRequest
    ) {

        transaction {

            LocationTable.insert {

                it[deviceName] =
                    location.deviceName

                it[latitude] =
                    location.latitude

                it[longitude] =
                    location.longitude

                it[timestamp] =
                    location.timestamp
            }

            /*
             * Mỗi device chỉ giữ
             * tối đa 50 location.
             */

            val idsToDelete =
                LocationTable
                    .select(
                        LocationTable.id
                    )
                    .where {
                        LocationTable.deviceName eq
                                location.deviceName
                    }
                    .orderBy(
                        LocationTable.timestamp to SortOrder.DESC
                    )
                    .drop(50)
                    .map {
                        it[LocationTable.id]
                    }

            if (idsToDelete.isNotEmpty()) {

                LocationTable.deleteWhere {

                    LocationTable.id inList idsToDelete
                }
            }
        }
    }

    fun getAll(): List<LocationRequest> {

        return transaction {

            LocationTable
                .selectAll()
                .orderBy(
                    LocationTable.timestamp to
                            SortOrder.DESC
                )
                .map {

                    LocationRequest(

                        latitude =
                            it[LocationTable.latitude],

                        longitude =
                            it[LocationTable.longitude],

                        deviceName =
                            it[LocationTable.deviceName],

                        timestamp =
                            it[LocationTable.timestamp]
                    )
                }
        }
    }

    fun getByDevice(
        deviceName: String
    ): List<LocationRequest> {

        return transaction {

            LocationTable
                .selectAll()
                .where {
                    LocationTable.deviceName eq deviceName
                }
                .orderBy(
                    LocationTable.timestamp to SortOrder.DESC
                )
                .map {

                    LocationRequest(

                        latitude =
                            it[LocationTable.latitude],

                        longitude =
                            it[LocationTable.longitude],

                        deviceName =
                            it[LocationTable.deviceName],

                        timestamp =
                            it[LocationTable.timestamp]
                    )
                }
        }
    }
}