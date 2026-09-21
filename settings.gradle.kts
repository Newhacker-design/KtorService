import org.gradle.api.initialization.resolve.RepositoriesMode

pluginManagement {
    repositories {
        // Mirror cho Gradle plugins (quan trọng: kotlin-gradle-plugin)
        maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        // Dự phòng
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // Mirror cho các dependency của dự án
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        mavenCentral()
    }
}

rootProject.name = "KtorService"