package com.example.ktorservice.service

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

class AIService {

    private val apiKey: String?
        get() = System.getenv("GEMINI_API_KEY")

    private val model =
        System.getenv("GEMINI_MODEL")
            ?: "gemini-3.5-flash-lite"

    private val json =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
        }

    // ============================================================
    // GENERATE ASSIGNMENT
    // ============================================================

    suspend fun generateAssignment(
        grade: Int,
        subject: String,
        topic: String? = null
    ): GeneratedAssignment {

        val prompt =
            buildPrompt(
                grade = grade,
                subject = subject,
                topic = topic
            )

        val responseText =
            callGeminiWithRetry(
                prompt = prompt
            )

        return parseResponse(
            responseText
        )
    }

    // ============================================================
    // GEMINI REQUEST + RETRY
    // ============================================================

    private suspend fun callGeminiWithRetry(
        prompt: String
    ): String {

        val key =
            apiKey
                ?: throw IllegalStateException(
                    "GEMINI_API_KEY is not configured"
                )

        var lastError =
            "Unknown Gemini error"

        // --------------------------------------------------------
        // Tối đa 3 lần thử
        // --------------------------------------------------------

        for (attempt in 1..3) {

            println(
                "========== GEMINI ATTEMPT $attempt/3 =========="
            )

            try {

                val result =
                    withContext(Dispatchers.IO) {

                        callGemini(
                            key = key,
                            prompt = prompt
                        )
                    }

                println(
                    "GEMINI SUCCESS ON ATTEMPT $attempt"
                )

                return result

            } catch (e: GeminiRetryException) {

                lastError =
                    e.message
                        ?: "Gemini temporary error"

                println(
                    "GEMINI TEMPORARY ERROR: $lastError"
                )

                if (attempt < 3) {

                    val delayMs =
                        when (attempt) {
                            1 -> 2_000L
                            2 -> 5_000L
                            else -> 10_000L
                        }

                    println(
                        "GEMINI RETRY AFTER ${delayMs}ms"
                    )

                    delay(
                        delayMs
                    )
                }

            } catch (e: Exception) {

                // ------------------------------------------------
                // Lỗi không thuộc nhóm retry
                // ------------------------------------------------

                throw e
            }
        }

        throw IllegalStateException(
            lastError
        )
    }

    // ============================================================
    // CALL GEMINI
    // ============================================================

    private fun callGemini(
        key: String,
        prompt: String
    ): String {

        val url =
            "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$key"

        val connection =
            java.net.URL(url)
                .openConnection()
                    as java.net.HttpURLConnection

        try {

            connection.requestMethod =
                "POST"

            connection.setRequestProperty(
                "Content-Type",
                "application/json"
            )

            connection.connectTimeout =
                15_000

            connection.readTimeout =
                60_000

            connection.doOutput =
                true

            // ----------------------------------------------------
            // REQUEST BODY
            // ----------------------------------------------------

            val requestBody =
                buildJsonObject {

                    put(
                        "contents",
                        buildJsonArray {

                            add(
                                buildJsonObject {

                                    put(
                                        "parts",
                                        buildJsonArray {

                                            add(
                                                buildJsonObject {

                                                    put(
                                                        "text",
                                                        prompt
                                                    )
                                                }
                                            )
                                        }
                                    )
                                }
                            )
                        }
                    )

                    put(
                        "generationConfig",
                        buildJsonObject {

                            put(
                                "temperature",
                                0.7
                            )

                            put(
                                "responseMimeType",
                                "application/json"
                            )
                        }
                    )
                }

            // ----------------------------------------------------
            // SEND
            // ----------------------------------------------------

            connection.outputStream
                .bufferedWriter()
                .use {

                    it.write(
                        requestBody.toString()
                    )
                }

            // ----------------------------------------------------
            // RESPONSE
            // ----------------------------------------------------

            val responseCode =
                connection.responseCode

            val responseText =
                if (responseCode in 200..299) {

                    connection.inputStream
                        .bufferedReader()
                        .use {
                            it.readText()
                        }

                } else {

                    connection.errorStream
                        ?.bufferedReader()
                        ?.use {
                            it.readText()
                        }
                        ?: "Unknown Gemini error"
                }

            println(
                "GEMINI HTTP CODE = $responseCode"
            )

            // ----------------------------------------------------
            // SUCCESS
            // ----------------------------------------------------

            if (responseCode in 200..299) {

                return responseText
            }

            // ----------------------------------------------------
            // TEMPORARY ERROR
            //
            // 429 = Too Many Requests
            // 500 = Internal Server Error
            // 503 = Service Unavailable
            // ----------------------------------------------------

            if (
                responseCode == 429 ||
                responseCode == 500 ||
                responseCode == 503
            ) {

                throw GeminiRetryException(
                    "Gemini API temporary error $responseCode: $responseText"
                )
            }

            // ----------------------------------------------------
            // OTHER ERROR
            // Không retry
            // ----------------------------------------------------

            throw IllegalStateException(
                "Gemini API error $responseCode: $responseText"
            )

        } finally {

            connection.disconnect()
        }
    }

    // ============================================================
    // RETRY EXCEPTION
    // ============================================================

    private class GeminiRetryException(
        message: String
    ) : Exception(message)

    // ============================================================
    // PROMPT
    // ============================================================

    private fun buildPrompt(
        grade: Int,
        subject: String,
        topic: String?
    ): String {

        val topicText =
            if (topic.isNullOrBlank()) {

                "Tự chọn một nội dung phù hợp với chương trình lớp $grade."

            } else {

                "Chủ đề yêu cầu: $topic"
            }

        return """
            Bạn là giáo viên Việt Nam có kinh nghiệm.

            Hãy tạo một bài tập cho học sinh:

            Lớp: $grade
            Môn: $subject

            $topicText

            Yêu cầu:

            1. Nội dung phù hợp với học sinh lớp $grade.
            2. Bài tập phải rõ ràng, có thể giao trực tiếp cho học sinh.
            3. Có đáp án chính xác.
            4. Có hướng dẫn chấm điểm.
            5. Tổng điểm là 10.
            6. Không đưa thông tin không chắc chắn.
            7. Nếu là Ngữ văn, cần phù hợp với trình độ học sinh.
            8. Nếu là Toán, đáp án phải có kết quả và cách giải.
            9. Nếu là Tiếng Anh, đáp án phải rõ ràng.

            Chỉ trả về JSON hợp lệ theo đúng cấu trúc:

            {
              "title": "Tên bài",
              "content": "Nội dung bài tập",
              "answerKey": "Đáp án",
              "gradingGuide": "Hướng dẫn chấm điểm",
              "totalScore": 10
            }

            Không thêm markdown.
            Không thêm ```json.
            Không giải thích bên ngoài JSON.
        """.trimIndent()
    }

    // ============================================================
    // PARSE GEMINI RESPONSE
    // ============================================================

    private fun parseResponse(
        responseText: String
    ): GeneratedAssignment {

        val root =
            json.parseToJsonElement(
                responseText
            ).jsonObject

        val candidates =
            root["candidates"]
                ?.jsonArray
                ?: throw IllegalStateException(
                    "Gemini response has no candidates"
                )

        val firstCandidate =
            candidates.firstOrNull()
                ?.jsonObject
                ?: throw IllegalStateException(
                    "Gemini returned no candidate"
                )

        val content =
            firstCandidate["content"]
                ?.jsonObject
                ?: throw IllegalStateException(
                    "Gemini response has no content"
                )

        val parts =
            content["parts"]
                ?.jsonArray
                ?: throw IllegalStateException(
                    "Gemini response has no parts"
                )

        val text =
            parts
                .firstOrNull()
                ?.jsonObject
                ?.get("text")
                ?.jsonPrimitive
                ?.content
                ?: throw IllegalStateException(
                    "Gemini response has no text"
                )

        val assignmentJson =
            json.parseToJsonElement(
                text.trim()
            ).jsonObject

        return GeneratedAssignment(

            title =
                assignmentJson["title"]
                    ?.jsonPrimitive
                    ?.content
                    ?: "Bài tập",

            content =
                assignmentJson["content"]
                    ?.jsonPrimitive
                    ?.content
                    ?: "",

            answerKey =
                assignmentJson["answerKey"]
                    ?.jsonPrimitive
                    ?.content
                    ?: "",

            gradingGuide =
                assignmentJson["gradingGuide"]
                    ?.jsonPrimitive
                    ?.content
                    ?: "",

            totalScore =
                assignmentJson["totalScore"]
                    ?.jsonPrimitive
                    ?.doubleOrNull
                    ?: 10.0
        )
    }

    // ============================================================
    // MODEL
    // ============================================================

    @Serializable
    data class GeneratedAssignment(

        val title: String,

        val content: String,

        val answerKey: String,

        val gradingGuide: String,

        val totalScore: Double
    )
}

