package com.example.ktorservice.config

import com.example.ktorservice.database.DevicesTable
import com.example.ktorservice.database.LicensesTable
import com.example.ktorservice.database.SessionsTable
import com.example.ktorservice.database.UsersTable
import com.example.ktorservice.database.table.AssignmentsTable
import com.example.ktorservice.database.table.LocationTable
import com.example.ktorservice.database.table.UserAssignmentsTable
import com.example.ktorservice.database.table.ViewedItems
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction

object DatabaseFactory {

    fun init() {

        val host =
            System.getenv("DB_HOST")
                ?: "localhost"

        val port =
            System.getenv("DB_PORT")
                ?: "5432"

        val database =
            System.getenv("DB_NAME")
                ?: "ktorservice"

        val user =
            System.getenv("DB_USER")
                ?: "ktoruser"

        val password =
            System.getenv("DB_PASSWORD")
                ?: error("DB_PASSWORD is not configured")

        println("========================================")
        println("DATABASE TYPE = PostgreSQL")
        println("DATABASE HOST = $host")
        println("DATABASE PORT = $port")
        println("DATABASE NAME = $database")
        println("DATABASE USER = $user")
        println("========================================")

        val config =
            HikariConfig().apply {

                driverClassName =
                    "org.postgresql.Driver"

                jdbcUrl =
                    "jdbc:postgresql://$host:$port/$database"

                username =
                    user

                this.password =
                    password

                maximumPoolSize =
                    5

                minimumIdle =
                    1

                isAutoCommit =
                    false

                connectionTimeout =
                    10_000

                validationTimeout =
                    5_000

                transactionIsolation =
                    "TRANSACTION_READ_COMMITTED"

                validate()
            }

        val dataSource =
            HikariDataSource(config)

        Database.connect(dataSource)

        transaction {

            SchemaUtils.create(
                ViewedItems,
                LocationTable,
                UsersTable,
                DevicesTable,
                LicensesTable,
                SessionsTable,
                AssignmentsTable,
                UserAssignmentsTable
            )
        }

        println("========================================")
        println("POSTGRESQL DATABASE INITIALIZED")
        println("========================================")
    }
}
