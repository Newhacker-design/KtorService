package com.example.ktorservice.config

import com.example.ktorservice.database.table.LocationTable
import com.example.ktorservice.database.table.ViewedItems
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction
import java.io.File

object DatabaseFactory {

    fun init() {

        val databasePath =
            System.getenv("DATABASE_PATH")
                ?: "data/database.db"

        val databaseFile =
            File(databasePath)

        databaseFile.parentFile?.mkdirs()

        println("========================================")
        println("DATABASE PATH = ${databaseFile.absolutePath}")
        println("DATABASE EXISTS = ${databaseFile.exists()}")
        println("========================================")

        val config =
            HikariConfig().apply {

                driverClassName =
                    "org.sqlite.JDBC"

                jdbcUrl =
                    "jdbc:sqlite:${databaseFile.absolutePath}"

                maximumPoolSize = 5

                isAutoCommit = false

                transactionIsolation =
                    "TRANSACTION_SERIALIZABLE"

                validate()
            }

        val dataSource =
            HikariDataSource(config)

        Database.connect(dataSource)

        transaction {

            SchemaUtils.create(
                ViewedItems,
                LocationTable
            )
        }

        println("========== DATABASE DEBUG ==========")
        println("DATABASE_PATH ENV = ${System.getenv("DATABASE_PATH")}")
        println("DATABASE ABSOLUTE PATH = ${databaseFile.absolutePath}")
        println("DATABASE EXISTS = ${databaseFile.exists()}")
        println("DATABASE SIZE = ${if (databaseFile.exists()) databaseFile.length() else 0}")
        println("====================================")
    }
}