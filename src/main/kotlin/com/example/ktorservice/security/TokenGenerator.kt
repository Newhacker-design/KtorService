package com.example.ktorservice.security

import java.security.SecureRandom
import java.util.Base64

object TokenGenerator {

    private val random =
        SecureRandom()

    fun generate(): String {

        val bytes =
            ByteArray(48)

        random.nextBytes(bytes)

        return Base64
            .getUrlEncoder()
            .withoutPadding()
            .encodeToString(bytes)
    }
}