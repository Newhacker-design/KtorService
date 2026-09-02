package com.example.ktorservice.service

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

class AIService {

    // ============================================================
    // ANSWER TYPE
    // ============================================================

    @Serializable
    enum class AnswerType {
        TEXT,
        HANDWRITING,
        DRAWING,
        SPEECH_TO_TEXT,
        MIXED
    }

    // ============================================================
    // DIFFICULTY
    // ============================================================

    @Serializable
    enum class Difficulty {
        EASY,
        MEDIUM,
        HARD
    }

    // ============================================================
    // GRADING METHOD
    // ============================================================

    @Serializable
    enum class GradingMethod {
        EXACT,
        AI_TEXT,
        OCR_AI,
        OPENCV,
        OPENCV_VISION_AI
    }

    // ============================================================
    // GENERATED QUESTION
    // ============================================================

    @Serializable
    data class GeneratedQuestion(
        val id: Int,
        val question: String,
        val points: Double,
        val answerType: AnswerType,
        val gradingMethod: GradingMethod
    )

    // ============================================================
    // GENERATED ANSWER
    // ============================================================

    @Serializable
    data class GeneratedAnswer(
        val id: Int,
        val answer: String
    )

    // ============================================================
    // GEMINI CONFIG
    // ============================================================

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
        topic: String? = null,
        difficulty: Difficulty
    ): GeneratedAssignment {

        val prompt =
            buildPrompt(
                grade = grade,
                subject = subject,
                topic = topic,
                difficulty = difficulty
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
        topic: String?,
        difficulty: Difficulty
    ): String {

        val topicText =
            if (topic.isNullOrBlank()) {

                "Tự chọn nội dung phù hợp với chương trình lớp $grade."

            } else {

                "Chủ đề yêu cầu: $topic"
            }

        val difficultyText =
            when (difficulty) {

                Difficulty.EASY ->
                    """
                    EASY - DỄ:
                    - Kiến thức cơ bản phù hợp với lớp $grade.
                    - Chủ yếu kiểm tra khả năng nhớ và hiểu.
                    - Số bước giải ít.
                    - Cách hỏi trực tiếp, rõ ràng.
                    - Không sử dụng câu hỏi đánh đố.
                    - Không yêu cầu kiến thức vượt chương trình.
                    """.trimIndent()

                Difficulty.MEDIUM ->
                    """
                    MEDIUM - TRUNG BÌNH:
                    - Phù hợp chương trình lớp $grade.
                    - Yêu cầu học sinh vận dụng kiến thức.
                    - Có thể cần nhiều bước suy luận.
                    - Có thể kết hợp các kiến thức đã học.
                    - Mức độ phù hợp với bài luyện tập hoặc kiểm tra thông thường.
                    """.trimIndent()

                Difficulty.HARD ->
                    """
                    HARD - KHÓ:
                    - Phù hợp chương trình lớp $grade nhưng ở mức vận dụng cao.
                    - Có thể kết hợp nhiều kiến thức.
                    - Có nhiều bước suy luận hoặc giải quyết vấn đề.
                    - Có thể sử dụng tình huống biến đổi hoặc nâng cao.
                    - Không được sử dụng kiến thức vượt quá chương trình lớp $grade.
                    - Không tạo câu hỏi khó một cách vô lý hoặc đánh đố.
                    """.trimIndent()
            }

        return """
        Bạn là giáo viên Việt Nam có kinh nghiệm.

        Hãy tạo MỘT BÀI TẬP gồm đúng 3 CÂU HỎI cho học sinh.

        THÔNG TIN:

        Lớp: $grade
        Môn: $subject

        $topicText

        ĐỘ KHÓ ĐƯỢC YÊU CẦU:

        ${difficulty.name}

        $difficultyText

        ============================================================
        YÊU CẦU CHUNG
        ============================================================

        1. Bài tập gồm đúng 3 câu hỏi.

        2. Cả 3 câu thuộc cùng một chủ đề.

        3. Nội dung phù hợp với học sinh lớp $grade.

        4. Tất cả câu hỏi phải tuân thủ độ khó ${difficulty.name}.

        5. Mỗi câu phải có điểm riêng.

        6. Tổng điểm của 3 câu phải bằng 10.

        7. Có đáp án chính xác cho từng câu.

        8. Có hướng dẫn chấm điểm.

        9. Nếu là Toán, đáp án phải có kết quả và cách giải cần thiết.

        10. Nếu là Ngữ văn, chấp nhận các cách diễn đạt tương đương
            nếu nội dung chính xác.

        11. Nếu là Tiếng Anh, đáp án phải rõ ràng.

        12. Không tạo câu hỏi mơ hồ.

        13. Không thêm câu hỏi thứ 4.

        14. Không gộp nhiều câu hỏi vào cùng một question.

        ============================================================
        ANSWER TYPE
        ============================================================

        Mỗi câu phải chọn MỘT answerType phù hợp nhất.

        Các giá trị hợp lệ:

        TEXT
        - Học sinh nhập câu trả lời bằng bàn phím.

        HANDWRITING
        - Học sinh viết tay.
        - Phù hợp với bài toán, phép tính, lời giải hoặc nội dung
          mà việc viết tay có ý nghĩa.

        DRAWING
        - Học sinh cần vẽ hình, biểu đồ, sơ đồ hoặc hình minh họa.

        SPEECH_TO_TEXT
        - Học sinh trả lời bằng lời nói.
        - Hệ thống sẽ chuyển giọng nói thành văn bản trước khi chấm.
        - Chỉ sử dụng khi việc trả lời bằng lời nói thực sự phù hợp
          với câu hỏi.

        MIXED
        - Chỉ sử dụng khi một câu thực sự yêu cầu nhiều loại
          input khác nhau.
        - Không sử dụng MIXED nếu chỉ cần một answerType.

        ============================================================
        GRADING METHOD
        ============================================================

        Chọn gradingMethod phù hợp với answerType.

        Quy tắc:

        TEXT:
        - EXACT nếu đáp án cần khớp chính xác.
        - AI_TEXT nếu cần đánh giá nội dung hoặc cách diễn đạt.

        HANDWRITING:
        - OCR_AI.

        DRAWING:
        - OPENCV nếu có thể đánh giá bằng hình học/xử lý ảnh.
        - OPENCV_VISION_AI nếu cần kết hợp phân tích hình ảnh
          với AI.

        SPEECH_TO_TEXT:
        - EXACT nếu câu trả lời sau chuyển giọng nói thành văn bản
          cần khớp chính xác.
        - AI_TEXT nếu cần đánh giá nội dung câu trả lời.

        MIXED:
        - Chỉ chọn khi thực sự cần thiết.
        - Không tự ý dùng một gradingMethod không phù hợp.

        Không được tạo các giá trị answerType hoặc gradingMethod
        ngoài danh sách trên.

        ============================================================
        JSON BẮT BUỘC
        ============================================================

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

        ============================================================
        QUY TẮC JSON
        ============================================================

        - questions phải có đúng 3 phần tử.

        - answerKey phải có đúng 3 phần tử.

        - id phải tương ứng giữa questions và answerKey.

        - ID phải là 1, 2, 3.

        - Tổng points phải bằng 10.

        - totalScore phải bằng 10.

        - answerType phải là một trong:
          TEXT, HANDWRITING, DRAWING, SPEECH_TO_TEXT, MIXED.

        - gradingMethod phải là một trong:
          EXACT, AI_TEXT, OCR_AI, OPENCV, OPENCV_VISION_AI.

        - answerType và gradingMethod phải tương thích.

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

                    val answerType =
                        obj["answerType"]
                            ?.jsonPrimitive
                            ?.content
                            ?.let { value ->

                                runCatching {
                                    AnswerType.valueOf(
                                        value.uppercase()
                                    )
                                }.getOrElse {

                                    throw IllegalStateException(
                                        "Invalid answerType: $value"
                                    )
                                }

                            }
                            ?: throw IllegalStateException(
                                "Question answerType missing"
                            )

                    val gradingMethod =
                        obj["gradingMethod"]
                            ?.jsonPrimitive
                            ?.content
                            ?.let { value ->

                                runCatching {
                                    GradingMethod.valueOf(
                                        value.uppercase()
                                    )
                                }.getOrElse {

                                    throw IllegalStateException(
                                        "Invalid gradingMethod: $value"
                                    )
                                }

                            }
                            ?: throw IllegalStateException(
                                "Question gradingMethod missing"
                            )

                    // ------------------------------------------------
                    // Kiểm tra answerType / gradingMethod
                    // ------------------------------------------------

                    validateGradingCompatibility(
                        answerType = answerType,
                        gradingMethod = gradingMethod
                    )

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
                            answerType,

                        gradingMethod =
                            gradingMethod
                    )
                }
                ?: throw IllegalStateException(
                    "Questions missing"
                )

        // ------------------------------------------------------------
        // Kiểm tra đúng 3 câu
        // ------------------------------------------------------------

        if (questions.size != 3) {

            throw IllegalStateException(
                "Assignment must contain exactly 3 questions, got ${questions.size}"
            )
        }

        // ------------------------------------------------------------
        // Kiểm tra ID câu hỏi
        // ------------------------------------------------------------

        val questionIds =
            questions.map { it.id }

        if (questionIds != listOf(1, 2, 3)) {

            throw IllegalStateException(
                "Question IDs must be exactly [1, 2, 3], got $questionIds"
            )
        }

        // ------------------------------------------------------------
        // ANSWER KEY
        // ------------------------------------------------------------

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

        // ------------------------------------------------------------
        // Kiểm tra answerKey có đúng 3 câu
        // ------------------------------------------------------------

        if (answerKey.size != 3) {

            throw IllegalStateException(
                "Answer key must contain exactly 3 answers, got ${answerKey.size}"
            )
        }

        val answerIds =
            answerKey.map { it.id }

        if (answerIds != listOf(1, 2, 3)) {

            throw IllegalStateException(
                "Answer IDs must be exactly [1, 2, 3], got $answerIds"
            )
        }

        // ------------------------------------------------------------
        // Kiểm tra question IDs và answer IDs
        // ------------------------------------------------------------

        if (questionIds.toSet() != answerIds.toSet()) {

            throw IllegalStateException(
                "Question IDs and answer IDs do not match"
            )
        }

        // ------------------------------------------------------------
        // GRADING GUIDE
        // ------------------------------------------------------------

        val gradingGuide =
            assignmentJson["gradingGuide"]
                ?.jsonPrimitive
                ?.content
                ?: ""

        // ------------------------------------------------------------
        // TOTAL SCORE
        // ------------------------------------------------------------

        val totalScore =
            assignmentJson["totalScore"]
                ?.jsonPrimitive
                ?.doubleOrNull
                ?: 10.0

        // ------------------------------------------------------------
        // Kiểm tra tổng điểm
        // ------------------------------------------------------------

        val pointsTotal =
            questions.sumOf {
                it.points
            }

        if (
            kotlin.math.abs(
                pointsTotal - totalScore
            ) > 0.01
        ) {

            throw IllegalStateException(
                "Question points total $pointsTotal != totalScore $totalScore"
            )
        }

        if (
            kotlin.math.abs(
                totalScore - 10.0
            ) > 0.01
        ) {

            throw IllegalStateException(
                "Assignment totalScore must be 10, got $totalScore"
            )
        }

        // ------------------------------------------------------------
        // GENERATED CONTENT
        // ------------------------------------------------------------

        val generatedContent =
            questions.joinToString(
                separator = "\n\n"
            ) {

                "Câu ${it.id} (${it.points} điểm):\n${it.question}"
            }

        // ------------------------------------------------------------
        // GENERATED ANSWER KEY
        // ------------------------------------------------------------

        val generatedAnswerKey =
            answerKey.joinToString(
                separator = "\n\n"
            ) {

                "Câu ${it.id}:\n${it.answer}"
            }

        println(
            "========== GENERATED ASSIGNMENT =========="
        )

        println(
            "TITLE = $title"
        )

        println(
            "QUESTIONS = ${questions.size}"
        )

        questions.forEach {

            println(
                "QUESTION ${it.id}: " +
                        "answerType=${it.answerType}, " +
                        "gradingMethod=${it.gradingMethod}, " +
                        "points=${it.points}"
            )
        }

        println(
            "TOTAL SCORE = $totalScore"
        )

        println(
            "=========================================="
        )

        return GeneratedAssignment(

            title = title,

            questions = questions,

            answerKey = answerKey,

            gradingGuide = gradingGuide,

            totalScore = totalScore
        )
    }

    // ============================================================
    // VALIDATE ANSWER TYPE + GRADING METHOD
    // ============================================================

    private fun validateGradingCompatibility(
        answerType: AnswerType,
        gradingMethod: GradingMethod
    ) {

        val valid =
            when (answerType) {

                AnswerType.TEXT ->
                    gradingMethod == GradingMethod.EXACT ||
                            gradingMethod == GradingMethod.AI_TEXT

                AnswerType.HANDWRITING ->
                    gradingMethod == GradingMethod.OCR_AI

                AnswerType.DRAWING ->
                    gradingMethod == GradingMethod.OPENCV ||
                            gradingMethod == GradingMethod.OPENCV_VISION_AI

                AnswerType.SPEECH_TO_TEXT ->
                    gradingMethod == GradingMethod.EXACT ||
                            gradingMethod == GradingMethod.AI_TEXT

                AnswerType.MIXED ->
                    true
            }

        if (!valid) {

            throw IllegalStateException(
                "Invalid grading combination: " +
                        "answerType=$answerType, " +
                        "gradingMethod=$gradingMethod"
            )
        }
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

    // ============================================================
    // GRADING RESULT
    // ============================================================

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

    // ============================================================
    // GRADE ASSIGNMENT
    // ============================================================

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

                println(
                    "GEMINI GRADING HTTP CODE = $responseCode"
                )

                println(
                    "========== GEMINI GRADING RAW RESPONSE =========="
                )

                println(
                    responseText
                )

                println(
                    "================================================="
                )

                if (responseCode !in 200..299) {

                    throw IllegalStateException(
                        "Gemini grading error $responseCode: $responseText"
                    )
                }

                parseGradingResponse(
                    responseText
                )

            } finally {

                connection.disconnect()
            }
        }
    }

    // ============================================================
    // GRADING PROMPT
    // ============================================================

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

    // ============================================================
    // PARSE GRADING RESPONSE
    // ============================================================

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
