package com.example.ktorservice.service

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import kotlin.math.abs

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
    // SUBJECT TYPE
    // ============================================================

    enum class SubjectType {
        NORMAL,
        SEX_EDUCATION
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

    /*
     * API KEY dùng cho các môn học thông thường.
     */
    private val apiKey: String?
        get() = System.getenv("GEMINI_API_KEY")

    /*
     * API KEY riêng cho Giáo dục giới tính.
     *
     * Nếu chưa cấu hình, hệ thống sẽ fallback về GEMINI_API_KEY.
     */
    private val sexEducationApiKey: String?
        get() = System.getenv("GEMINI_SEX_EDUCATION_API_KEY")

    /*
     * Model thông thường.
     */
    private val model: String
        get() =
            System.getenv("GEMINI_MODEL")
                ?: "gemini-3.5-flash-lite"

    /*
     * Model riêng cho Giáo dục giới tính.
     *
     * Nếu chưa cấu hình, dùng GEMINI_MODEL.
     */
    private val sexEducationModel: String
        get() =
            System.getenv("GEMINI_SEX_EDUCATION_MODEL")
                ?: model

    private val json =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
        }

    // ============================================================
    // SUBJECT HELPER
    // ============================================================

    private fun getSubjectType(
        subject: String
    ): SubjectType {

        val normalized =
            subject
                .trim()
                .lowercase()
                .replace("-", "_")
                .replace(" ", "_")

        return when (normalized) {

            "sex_education",
            "sexeducation",
            "gender_education",
            "sexual_education",
            "giao_duc_gioi_tinh",
            "giáo_dục_giới_tính" -> {

                SubjectType.SEX_EDUCATION
            }

            else -> {

                SubjectType.NORMAL
            }
        }
    }

    private fun isSexEducation(
        subject: String
    ): Boolean {

        return getSubjectType(subject) ==
                SubjectType.SEX_EDUCATION
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

        val sexEducation =
            isSexEducation(subject)

        println(
            "========== AI GENERATE ASSIGNMENT =========="
        )

        println(
            "GRADE = $grade"
        )

        println(
            "SUBJECT = $subject"
        )

        println(
            "SUBJECT TYPE = ${getSubjectType(subject)}"
        )

        println(
            "TOPIC = $topic"
        )

        println(
            "DIFFICULTY = $difficulty"
        )

        println(
            "SEX EDUCATION = $sexEducation"
        )

        println(
            "============================================"
        )

        val prompt =
            if (sexEducation) {

                buildSexEducationPrompt(
                    grade = grade,
                    topic = topic,
                    difficulty = difficulty
                )

            } else {

                buildPrompt(
                    grade = grade,
                    subject = subject,
                    topic = topic,
                    difficulty = difficulty
                )
            }

        val responseText =
            callGeminiWithRetry(
                prompt = prompt,
                sexEducation = sexEducation
            )

        val result =
            parseResponse(
                responseText
            )

        if (sexEducation) {

            validateSexEducationAssignment(
                assignment = result,
                grade = grade
            )
        }

        return result
    }

    // ============================================================
    // GEMINI REQUEST + RETRY
    // ============================================================

    private suspend fun callGeminiWithRetry(
        prompt: String,
        sexEducation: Boolean = false
    ): String {

        val key =
            if (sexEducation) {
                sexEducationApiKey ?: apiKey
            } else {
                apiKey
            } ?: throw IllegalStateException(
                if (sexEducation) {
                    "GEMINI_SEX_EDUCATION_API_KEY or GEMINI_API_KEY is not configured"
                } else {
                    "GEMINI_API_KEY is not configured"
                }
            )

        /*
         * ------------------------------------------------------------
         * MODEL CHÍNH
         * ------------------------------------------------------------
         */

        val primaryModel =
            if (sexEducation) {
                sexEducationModel
            } else {
                model
            }

        /*
         * ------------------------------------------------------------
         * MODEL DỰ PHÒNG
         *
         * Có thể cấu hình bằng ENV:
         *
         * GEMINI_FALLBACK_MODEL
         * GEMINI_SEX_EDUCATION_FALLBACK_MODEL
         *
         * Nếu không cấu hình:
         *
         * gemini-3.5-flash
         *
         * ------------------------------------------------------------
         */

        val fallbackModel =
            if (sexEducation) {

                System.getenv(
                    "GEMINI_SEX_EDUCATION_FALLBACK_MODEL"
                ) ?: System.getenv(
                    "GEMINI_FALLBACK_MODEL"
                ) ?: "gemini-3.5-flash"

            } else {

                System.getenv(
                    "GEMINI_FALLBACK_MODEL"
                ) ?: "gemini-3.5-flash"
            }

        /*
         * ------------------------------------------------------------
         * DANH SÁCH MODEL
         *
         * Nếu primary == fallback thì chỉ gọi một model.
         * ------------------------------------------------------------
         */

        val models =
            if (
                primaryModel == fallbackModel
            ) {
                listOf(primaryModel)
            } else {
                listOf(
                    primaryModel,
                    fallbackModel
                )
            }

        var lastError =
            "Unknown Gemini error"

        /*
         * ------------------------------------------------------------
         * THỬ TỪNG MODEL
         * ------------------------------------------------------------
         */

        for ((modelIndex, selectedModel) in models.withIndex()) {

            val modelType =
                if (modelIndex == 0) {
                    "PRIMARY"
                } else {
                    "FALLBACK"
                }

            println(
                "=================================================="
            )

            println(
                "GEMINI MODEL TYPE = $modelType"
            )

            println(
                "GEMINI MODEL = $selectedModel"
            )

            println(
                "=================================================="
            )

            /*
             * --------------------------------------------------------
             * MỖI MODEL TỐI ĐA 3 ATTEMPT
             * --------------------------------------------------------
             */

            for (attempt in 1..3) {

                println(
                    "========== GEMINI $modelType ATTEMPT $attempt/3 =========="
                )

                try {

                    val result =
                        withContext(Dispatchers.IO) {

                            callGemini(
                                key = key,
                                prompt = prompt,
                                sexEducation = sexEducation,
                                selectedModelOverride = selectedModel
                            )
                        }

                    println(
                        "GEMINI SUCCESS"
                    )

                    println(
                        "MODEL TYPE = $modelType"
                    )

                    println(
                        "MODEL = $selectedModel"
                    )

                    println(
                        "ATTEMPT = $attempt"
                    )

                    return result

                } catch (e: GeminiRetryException) {

                    lastError =
                        e.message
                            ?: "Gemini temporary error"

                    println(
                        "GEMINI TEMPORARY ERROR: $lastError"
                    )

                    /*
                     * ------------------------------------------------
                     * Nếu chưa hết attempt:
                     *
                     * 1 -> 5s
                     * 2 -> 15s
                     *
                     * Sau attempt 3 chuyển fallback model.
                     * ------------------------------------------------
                     */

                    if (attempt < 3) {

                        val delayMs =
                            when (attempt) {
                                1 -> 5_000L
                                2 -> 15_000L
                                else -> 30_000L
                            }

                        println(
                            "GEMINI RETRY AFTER ${delayMs}ms"
                        )

                        delay(
                            delayMs
                        )
                    }

                } catch (e: java.net.SocketTimeoutException) {

                    lastError =
                        "Gemini socket timeout: ${e.message}"

                    println(
                        "GEMINI SOCKET TIMEOUT: ${e.message}"
                    )

                    if (attempt < 3) {

                        val delayMs =
                            when (attempt) {
                                1 -> 5_000L
                                2 -> 15_000L
                                else -> 30_000L
                            }

                        println(
                            "GEMINI TIMEOUT RETRY AFTER ${delayMs}ms"
                        )

                        delay(
                            delayMs
                        )
                    }

                } catch (e: java.net.ConnectException) {

                    lastError =
                        "Gemini connection error: ${e.message}"

                    println(
                        "GEMINI CONNECTION ERROR: ${e.message}"
                    )

                    if (attempt < 3) {

                        val delayMs =
                            when (attempt) {
                                1 -> 5_000L
                                2 -> 15_000L
                                else -> 30_000L
                            }

                        println(
                            "GEMINI CONNECTION RETRY AFTER ${delayMs}ms"
                        )

                        delay(
                            delayMs
                        )
                    }

                } catch (e: Exception) {

                    /*
                     * Lỗi không phải temporary:
                     * Không retry.
                     */

                    throw e
                }
            }

            /*
             * --------------------------------------------------------
             * MODEL HIỆN TẠI ĐÃ HẾT 3 ATTEMPT
             * --------------------------------------------------------
             */

            if (modelIndex < models.lastIndex) {

                println(
                    "=================================================="
                )

                println(
                    "GEMINI PRIMARY MODEL FAILED"
                )

                println(
                    "PRIMARY MODEL = $selectedModel"
                )

                println(
                    "SWITCHING TO FALLBACK MODEL = ${models[modelIndex + 1]}"
                )

                println(
                    "=================================================="
                )

                /*
                 * Không delay thêm ở đây.
                 *
                 * Đã chờ:
                 * 5s + 15s
                 *
                 * trước khi chuyển model.
                 */

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
        prompt: String,
        sexEducation: Boolean = false,
        selectedModelOverride: String? = null
    ): String {

        val selectedModel =
            selectedModelOverride
                ?: if (sexEducation) {
                    sexEducationModel
                } else {
                    model
                }

        val url =
            "https://generativelanguage.googleapis.com/v1beta/models/" +
                    "$selectedModel:generateContent?key=$key"

        println(
            "GEMINI MODEL = $selectedModel"
        )

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

            connection.connectTimeout = 20_000
            connection.readTimeout = 120_000

            connection.doOutput =
                true

            // ----------------------------------------------------
            // REQUEST BODY
            // ----------------------------------------------------

            val requestBody =
                buildJsonObject {

                    // ====================================================
                    // SYSTEM INSTRUCTION
                    // ====================================================

                    if (sexEducation) {

                        put(
                            "systemInstruction",
                            buildJsonObject {

                                put(
                                    "parts",
                                    buildJsonArray {

                                        add(
                                            buildJsonObject {

                                                put(
                                                    "text",
                                                    buildSexEducationSystemInstruction()
                                                )
                                            }
                                        )
                                    }
                                )
                            }
                        )
                    }

                    // ====================================================
                    // CONTENT
                    // ====================================================

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

                    // ====================================================
                    // SAFETY SETTINGS
                    // ====================================================

                    if (sexEducation) {

                        put(
                            "safetySettings",
                            buildJsonArray {

                                add(
                                    buildJsonObject {

                                        put(
                                            "category",
                                            "HARM_CATEGORY_SEXUALLY_EXPLICIT"
                                        )

                                        put(
                                            "threshold",
                                            "BLOCK_MEDIUM_AND_ABOVE"
                                        )
                                    }
                                )

                                add(
                                    buildJsonObject {

                                        put(
                                            "category",
                                            "HARM_CATEGORY_HARASSMENT"
                                        )

                                        put(
                                            "threshold",
                                            "BLOCK_LOW_AND_ABOVE"
                                        )
                                    }
                                )

                                add(
                                    buildJsonObject {

                                        put(
                                            "category",
                                            "HARM_CATEGORY_DANGEROUS_CONTENT"
                                        )

                                        put(
                                            "threshold",
                                            "BLOCK_MEDIUM_AND_ABOVE"
                                        )
                                    }
                                )
                            }
                        )
                    }

                    // ====================================================
                    // GENERATION CONFIG
                    // ====================================================

                    put(
                        "generationConfig",
                        buildJsonObject {

                            put(
                                "temperature",
                                if (sexEducation) {
                                    0.3
                                } else {
                                    0.7
                                }
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
    // NORMAL PROMPT
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
    // SEX EDUCATION SYSTEM INSTRUCTION
    // ============================================================

    private fun buildSexEducationSystemInstruction(): String {

        return """
        Bạn là một chuyên gia giáo dục giới tính và sức khỏe sinh sản
        cho học sinh.

        Nhiệm vụ của bạn là tạo nội dung GIÁO DỤC, KHOA HỌC,
        AN TOÀN và PHÙ HỢP VỚI ĐỘ TUỔI.

        Đây là hệ thống dành cho học sinh nên phải đặc biệt chú trọng
        đến việc bảo vệ trẻ em và thanh thiếu niên.

        ============================================================
        NGUYÊN TẮC BẮT BUỘC
        ============================================================

        1. Không tạo nội dung khiêu dâm.

        2. Không tạo nội dung nhằm kích thích tình dục.

        3. Không mô tả hành vi tình dục một cách chi tiết nếu
           chi tiết đó không cần thiết cho mục tiêu giáo dục.

        4. Không tạo tình huống tình dục với trẻ em.

        5. Không nhập vai tình dục.

        6. Không tạo hội thoại tình dục.

        7. Không yêu cầu học sinh cung cấp ảnh cơ thể.

        8. Không yêu cầu học sinh cung cấp ảnh vùng riêng tư.

        9. Không yêu cầu học sinh cung cấp video cá nhân.

        10. Không yêu cầu học sinh kể trải nghiệm tình dục cá nhân.

        11. Không yêu cầu thông tin riêng tư không cần thiết.

        12. Không hướng dẫn học sinh thực hiện hành vi tình dục.

        13. Không tạo nội dung có tính chất dụ dỗ hoặc khai thác
            trẻ em.

        14. Không phán xét học sinh.

        15. Không sử dụng ngôn ngữ gây xấu hổ hoặc kỳ thị.

        16. Luôn xem xét khối lớp trước khi quyết định mức độ
            kiến thức được trình bày.

        17. Nếu một yêu cầu vượt quá phạm vi phù hợp với độ tuổi,
            hãy chuyển hướng sang kiến thức khoa học, sức khỏe,
            an toàn và khuyến nghị trao đổi với người lớn đáng tin cậy.

        ============================================================
        NỘI DUNG ĐƯỢC ƯU TIÊN
        ============================================================

        - hiểu biết về cơ thể;
        - tuổi dậy thì;
        - vệ sinh cá nhân;
        - cảm xúc;
        - ranh giới cá nhân;
        - quyền từ chối;
        - sự đồng thuận;
        - tôn trọng người khác;
        - an toàn cá nhân;
        - an toàn trên Internet;
        - bảo vệ hình ảnh và thông tin riêng tư;
        - sức khỏe sinh sản phù hợp độ tuổi;
        - nhận biết hành vi không phù hợp;
        - phòng tránh nguy cơ;
        - tìm kiếm sự giúp đỡ.

        Nội dung phải phục vụ mục tiêu học tập,
        không phục vụ mục đích giải trí tình dục.
    """.trimIndent()
    }

    // ============================================================
    // SEX EDUCATION AGE SCOPE
    // ============================================================

    private fun getSexEducationScope(
        grade: Int
    ): String {

        return when (grade) {

            in 1..3 -> {

                """
                KHỐI 1-3

                Chỉ tập trung vào:

                - Nhận biết cơ thể ở mức phù hợp.
                - Cơ thể thuộc về mỗi người.
                - Vùng riêng tư.
                - Không chạm vào người khác khi họ không đồng ý.
                - Quyền nói "không".
                - Giữ khoảng cách an toàn.
                - Nhận biết người lớn đáng tin cậy.
                - Báo cho người lớn khi cảm thấy không an toàn.
                - Vệ sinh cá nhân cơ bản.

                Không đưa nội dung về hoạt động tình dục.
                """.trimIndent()
            }

            in 4..5 -> {

                """
                KHỐI 4-5

                Có thể tập trung vào:

                - Những thay đổi cơ thể khi lớn lên.
                - Tuổi dậy thì ở mức cơ bản.
                - Vệ sinh cá nhân.
                - Kinh nguyệt ở mức giáo dục phù hợp.
                - Những thay đổi cảm xúc.
                - Tôn trọng cơ thể.
                - Ranh giới cá nhân.
                - Quyền từ chối.
                - An toàn với người khác.
                - An toàn trên Internet.
                - Không chia sẻ ảnh hoặc thông tin riêng tư.

                Không mô tả hành vi tình dục chi tiết.
                """.trimIndent()
            }

            in 6..7 -> {

                """
                KHỐI 6-7

                Có thể tập trung vào:

                - Tuổi dậy thì.
                - Thay đổi thể chất.
                - Thay đổi tâm lý và cảm xúc.
                - Vệ sinh trong tuổi dậy thì.
                - Kinh nguyệt.
                - Ranh giới cá nhân.
                - Sự đồng thuận.
                - Tôn trọng cơ thể.
                - Quan hệ lành mạnh ở mức phù hợp.
                - An toàn trên Internet.
                - Bảo vệ hình ảnh cá nhân.
                - Nhận biết hành vi không phù hợp.
                - Tìm kiếm sự giúp đỡ.

                Nội dung phải mang tính giáo dục,
                không mô tả tình dục một cách chi tiết.
                """.trimIndent()
            }

            in 8..9 -> {

                """
                KHỐI 8-9

                Có thể tập trung vào:

                - Sức khỏe sinh sản.
                - Dậy thì.
                - Cơ thể và hormone ở mức giáo dục.
                - Kinh nguyệt và sức khỏe kinh nguyệt.
                - Sức khỏe sinh sản nam và nữ.
                - Đồng thuận.
                - Ranh giới cá nhân.
                - Quan hệ lành mạnh.
                - Trách nhiệm cá nhân.
                - Phòng tránh STI.
                - Phòng tránh mang thai ngoài ý muốn.
                - An toàn Internet.
                - Hình ảnh riêng tư và quyền riêng tư.
                - Nhận biết nguy cơ xâm hại.

                Giải thích khoa học nhưng không sử dụng
                mô tả tình dục mang tính kích thích.
                """.trimIndent()
            }

            else -> {

                """
                KHỐI 10-12

                Có thể tập trung vào:

                - Sức khỏe sinh sản.
                - Cơ chế sinh sản ở mức khoa học.
                - Sức khỏe tình dục có trách nhiệm.
                - Đồng thuận.
                - Ranh giới cá nhân.
                - Quan hệ lành mạnh.
                - Trách nhiệm trong quan hệ.
                - Phòng tránh thai.
                - STI và phòng ngừa.
                - Sức khỏe thể chất và tinh thần.
                - Quyền riêng tư.
                - An toàn Internet.
                - Nhận biết và phòng tránh xâm hại.
                - Khi nào cần tìm sự hỗ trợ y tế hoặc người lớn đáng tin cậy.

                Nội dung phải mang tính khoa học và giáo dục,
                không tạo nội dung khiêu dâm hoặc kích thích.
                """.trimIndent()
            }
        }
    }

    // ============================================================
    // SEX EDUCATION DIFFICULTY
    // ============================================================

    private fun getSexEducationDifficulty(
        difficulty: Difficulty
    ): String {

        return when (difficulty) {

            Difficulty.EASY -> {

                """
                EASY - DỄ:

                - Kiến thức cơ bản.
                - Câu hỏi trực tiếp.
                - Chủ yếu kiểm tra khả năng nhận biết và hiểu.
                - Không yêu cầu suy luận phức tạp.
                - Không dùng tình huống gây áp lực tâm lý.
                """.trimIndent()
            }

            Difficulty.MEDIUM -> {

                """
                MEDIUM - TRUNG BÌNH:

                - Yêu cầu học sinh hiểu và vận dụng kiến thức.
                - Có thể đưa ra tình huống giáo dục.
                - Học sinh cần lựa chọn cách xử lý phù hợp.
                - Có thể yêu cầu giải thích lý do.
                - Không đưa nội dung vượt phạm vi độ tuổi.
                """.trimIndent()
            }

            Difficulty.HARD -> {

                """
                HARD - KHÓ:

                - Yêu cầu vận dụng kiến thức.
                - Có thể sử dụng tình huống thực tế.
                - Có thể yêu cầu phân tích nguy cơ.
                - Có thể yêu cầu lựa chọn cách xử lý an toàn.
                - Có thể yêu cầu giải thích quyết định.
                - Tuyệt đối không làm câu hỏi khó bằng cách đưa
                  nội dung không phù hợp độ tuổi.
                """.trimIndent()
            }
        }
    }

    // ============================================================
    // SEX EDUCATION PROMPT
    // ============================================================

    private fun buildSexEducationPrompt(
        grade: Int,
        topic: String?,
        difficulty: Difficulty
    ): String {

        val scope =
            getSexEducationScope(
                grade = grade
            )

        val difficultyText =
            getSexEducationDifficulty(
                difficulty = difficulty
            )

        val topicText =
            if (topic.isNullOrBlank()) {

                "Tự chọn một chủ đề phù hợp với khối $grade."

            } else {

                "Chủ đề được yêu cầu: $topic"
            }

        return """
        Hãy tạo MỘT BÀI TẬP GIÁO DỤC GIỚI TÍNH
        cho học sinh Việt Nam lớp $grade.

        ============================================================
        PHẠM VI ĐỘ TUỔI
        ============================================================

        $scope

        ============================================================
        CHỦ ĐỀ
        ============================================================

        $topicText

        ============================================================
        ĐỘ KHÓ
        ============================================================

        ${difficulty.name}

        $difficultyText

        ============================================================
        MỤC TIÊU GIÁO DỤC
        ============================================================

        Bài tập phải giúp học sinh:

        - hiểu kiến thức;
        - biết bảo vệ bản thân;
        - biết tôn trọng người khác;
        - biết nhận biết ranh giới;
        - biết nhận biết nguy cơ;
        - biết tìm kiếm sự giúp đỡ khi cần.

        ============================================================
        QUY TẮC AN TOÀN
        ============================================================

        1. Không tạo nội dung khiêu dâm.

        2. Không tạo nội dung nhằm kích thích tình dục.

        3. Không mô tả hành vi tình dục một cách chi tiết nếu
           chi tiết đó không cần thiết cho mục tiêu giáo dục.

        4. Không tạo tình huống tình dục với trẻ em.

        5. Không yêu cầu học sinh kể trải nghiệm cá nhân.

        6. Không yêu cầu học sinh cung cấp ảnh, video hoặc
           thông tin riêng tư.

        7. Không yêu cầu học sinh mô tả cơ thể riêng tư của chính mình.

        8. Không tạo nội dung có tính chất dụ dỗ hoặc nhập vai.

        9. Không phán xét học sinh.

        10. Không sử dụng ngôn ngữ gây xấu hổ hoặc kỳ thị.

        11. Nếu có tình huống nguy hiểm, hướng học sinh tìm người
            lớn đáng tin cậy hoặc hỗ trợ phù hợp.

        ============================================================
        YÊU CẦU BÀI TẬP
        ============================================================

        - Đúng 3 câu.
        - Cùng một chủ đề.
        - Phù hợp lớp $grade.
        - Tổng điểm = 10.
        - Mỗi câu có điểm riêng.
        - Có đáp án.
        - Có hướng dẫn chấm.

        Ưu tiên các dạng:

        - nhận biết;
        - giải thích ngắn;
        - tình huống giáo dục;
        - lựa chọn cách xử lý an toàn;
        - nhận biết hành vi phù hợp và không phù hợp;
        - nhận biết khi nào cần tìm người lớn giúp đỡ.

        Không tạo câu hỏi yêu cầu học sinh chia sẻ
        thông tin riêng tư của bản thân.

        ============================================================
        ANSWER TYPE
        ============================================================

        Ưu tiên:

        TEXT + AI_TEXT

        hoặc:

        SPEECH_TO_TEXT + AI_TEXT

        HANDWRITING chỉ dùng khi thật sự có ý nghĩa.

        DRAWING chỉ dùng khi nội dung thực sự yêu cầu
        sơ đồ giáo dục.

        Không sử dụng EXACT cho câu hỏi mở có nhiều cách
        diễn đạt đúng.

        ============================================================
        JSON BẮT BUỘC
        ============================================================

        {
          "title": "Tên bài",

          "questions": [
            {
              "id": 1,
              "question": "Câu hỏi",
              "points": 3,
              "answerType": "TEXT",
              "gradingMethod": "AI_TEXT"
            },
            {
              "id": 2,
              "question": "Câu hỏi",
              "points": 3,
              "answerType": "TEXT",
              "gradingMethod": "AI_TEXT"
            },
            {
              "id": 3,
              "question": "Câu hỏi",
              "points": 4,
              "answerType": "TEXT",
              "gradingMethod": "AI_TEXT"
            }
          ],

          "answerKey": [
            {
              "id": 1,
              "answer": "Đáp án"
            },
            {
              "id": 2,
              "answer": "Đáp án"
            },
            {
              "id": 3,
              "answer": "Đáp án"
            }
          ],

          "gradingGuide": "Hướng dẫn chấm",

          "totalScore": 10
        }

        ============================================================
        JSON RULES
        ============================================================

        - Chỉ trả về JSON.
        - Không markdown.
        - Không ```json.
        - Không giải thích bên ngoài JSON.
        - Đúng 3 câu.
        - ID phải là 1, 2, 3.
        - answerKey phải có 3 phần tử.
        - Tổng points = 10.
        - totalScore = 10.
        - answerType phải hợp lệ.
        - gradingMethod phải hợp lệ.
        - answerType và gradingMethod phải tương thích.
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
            questions.map {
                it.id
            }

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
            answerKey.map {
                it.id
            }

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
            abs(
                pointsTotal - totalScore
            ) > 0.01
        ) {

            throw IllegalStateException(
                "Question points total $pointsTotal != totalScore $totalScore"
            )
        }

        if (
            abs(
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
    // VALIDATE SEX EDUCATION ASSIGNMENT
    // ============================================================

    private fun validateSexEducationAssignment(
        assignment: GeneratedAssignment,
        grade: Int
    ) {

        println(
            "========== VALIDATE SEX EDUCATION =========="
        )

        println(
            "GRADE = $grade"
        )

        println(
            "QUESTIONS = ${assignment.questions.size}"
        )

        // --------------------------------------------------------
        // Đúng 3 câu
        // --------------------------------------------------------

        if (assignment.questions.size != 3) {

            throw IllegalStateException(
                "Sex education assignment must contain exactly 3 questions"
            )
        }

        // --------------------------------------------------------
        // Đúng 3 đáp án
        // --------------------------------------------------------

        if (assignment.answerKey.size != 3) {

            throw IllegalStateException(
                "Sex education assignment must contain exactly 3 answers"
            )
        }

        // --------------------------------------------------------
        // Tổng điểm
        // --------------------------------------------------------

        if (
            abs(
                assignment.totalScore - 10.0
            ) > 0.01
        ) {

            throw IllegalStateException(
                "Sex education assignment total score must be 10"
            )
        }

        // --------------------------------------------------------
        // Kiểm tra từng câu
        // --------------------------------------------------------

        assignment.questions.forEach { question ->

            if (question.question.isBlank()) {

                throw IllegalStateException(
                    "Sex education question ${question.id} is empty"
                )
            }

            /*
             * Giáo dục giới tính ưu tiên AI_TEXT.
             *
             * Không nên dùng EXACT cho câu hỏi mở vì học sinh
             * có thể diễn đạt đúng bằng nhiều cách.
             */

            if (
                question.answerType == AnswerType.TEXT &&
                question.gradingMethod == GradingMethod.EXACT
            ) {

                throw IllegalStateException(
                    "Sex education should not use EXACT grading for open text"
                )
            }
        }

        println(
            "SEX EDUCATION VALIDATION PASSED"
        )

        println(
            "============================================"
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

        val sexEducation =
            isSexEducation(
                assignment.subject
            )

        println(
            "========== AI GRADE ASSIGNMENT =========="
        )

        println(
            "SUBJECT = ${assignment.subject}"
        )

        println(
            "SEX EDUCATION = $sexEducation"
        )

        println(
            "GRADE = ${assignment.grade}"
        )

        println(
            "=========================================="
        )

        val prompt =
            if (sexEducation) {

                buildSexEducationGradingPrompt(
                    assignment = assignment,
                    studentAnswer = studentAnswer
                )

            } else {

                buildGradingPrompt(
                    assignment = assignment,
                    studentAnswer = studentAnswer
                )
            }

        return withContext(Dispatchers.IO) {

            val key =
                if (sexEducation) {

                    sexEducationApiKey
                        ?: apiKey

                } else {

                    apiKey
                }
                    ?: throw IllegalStateException(
                        if (sexEducation) {
                            "GEMINI_SEX_EDUCATION_API_KEY or GEMINI_API_KEY is not configured"
                        } else {
                            "GEMINI_API_KEY is not configured"
                        }
                    )

            val selectedModel =
                if (sexEducation) {

                    sexEducationModel

                } else {

                    model
                }

            val url =
                "https://generativelanguage.googleapis.com/v1beta/models/" +
                        "$selectedModel:generateContent?key=$key"

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

                // ------------------------------------------------
                // REQUEST BODY
                // ------------------------------------------------

                val requestBody =
                    buildJsonObject {

                        // ============================================
                        // SYSTEM INSTRUCTION
                        // ============================================

                        if (sexEducation) {

                            put(
                                "systemInstruction",
                                buildJsonObject {

                                    put(
                                        "parts",
                                        buildJsonArray {

                                            add(
                                                buildJsonObject {

                                                    put(
                                                        "text",
                                                        buildSexEducationGradingSystemInstruction()
                                                    )
                                                }
                                            )
                                        }
                                    )
                                }
                            )
                        }

                        // ============================================
                        // CONTENT
                        // ============================================

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

                        // ============================================
                        // SAFETY SETTINGS
                        // ============================================

                        if (sexEducation) {

                            put(
                                "safetySettings",
                                buildJsonArray {

                                    add(
                                        buildJsonObject {

                                            put(
                                                "category",
                                                "HARM_CATEGORY_SEXUALLY_EXPLICIT"
                                            )

                                            put(
                                                "threshold",
                                                "BLOCK_MEDIUM_AND_ABOVE"
                                            )
                                        }
                                    )

                                    add(
                                        buildJsonObject {

                                            put(
                                                "category",
                                                "HARM_CATEGORY_HARASSMENT"
                                            )

                                            put(
                                                "threshold",
                                                "BLOCK_LOW_AND_ABOVE"
                                            )
                                        }
                                    )

                                    add(
                                        buildJsonObject {

                                            put(
                                                "category",
                                                "HARM_CATEGORY_DANGEROUS_CONTENT"
                                            )

                                            put(
                                                "threshold",
                                                "BLOCK_MEDIUM_AND_ABOVE"
                                            )
                                        }
                                    )
                                }
                            )
                        }

                        // ============================================
                        // GENERATION CONFIG
                        // ============================================

                        put(
                            "generationConfig",
                            buildJsonObject {

                                put(
                                    "temperature",
                                    if (sexEducation) {
                                        0.2
                                    } else {
                                        0.2
                                    }
                                )

                                put(
                                    "responseMimeType",
                                    "application/json"
                                )
                            }
                        )
                    }

                // ------------------------------------------------
                // SEND
                // ------------------------------------------------

                connection.outputStream
                    .bufferedWriter()
                    .use {

                        it.write(
                            requestBody.toString()
                        )
                    }

                // ------------------------------------------------
                // RESPONSE
                // ------------------------------------------------

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
    // NORMAL GRADING PROMPT
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
    // SEX EDUCATION GRADING SYSTEM INSTRUCTION
    // ============================================================

    private fun buildSexEducationGradingSystemInstruction(): String {

        return """
        Bạn là giáo viên đang chấm bài GIÁO DỤC GIỚI TÍNH
        cho học sinh.

        Hãy đánh giá kiến thức và khả năng xử lý tình huống
        giáo dục của học sinh.

        ============================================================
        NGUYÊN TẮC
        ============================================================

        1. Chỉ đánh giá nội dung câu trả lời.

        2. Không đánh giá hoặc suy đoán đời sống riêng tư
           của học sinh.

        3. Không yêu cầu học sinh tiết lộ trải nghiệm cá nhân.

        4. Không phán xét học sinh.

        5. Không làm học sinh xấu hổ.

        6. Nếu học sinh diễn đạt khác đáp án nhưng kiến thức đúng,
           vẫn phải cho điểm.

        7. Nếu học sinh chưa hiểu, feedback phải giải thích
           ngắn gọn kiến thức đúng.

        8. Nếu câu hỏi liên quan đến an toàn, ưu tiên đánh giá
           khả năng nhận biết nguy cơ và tìm kiếm sự giúp đỡ.

        9. Feedback phải phù hợp với độ tuổi.

        10. Không tạo nội dung khiêu dâm hoặc mô tả tình dục
            không cần thiết cho việc chấm bài.
    """.trimIndent()
    }

    // ============================================================
    // SEX EDUCATION GRADING PROMPT
    // ============================================================

    private fun buildSexEducationGradingPrompt(
        assignment: AssignmentService.AssignmentResult,
        studentAnswer: String
    ): String {

        return """
        Bạn là giáo viên đang chấm bài GIÁO DỤC GIỚI TÍNH
        cho học sinh lớp ${assignment.grade}.

        Đây là nội dung giáo dục trẻ em/thanh thiếu niên.

        Việc chấm phải tập trung vào KIẾN THỨC, SỨC KHỎE
        và AN TOÀN, không đánh giá hoặc phán xét đời sống
        riêng tư của học sinh.

        ============================================================
        BÀI TẬP
        ============================================================

        ${assignment.content}

        ============================================================
        ĐÁP ÁN
        ============================================================

        ${assignment.answerKey}

        ============================================================
        HƯỚNG DẪN CHẤM
        ============================================================

        ${assignment.gradingGuide}

        ============================================================
        BÀI LÀM CỦA HỌC SINH
        ============================================================

        $studentAnswer

        ============================================================
        QUY TẮC CHẤM
        ============================================================

        1. Chỉ chấm kiến thức thể hiện trong bài.

        2. Không yêu cầu học sinh tiết lộ trải nghiệm cá nhân.

        3. Không suy đoán đời sống hoặc hành vi riêng tư
           của học sinh.

        4. Nếu học sinh dùng cách diễn đạt khác nhưng thể hiện
           đúng kiến thức, vẫn cho điểm.

        5. Nếu câu trả lời có nhiều ý đúng, đánh giá từng ý.

        6. Nếu học sinh hiểu sai kiến thức, giải thích ngắn gọn
           kiến thức đúng.

        7. Nếu câu hỏi về tình huống an toàn, ưu tiên đánh giá
           khả năng nhận biết nguy cơ và tìm kiếm sự giúp đỡ.

        8. Không phán xét.

        9. Không sử dụng ngôn ngữ gây xấu hổ.

        10. Feedback phải phù hợp với học sinh lớp ${assignment.grade}.

        11. Không tự ý thay đổi tổng điểm.

        12. Không đánh giá học sinh dựa trên việc các em có
            trải nghiệm cá nhân hay không.

        Điểm tối đa: ${assignment.totalScore}

        ============================================================
        OUTPUT
        ============================================================

        Chỉ trả về JSON:

        {
          "score": 8.5,
          "feedback": "Em đã hiểu đúng các ý chính..."
        }

        Không markdown.
        Không ```json.
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
