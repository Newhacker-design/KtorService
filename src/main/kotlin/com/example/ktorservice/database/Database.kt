package com.example.ktorservice.database

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import java.io.File
import java.sql.Connection

object Database {

    private lateinit var dataSource: HikariDataSource

    fun init() {

        val dataDir = File("data")

        if (!dataDir.exists()) {
            dataDir.mkdirs()
        }

        val dbFile = File(dataDir, "app.db")

        val config = HikariConfig().apply {

            jdbcUrl = "jdbc:sqlite:${dbFile.absolutePath}"

            driverClassName = "org.sqlite.JDBC"

            maximumPoolSize = 5

            minimumIdle = 1

            isAutoCommit = true

            connectionTimeout = 30000

            idleTimeout = 600000

            maxLifetime = 1800000

            poolName = "KtorSQLitePool"

        }

        dataSource = HikariDataSource(config)

        createTables()
    }

    fun connection(): Connection {
        return dataSource.connection
    }

    private fun createTables() {

        connection().use { conn ->

            conn.createStatement().use { stmt ->

                stmt.execute(
                    """
                    CREATE TABLE IF NOT EXISTS viewed_items
                    (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,

                        type TEXT NOT NULL,

                        value TEXT NOT NULL,

                        created_at INTEGER NOT NULL
                    )
                    """.trimIndent()
                )

            }

        }

    }

    fun close() {

        if (::dataSource.isInitialized) {

            dataSource.close()

        }

    }

}