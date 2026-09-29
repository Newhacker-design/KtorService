package com.example.ktorservice.config

import com.example.ktorservice.database.DeviceControlsTable
import com.example.ktorservice.database.VideosTable
import com.example.ktorservice.database.DevicesTable
import com.example.ktorservice.database.LicensesTable
import com.example.ktorservice.database.SessionsTable
import com.example.ktorservice.database.UsersTable
import com.example.ktorservice.database.table.AssignmentsTable
import com.example.ktorservice.database.table.LearningPathsTable
import com.example.ktorservice.database.table.LearningStepsTable
import com.example.ktorservice.database.table.LocationTable
import com.example.ktorservice.database.table.ParentChildrenTable
import com.example.ktorservice.database.table.StudentLearningProgressTable
import com.example.ktorservice.database.table.UserAssignmentsTable
import com.example.ktorservice.database.table.ViewedItems
import com.example.ktorservice.service.DefaultMathLearningPathSeeder
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
                UserAssignmentsTable,
                LearningPathsTable,
                LearningStepsTable,
                StudentLearningProgressTable,
                ParentChildrenTable,
                VideosTable,

                // NEW
                DeviceControlsTable
            )

            // Additive migration for databases created before learning-step pooling.
            exec(
                "ALTER TABLE assignments " +
                        "ADD COLUMN IF NOT EXISTS learning_step_id INTEGER NULL"
            )
            exec(
                "ALTER TABLE user_assignments " +
                        "ADD COLUMN IF NOT EXISTS mode VARCHAR(20) NOT NULL DEFAULT 'PRACTICE'"
            )

            // Reuse past path-generated assignments when their step association is unambiguous.
            exec(
                "UPDATE assignments AS a " +
                        "SET learning_step_id = linked.learning_step_id " +
                        "FROM (" +
                        "SELECT assignment_id, MIN(learning_step_id) AS learning_step_id " +
                        "FROM user_assignments " +
                        "WHERE learning_step_id IS NOT NULL " +
                        "GROUP BY assignment_id " +
                        "HAVING COUNT(DISTINCT learning_step_id) = 1" +
                        ") AS linked " +
                        "WHERE a.id = linked.assignment_id " +
                        "AND a.learning_step_id IS NULL"
            )
        }

        // Idempotently add baseline math paths for grades 1–12.
        DefaultMathLearningPathSeeder.seedIfMissing()

        println("========================================")
        println("POSTGRESQL DATABASE INITIALIZED")
        println("========================================")
    }
}
