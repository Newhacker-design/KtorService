package com.example.ktorservice.config

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.jetbrains.exposed.sql.Database
import java.io.File

object DatabaseFactory {

    fun init() {

        val databaseUrl =
            System.getenv("DATABASE_URL")

        if (!databaseUrl.isNullOrBlank()) {

            initPostgres(databaseUrl)

        } else {

            initSQLite()
        }
    }

    /**
     * ============================================
     * LOCAL
     * ============================================
     */
    private fun initSQLite() {

        val databaseFile =
            File("data/database.db")

        println("========================================")
        println("DATABASE = SQLITE")
        println("DATABASE PATH = ${databaseFile.absolutePath}")
        println("DATABASE EXISTS = ${databaseFile.exists()}")
        println("========================================")

        databaseFile.parentFile?.mkdirs()

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

        println("========================================")
        println("SQLITE CONNECTED SUCCESSFULLY")
        println("========================================")
    }

    /**
     * ============================================
     * RENDER / POSTGRESQL
     * ============================================
     */
    private fun initPostgres(
        databaseUrl: String
    ) {

        println("========================================")
        println("DATABASE = POSTGRESQL")
        println("========================================")

        val config =
            HikariConfig().apply {

                jdbcUrl = databaseUrl

                driverClassName =
                    "org.postgresql.Driver"

                maximumPoolSize = 5

                isAutoCommit = false

                transactionIsolation =
                    "TRANSACTION_READ_COMMITTED"

                validate()
            }

        val dataSource =
            HikariDataSource(config)

        Database.connect(dataSource)

        println("========================================")
        println("POSTGRESQL CONNECTED SUCCESSFULLY")
        println("========================================")
    }
}