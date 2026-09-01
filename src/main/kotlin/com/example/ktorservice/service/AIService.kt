package com.example.ktorservice.service

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

class AIService {
    @Serializable
    enum class AnswerType {
        TEXT,
        HANDWRITING,
        DRAWING,
        MIXED
    }

    @Serializable
    enum class GradingMethod {
        EXACT,
        AI_TEXT,
        OCR_AI,
        OPENCV,
        OPENCV_VISION_AI
    }
    @Serializable
    data class GeneratedQuestion(
        val id: Int,
        val question: String,
        val points: Double,
        val answerType: AnswerType,
        val gradingMethod: GradingMethod
    )

    @Serializable
    data class GeneratedAnswer(
        val id: Int,
        val answer: String
    )



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
                "Tự chọn nội dung phù hợp với chương trình lớp $grade."
            } else {
                "Chủ đề yêu cầu: $topic"
            }

        return """
        Bạn là giáo viên Việt Nam có kinh nghiệm.

        Hãy tạo MỘT BÀI TẬP gồm đúng 3 CÂU HỎI cho học sinh:

        Lớp: $grade
        Môn: $subject

        $topicText

        YÊU CẦU:

        1. Bài tập gồm đúng 3 câu hỏi.
        2. Cả 3 câu thuộc cùng một chủ đề.
        3. Nội dung phù hợp với học sinh lớp $grade.
        4. Mỗi câu phải có điểm riêng.
        5. Tổng điểm của 3 câu phải bằng 10.
        6. Có đáp án chính xác cho từng câu.
        7. Có hướng dẫn chấm điểm.
        8. Nếu là Toán, đáp án phải có kết quả và cách giải cần thiết.
        9. Nếu là Ngữ văn, chấm được các cách diễn đạt tương đương.
        10. Nếu là Tiếng Anh, đáp án phải rõ ràng.
        11. Không tạo câu hỏi mơ hồ.
        12. Không thêm câu hỏi thứ 4.
        13. Không gộp nhiều câu vào cùng một question.

        CẤU TRÚC JSON BẮT BUỘC:

       {
  "title": "Tên bài",

  "questions": [
    {
      "id": 1,
      "question": "Nội dung câu hỏi 1",
      "points": 3,
      "answerType": "TEXT",
      "gradingMethod": "AI_TEXT"
    },
    {
      "id": 2,
      "question": "Nội dung câu hỏi 2",
      "points": 3,
      "answerType": "HANDWRITING",
      "gradingMethod": "OCR_AI"
    },
    {
      "id": 3,
      "question": "Nội dung câu hỏi 3",
      "points": 4,
      "answerType": "DRAWING",
      "gradingMethod": "OPENCV_VISION_AI"
    }
  ],

  "answerKey": [
    {
      "id": 1,
      "answer": "Đáp án câu 1"
    },
    {
      "id": 2,
      "answer": "Đáp án câu 2"
    },
    {
      "id": 3,
      "answer": "Đáp án câu 3"
    }
  ],

  "gradingGuide": "Hướng dẫn chấm từng câu",

  "totalScore": 10
}

        QUY TẮC:

        - questions phải có đúng 3 phần tử.
        - answerKey phải có đúng 3 phần tử.
        - id phải tương ứng giữa questions và answerKey.
        - Tổng points phải bằng 10.
        - Chỉ trả về JSON hợp lệ.
        - Không markdown.
        - Không ```json.
        - Không giải thích bên ngoài JSON.
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

        val title =
            assignmentJson["title"]
                ?.jsonPrimitive
                ?.content
                ?: "Bài tập"

        val questions =
            assignmentJson["questions"]
                ?.jsonArray
                ?.map { element ->

                    val obj =
                        element.jsonObject

                    GeneratedQuestion(
                        id =
                            obj["id"]
                                ?.jsonPrimitive
                                ?.int
                                ?: throw IllegalStateException(
                                    "Question id missing"
                                ),

                        question =
                            obj["question"]
                                ?.jsonPrimitive
                                ?.content
                                ?: throw IllegalStateException(
                                    "Question content missing"
                                ),

                        points =
                            obj["points"]
                                ?.jsonPrimitive
                                ?.doubleOrNull
                                ?: throw IllegalStateException(
                                    "Question points missing"
                                ),

                        answerType =
                            obj["answerType"]
                                ?.jsonPrimitive
                                ?.content
                                ?.let {
                                    runCatching {
                                        AnswerType.valueOf(it.uppercase())
                                    }.getOrElse {
                                        throw IllegalStateException(
                                            "Invalid answerType: $it"
                                        )
                                    }
                                }
                                ?: throw IllegalStateException(
                                    "Question answerType missing"
                                ),

                        gradingMethod =
                            obj["gradingMethod"]
                                ?.jsonPrimitive
                                ?.content
                                ?.let {
                                    runCatching {
                                        GradingMethod.valueOf(it.uppercase())
                                    }.getOrElse {
                                        throw IllegalStateException(
                                            "Invalid gradingMethod: $it"
                                        )
                                    }
                                }
                                ?: throw IllegalStateException(
                                    "Question gradingMethod missing"
                                )
                    )
                }
                ?: throw IllegalStateException(
                    "Questions missing"
                )
        val questionIds =
            questions.map { it.id }.toSet()


        val answerKey =
            assignmentJson["answerKey"]
                ?.jsonArray
                ?.map { element ->

                    val obj =
                        element.jsonObject

                    GeneratedAnswer(
                        id =
                            obj["id"]
                                ?.jsonPrimitive
                                ?.int
                                ?: throw IllegalStateException(
                                    "Answer id missing"
                                ),

                        answer =
                            obj["answer"]
                                ?.jsonPrimitive
                                ?.content
                                ?: ""
                    )
                }
                ?: throw IllegalStateException(
                    "Answer key missing"
                )
        val answerIds =
            answerKey.map { it.id }.toSet()
        if (questionIds != answerIds) {
            throw IllegalStateException(
                "Question IDs and answer IDs do not match"
            )
        }

        val gradingGuide =
            assignmentJson["gradingGuide"]
                ?.jsonPrimitive
                ?.content
                ?: ""

        val totalScore =
            assignmentJson["totalScore"]
                ?.jsonPrimitive
                ?.doubleOrNull
                ?: 10.0

        val pointsTotal =
            questions.sumOf {
                it.points
            }

        if (kotlin.math.abs(pointsTotal - totalScore) > 0.01) {

            throw IllegalStateException(
                "Question points total $pointsTotal != totalScore $totalScore"
            )
        }

        val generatedContent =
            questions.joinToString(
                separator = "\n\n"
            ) {
                "Câu ${it.id} (${it.points} điểm):\n${it.question}"
            }

        val generatedAnswerKey =
            answerKey.joinToString(
                separator = "\n\n"
            ) {
                "Câu ${it.id}:\n${it.answer}"
            }

        return GeneratedAssignment(

            title = title,

            questions = questions,

            answerKey = answerKey,

            gradingGuide = gradingGuide,

            totalScore = totalScore
        )
    }


    // ============================================================
    // MODEL
    // ============================================================
    @Serializable
    data class GeneratedAssignment(
        val title: String,

        val questions: List<GeneratedQuestion>,

        val answerKey: List<GeneratedAnswer>,

        val gradingGuide: String,

        val totalScore: Double
    )
    @Serializable
    data class QuestionGradingResult(
        val id: Int,
        val score: Double,
        val feedback: String
    )

    @Serializable
    data class GradingResult(
        val score: Double,
        val feedback: String,
        val questions: List<QuestionGradingResult> = emptyList()
    )

    suspend fun gradeAssignment(
        assignment: AssignmentService.AssignmentResult,
        studentAnswer: String
    ): GradingResult {

        val prompt =
            buildGradingPrompt(
                assignment = assignment,
                studentAnswer = studentAnswer
            )

        return withContext(Dispatchers.IO) {

            val key =
                apiKey
                    ?: throw IllegalStateException(
                        "GEMINI_API_KEY is not configured"
                    )

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
                                    0.2
                                )

                                put(
                                    "responseMimeType",
                                    "application/json"
                                )
                            }
                        )
                    }

                connection.outputStream
                    .bufferedWriter()
                    .use {
                        it.write(
                            requestBody.toString()
                        )
                    }

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

                println("GEMINI GRADING HTTP CODE = $responseCode")

                println("========== GEMINI GRADING RAW RESPONSE ==========")
                println(responseText)
                println("=================================================")

                if (responseCode !in 200..299) {

                    throw IllegalStateException(
                        "Gemini grading error $responseCode: $responseText"
                    )
                }

                parseGradingResponse(responseText)

            } finally {

                connection.disconnect()
            }
        }
    }
    private fun buildGradingPrompt(
        assignment: AssignmentService.AssignmentResult,
        studentAnswer: String
    ): String {

        return """
        Bạn là giáo viên Việt Nam đang chấm bài cho học sinh.

        THÔNG TIN BÀI TẬP

        Môn: ${assignment.subject}
        Lớp: ${assignment.grade}
        Tiêu đề: ${assignment.title}

        NỘI DUNG BÀI TẬP:

        ${assignment.content}

        ĐÁP ÁN CHUẨN:

        ${assignment.answerKey}

        HƯỚNG DẪN CHẤM:

        ${assignment.gradingGuide}

        TỔNG ĐIỂM:

        ${assignment.totalScore}

        BÀI LÀM CỦA HỌC SINH:

        $studentAnswer

        Hãy chấm bài làm của học sinh.

        Yêu cầu:

        1. Chấm công bằng dựa trên đáp án và hướng dẫn chấm.
        2. Không tự ý thay đổi thang điểm.
        3. Điểm tối đa là ${assignment.totalScore}.
        4. Có thể cho điểm lẻ.
        5. Nếu học sinh trả lời đúng nhưng diễn đạt khác đáp án mẫu,
           vẫn phải xem xét cho điểm nếu nội dung chính xác.
        6. Nếu bài làm thiếu ý, phải trừ điểm tương ứng.
        7. Feedback phải ngắn gọn, dễ hiểu với học sinh lớp ${assignment.grade}.
        8. Không bịa thông tin.

        Chỉ trả về JSON hợp lệ:

        {
          "score": 8.5,
          "feedback": "Bài làm tốt. Em đã trả lời đúng các ý chính..."
        }

        Không thêm markdown.
        Không thêm ```json.
        Không giải thích bên ngoài JSON.
    """.trimIndent()
    }
    private fun parseGradingResponse(
        responseText: String
    ): GradingResult {

        val root =
            json.parseToJsonElement(
                responseText
            ).jsonObject

        val candidates =
            root["candidates"]
                ?.jsonArray
                ?: throw IllegalStateException(
                    "Gemini grading response has no candidates"
                )

        val firstCandidate =
            candidates.firstOrNull()
                ?.jsonObject
                ?: throw IllegalStateException(
                    "Gemini grading returned no candidate"
                )

        val content =
            firstCandidate["content"]
                ?.jsonObject
                ?: throw IllegalStateException(
                    "Gemini grading response has no content"
                )

        val parts =
            content["parts"]
                ?.jsonArray
                ?: throw IllegalStateException(
                    "Gemini grading response has no parts"
                )

        val text =
            parts
                .firstOrNull()
                ?.jsonObject
                ?.get("text")
                ?.jsonPrimitive
                ?.content
                ?: throw IllegalStateException(
                    "Gemini grading response has no text"
                )

        val result =
            json.parseToJsonElement(
                text.trim()
            ).jsonObject

        val score =
            result["score"]
                ?.jsonPrimitive
                ?.doubleOrNull
                ?: throw IllegalStateException(
                    "Gemini grading response has no score"
                )

        val feedback =
            result["feedback"]
                ?.jsonPrimitive
                ?.content
                ?: ""

        return GradingResult(
            score = score,
            feedback = feedback
        )
    }
}

