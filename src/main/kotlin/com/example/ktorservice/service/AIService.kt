package com.example.ktorservice.service

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.util.Locale
import kotlin.math.abs

class AIService {


    private val sourceReferencePatterns = listOf(
        // ----- Các pattern cũ -----
        Regex("""\bđoạn\s+văn\s+(trên|dưới|sau|đây)\b""", RegexOption.IGNORE_CASE),
        Regex("""\bbài\s+(đọc|học)\s+(trên|dưới|sau|đây)\b""", RegexOption.IGNORE_CASE),
        Regex("""\bnội\s+dung\s+(trên|dưới|sau|đây)\b""", RegexOption.IGNORE_CASE),
        Regex("""\bbảng\s+(trên|dưới|sau|đây)\b""", RegexOption.IGNORE_CASE),
        Regex("""\bhình\s+(trên|dưới|sau|đây)\b""", RegexOption.IGNORE_CASE),
        Regex("""\bdựa\s+vào\s+(đoạn|bài|nội\s+dung|bảng|hình)\b""", RegexOption.IGNORE_CASE),
        Regex("""\bđọc\s+(đoạn\s+văn|bài\s+đọc)\b""", RegexOption.IGNORE_CASE),
        Regex("""\btheo\s+(nội\s+dung|bài\s+học|đoạn\s+văn)\b""", RegexOption.IGNORE_CASE),

        // ----- MỚI: đại từ chỉ định sau tên thể loại -----
        // "truyện này", "bài thơ sau", "đoạn trích dưới"...
        Regex(
            """\b(?i:câu\s+chuyện|truyện|bài\s+thơ|đoạn\s+thơ|bài\s+văn|đoạn\s+trích|tác\s+phẩm|bài\s+đọc)\s+(?:trên|dưới|sau|đây|này|kia|đó)\b"""
        ),

        // ----- MỚI: gọi tên tác phẩm cụ thể -----
        // "câu chuyện Rùa và Thỏ", "truyện Thánh Gióng",
        // "bài thơ Lượm", "từ câu chuyện rùa và thỏ..."
        // Bỏ qua các đại từ/động từ chung để tránh false positive.
        Regex(
            """\b(?i:câu\s+chuyện|truyện|bài\s+thơ|đoạn\s+thơ|bài\s+văn|đoạn\s+trích|tác\s+phẩm|bài\s+đọc)\s+(?:kể\s+về\s+|về\s+|của\s+)?(?!nào\b|gì\b|em\b|bạn\b|con\b|mình\b|chúng\b|hãy\b|kể\b|viết\b|bản\s+thân\b|ai\b|đó\b|này\b|kia\b|trên\b|dưới\b|sau\b|đây\b|trước\b)\S"""
        ),

        // ----- MỚI: tên tác phẩm được trích dẫn trong ngoặc kép -----
        Regex(
            """\b(?i:câu\s+chuyện|truyện|bài\s+thơ|đoạn\s+thơ|bài\s+văn|đoạn\s+trích|tác\s+phẩm|bài\s+đọc)\s*[\u0022\u201C\u201D]"""
        ),
)

// ============================================================
// PUBLIC API / MODELS
// ============================================================

    @Serializable
    enum class QuestionSourceType {
        SELF_CONTAINED,
        LESSON_CONTENT,
        READING_PASSAGE
    }

    @Serializable
    enum class AnswerType {
        TEXT,
        HANDWRITING,
        DRAWING,
        SPEECH_TO_TEXT,
        MIXED
    }

    @Serializable
    enum class Difficulty {
        EASY,
        MEDIUM,
        HARD
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
    enum class RuleGradingMethod {
        EXACT,
        NUMERIC,
        REQUIRED_CONCEPTS,
        STEP_RUBRIC
    }

    @Serializable
    enum class RubricCriterionMethod {
        EVIDENCE,
        REQUIRED_CONCEPTS,
        FINAL_NUMERIC
    }

    @Serializable
    enum class MathAnswerKind {
        AUTO,
        NUMBER,
        CALCULATION,
        FILL_BLANK,
        MULTIPLE_CHOICE,
        TRUE_FALSE_SET,
        ORDERED_TUPLE,
        UNORDERED_SET,
        QUANTITY,
        SYMBOLIC_EXPRESSION
    }

    @Serializable
    data class MathAnswerSpec(
        val kind: MathAnswerKind = MathAnswerKind.AUTO,
        val expectedUnit: String? = null,
        val absoluteTolerance: Double = 0.0,
        val relativeTolerance: Double = 0.0,
        val requireEquationConsistency: Boolean = true
    )

    @Serializable
    data class RubricCriterion(
        val id: String,
        val description: String,
        val points: Double,
        val method: RubricCriterionMethod,
        val acceptedEvidence: List<String> = emptyList(),
        val requiredConcepts: List<String> = emptyList(),
        val expectedNumber: String? = null,
        val numericTolerance: Double = 0.0
    )

    @Serializable
    data class GradingRubric(
        val version: Int = 1,
        val criteria: List<RubricCriterion> = emptyList()
    )

    @Serializable
    data class GradingSpec(
        val method: RuleGradingMethod = RuleGradingMethod.EXACT,
        val correctAnswer: String = "",
        val acceptedAnswers: List<String> = emptyList(),
        val requiredConcepts: List<String> = emptyList(),
        val caseSensitive: Boolean = false,
        val ignoreWhitespace: Boolean = true,
        val ignorePunctuation: Boolean = false,
        val numericTolerance: Double = 0.0,
        val allowPartialCredit: Boolean = false,
        val rubric: GradingRubric? = null,
        val mathAnswerSpec: MathAnswerSpec? = null
    )

    enum class SubjectType {
        NORMAL,
        SEX_EDUCATION
    }

    @Serializable
    data class GeneratedQuestion(
        val id: Int,
        val question: String,
        val learningObjective: String,
        val points: Double,
        val answerType: AnswerType,
        val gradingMethod: GradingMethod,
        val sourceType: QuestionSourceType =
            QuestionSourceType.SELF_CONTAINED,
        val gradingSpec: GradingSpec = GradingSpec(),
        val options: List<String> = emptyList(),
        val statements: List<String> = emptyList()
    )

    @Serializable
    data class GeneratedAnswer(
        val id: Int,
        val answer: String
    )

    @Serializable
    data class GeneratedAssignment(
        val title: String,
        val learningMaterial: String? = null,
        val questions: List<GeneratedQuestion>,
        val answerKey: List<GeneratedAnswer>,
        val gradingGuide: String,
        val totalScore: Double
    )

    @Serializable
    data class AssignmentQualityReview(
        val pass: Boolean,
        val issues: List<String> = emptyList(),
        val summary: String = ""
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

// ============================================================
// INTERNAL BLUEPRINT
// ============================================================

    private enum class CognitiveLevel {
        RECALL,
        UNDERSTAND,
        APPLY,
        REASON
    }

    private enum class QuestionStrategy {
        DIRECT,
        EXPLANATION,
        WORD_PROBLEM,
        COMPARISON,
        REAL_WORLD,
        MULTI_STEP,
        ERROR_ANALYSIS,
        CAUSE_EFFECT,
        DECISION,
        PATTERN,
        EVIDENCE
    }

    private data class BlueprintQuestion(
        val id: Int,
        val cognitiveLevel: CognitiveLevel,
        val strategy: QuestionStrategy,
        val purpose: String,
        val avoid: String
    )

    private data class GenerationBlueprint(
        val subject: String,
        val grade: Int,
        val topic: String,
        val difficulty: Difficulty,
        val questions: List<BlueprintQuestion>
    )

// ============================================================
// GEMINI CONFIG
// ============================================================

    private val apiKey: String?
        get() = System.getenv("GEMINI_API_KEY")

    private val sexEducationApiKey: String?
        get() = System.getenv("GEMINI_SEX_EDUCATION_API_KEY")

    private val model: String
        get() = System.getenv("GEMINI_MODEL")
            ?: "gemini-3.5-flash-lite"

    private val sexEducationModel: String
        get() = System.getenv("GEMINI_SEX_EDUCATION_MODEL")
            ?: model

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
    }

// ============================================================
// SUBJECT
// ============================================================

    private fun getSubjectType(subject: String): SubjectType {

        val normalized = subject
            .trim()
            .lowercase()
            .replace("-", " ")
            .replace("_", " ")
            .replace(Regex("\\s+"), " ")

        val aliases = setOf(
            "giao duc gioi tinh",
            "giao duc suc khoe sinh san",
            "suc khoe sinh san",
            "gioi tinh",
            "sex education",
            "sexual health",
            "reproductive health",
            "sexual education"
        )

        return if (normalized in aliases) {
            SubjectType.SEX_EDUCATION
        } else {
            SubjectType.NORMAL
        }
    }

    private fun isSexEducation(subject: String): Boolean =
        getSubjectType(subject) == SubjectType.SEX_EDUCATION

// ============================================================
// GENERATION
// ============================================================

    suspend fun generateAssignment(
        grade: Int,
        subject: String,
        topic: String?,
        difficulty: Difficulty,
        previousAssignments: List<String> = emptyList(),
        qualityFeedback: String? = null,
        learningStepTitle: String? = null,
        learningStepSkill: String? = null,
        learningStepDescription: String? = null
    ): GeneratedAssignment {

        println(
            "[AIService] generateAssignment " +
                    "grade=$grade subject=$subject topic=$topic " +
                    "difficulty=$difficulty " +
                    "learningStep=${learningStepTitle ?: "none"} " +
                    "previous=${previousAssignments.size}"
        )

        require(grade in 1..12) {
            "Grade must be between 1 and 12"
        }

        val sexEducation = isSexEducation(subject)

        val prompt = if (sexEducation) {
            buildSexEducationPrompt(
                grade = grade,
                subject = subject,
                topic = topic,
                difficulty = difficulty,
                previousAssignments = previousAssignments,
                qualityFeedback = qualityFeedback,
                learningStepTitle = learningStepTitle,
                learningStepSkill = learningStepSkill,
                learningStepDescription = learningStepDescription
            )
        } else {
            buildPrompt(
                grade = grade,
                subject = subject,
                topic = topic,
                difficulty = difficulty,
                previousAssignments = previousAssignments,
                qualityFeedback = qualityFeedback,
                learningStepTitle = learningStepTitle,
                learningStepSkill = learningStepSkill,
                learningStepDescription = learningStepDescription
            )
        }

        val response = withContext(Dispatchers.IO) {
            callGeminiWithRetry(
                prompt = prompt,
                sexEducation = sexEducation
            )
        }

        val parsedAssignment = parseResponse(response)
        val assignment = if (
            !sexEducation && isMathSubject(subject) && grade in 1..12
        ) {
            repairInvalidMathChoices(
                assignment = parsedAssignment,
                grade = grade,
                subject = subject
            )
        } else {
            parsedAssignment
        }

        if (sexEducation) {
            validateSexEducationAssignment(
                assignment = assignment,
                grade = grade
            )
        }

        validateParsedAssignment(
            assignment = assignment,
            grade = grade,
            subject = subject,
            difficulty = difficulty
        )

        return assignment
    }

    /** Repairs only malformed MCQ option arrays before rejecting the full assignment. */
    private suspend fun repairInvalidMathChoices(
        assignment: GeneratedAssignment,
        grade: Int,
        subject: String
    ): GeneratedAssignment {
        val choiceCount = when (grade) {
            in 1..5 -> 10
            in 6..9 -> 11
            else -> 12
        }
        val answersById = assignment.answerKey.associateBy { it.id }
        val invalidQuestions = assignment.questions.filter { question ->
            val hasValidAnswerLetter =
                answersById[question.id]?.answer?.trim()?.uppercase(Locale.ROOT) in
                    setOf("A", "B", "C", "D")
            question.id <= choiceCount && hasValidAnswerLetter && (
                question.options.size != 4 ||
                    question.options.any { it.isBlank() } ||
                    question.options.map(::normalizeSemanticText).distinct().size != 4
                )
        }
        if (invalidQuestions.isEmpty()) return assignment

        val questionsById = invalidQuestions.associateBy { it.id }
        val unresolvedIds = invalidQuestions.map { it.id }.toMutableSet()
        val optionsToRepair = invalidQuestions.associate { it.id to it.options }.toMutableMap()
        val repairedOptions = mutableMapOf<Int, List<String>>()

        fun optionError(options: List<String>?): String = when {
            options == null -> "API không trả về options"
            options.size != 4 -> "có ${options.size} lựa chọn, cần 4"
            options.any { it.isBlank() } -> "có lựa chọn rỗng"
            options.map(::normalizeSemanticText).distinct().size != 4 -> "có lựa chọn trùng"
            else -> ""
        }

        for (repairAttempt in 1..2) {
            if (unresolvedIds.isEmpty()) break

            val questionsToRepair = buildJsonArray {
                unresolvedIds.sorted().forEach { id ->
                    val question = questionsById.getValue(id)
                    add(buildJsonObject {
                        put("id", id)
                        put("question", question.question)
                        put("correctOption", answersById[id]?.answer.orEmpty())
                        put("previousOptions", buildJsonArray {
                            optionsToRepair[id].orEmpty().forEach { option -> add(JsonPrimitive(option)) }
                        })
                        put("previousError", optionError(optionsToRepair[id]))
                    })
                }
            }
            val prompt = """
                Bạn là biên tập viên đề Toán lớp $grade, môn $subject.
                Đây là lượt sửa $repairAttempt cho các câu trắc nghiệm còn lỗi.
                Với MỖI ID được yêu cầu, bắt buộc trả đúng 4 chuỗi phương án khác nhau,
                không được rỗng, không lặp, không thêm chữ A./B./C./D. vào nội dung.
                Mỗi phương án phải là nội dung cụ thể phù hợp câu hỏi; tuyệt đối không dùng
                dấu ba chấm, "Lựa chọn A", "Phương án 1" hoặc văn bản giữ chỗ.
                correctOption là vị trí đáp án đúng; phải tự giải câu hỏi và đặt đáp án đúng
                vào đúng vị trí đó. Không chép lại bộ previousOptions nếu previousError báo lỗi.
                Chỉ trả một JSON object có trường "questions" là mảng. Mỗi phần tử trong mảng
                có "id" là ID đầu vào và "options" là mảng gồm đúng bốn chuỗi nội dung khác nhau.
                Phải có đủ mọi ID trong đầu vào, không thêm lời dẫn hoặc markdown.

                Câu cần sửa:
                $questionsToRepair
            """.trimIndent()

            val response = callGeminiWithRetry(
                prompt = prompt,
                sexEducation = false,
                temperature = 0.2
            )
            val responseQuestions = parseGeminiJsonResponse(response).jsonObject["questions"]?.jsonArray
                ?: throw IllegalStateException("Choice repair response is missing questions")
            val returnedOptions = responseQuestions.associate { element ->
                val item = element.jsonObject
                val id = item["id"]?.jsonPrimitive?.intOrNull
                    ?: throw IllegalStateException("Choice repair response is missing a question id")
                val options = item["options"]?.jsonArray
                    ?.map { it.jsonPrimitive.content.trim() }
                id to options
            }

            unresolvedIds.toList().forEach { id ->
                val options = returnedOptions[id]
                if (optionError(options).isEmpty() && options != null) {
                    repairedOptions[id] = options
                    unresolvedIds.remove(id)
                } else if (options != null) {
                    optionsToRepair[id] = options
                }
            }
        }

        if (unresolvedIds.isNotEmpty()) {
            val details = unresolvedIds.sorted().joinToString("; ") { id ->
                "Q$id: ${optionError(optionsToRepair[id])}"
            }
            throw IllegalStateException("Choice repair failed after 2 focused attempts: $details")
        }

        println("[AIService] Repaired multiple-choice options for question IDs ${repairedOptions.keys.sorted()}")
        return assignment.copy(questions = assignment.questions.map { question ->
            question.copy(options = repairedOptions[question.id] ?: question.options)
        })
    }

    private fun isMathSubject(subject: String): Boolean =
        subject.contains("toán", ignoreCase = true) || subject.contains("math", ignoreCase = true)

// ============================================================
// BLUEPRINT
// ============================================================

    private fun createBlueprint(
        grade: Int,
        subject: String,
        topic: String?,
        difficulty: Difficulty,
        learningStepTitle: String? = null,
        learningStepSkill: String? = null,
        learningStepDescription: String? = null
    ): GenerationBlueprint {

        val normalizedSubject = subject.trim().lowercase()

        val topicText = topic
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: "nội dung phù hợp chương trình lớp $grade"
        val learningStepText =
            learningStepSkill
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: learningStepTitle
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
        val isMath =
            normalizedSubject.contains("toán") ||
                    normalizedSubject.contains("math")

        val isLanguage =
            normalizedSubject.contains("ngữ văn") ||
                    normalizedSubject.contains("văn") ||
                    normalizedSubject.contains("literature") ||
                    normalizedSubject.contains("tiếng việt")

        val isEnglish =
            normalizedSubject.contains("anh") ||
                    normalizedSubject.contains("english")

        val baseQuestions = when {
            isMath -> createMathBlueprint(difficulty)

            isLanguage -> listOf(
                BlueprintQuestion(
                    1,
                    CognitiveLevel.UNDERSTAND,
                    QuestionStrategy.EVIDENCE,
                    "Xác định và giải thích nội dung hoặc ý nghĩa chính.",
                    "Không chỉ chép lại câu hoặc định nghĩa."
                ),
                BlueprintQuestion(
                    2,
                    CognitiveLevel.APPLY,
                    QuestionStrategy.COMPARISON,
                    "Vận dụng kiến thức để phân tích, so sánh hoặc giải thích trường hợp cụ thể.",
                    "Không lặp lại thao tác của câu 1."
                ),
                BlueprintQuestion(
                    3,
                    CognitiveLevel.REASON,
                    QuestionStrategy.EXPLANATION,
                    "Phân tích, lập luận hoặc đưa ra nhận xét có căn cứ.",
                    "Không biến thành câu hỏi nhớ lại."
                )
            )

            isEnglish -> listOf(
                BlueprintQuestion(
                    1,
                    CognitiveLevel.UNDERSTAND,
                    QuestionStrategy.DIRECT,
                    "Kiểm tra kiến thức/ngôn ngữ cốt lõi.",
                    "Không dùng cấu trúc ngoài trình độ."
                ),
                BlueprintQuestion(
                    2,
                    CognitiveLevel.APPLY,
                    QuestionStrategy.REAL_WORLD,
                    "Đưa kiến thức vào tình huống giao tiếp hoặc ngữ cảnh cụ thể.",
                    "Không chỉ thay vài từ của câu 1."
                ),
                BlueprintQuestion(
                    3,
                    CognitiveLevel.REASON,
                    QuestionStrategy.ERROR_ANALYSIS,
                    "Phát hiện, sửa hoặc giải thích lỗi.",
                    "Không dùng lỗi giả tạo."
                )
            )

            else -> listOf(
                BlueprintQuestion(
                    1,
                    CognitiveLevel.UNDERSTAND,
                    QuestionStrategy.DIRECT,
                    "Kiểm tra kiến thức nền tảng và khả năng giải thích.",
                    "Không chỉ yêu cầu học thuộc."
                ),
                BlueprintQuestion(
                    2,
                    CognitiveLevel.APPLY,
                    QuestionStrategy.REAL_WORLD,
                    "Vận dụng kiến thức vào tình huống cụ thể.",
                    "Không lặp lại dữ kiện hoặc cách giải Q1."
                ),
                BlueprintQuestion(
                    3,
                    CognitiveLevel.REASON,
                    QuestionStrategy.CAUSE_EFFECT,
                    "Phân tích nguyên nhân, hậu quả, bằng chứng hoặc lựa chọn.",
                    "Không hỏi lại cùng kiến thức."
                )
            )
        }

        val questions = if (!isMath) baseQuestions else {
            val count = mathQuestionCount(grade)
            val levels = when (grade) {
                in 1..5 -> List(5) { CognitiveLevel.RECALL } +
                        List(4) { CognitiveLevel.UNDERSTAND } + CognitiveLevel.APPLY
                in 6..9 -> List(6) { CognitiveLevel.RECALL } +
                        List(6) { CognitiveLevel.UNDERSTAND } +
                        List(3) { CognitiveLevel.APPLY } + CognitiveLevel.REASON
                else -> List(12) { CognitiveLevel.UNDERSTAND } +
                        List(4) { CognitiveLevel.APPLY } + List(6) { CognitiveLevel.REASON }
            }
            (1..count).map { index ->
                val template = baseQuestions[(index - 1) % baseQuestions.size]
                template.copy(
                    id = index,
                    cognitiveLevel = levels[index - 1],
                    purpose = "Thiết kế câu $index theo ma trận Toán lớp $grade, đúng mức độ nhận thức và chủ đề được phân bổ.",
                    avoid = "Không lặp lại câu khác; không vượt chương trình lớp $grade."
                )
            }
        }

        return GenerationBlueprint(
            subject = subject,
            grade = grade,
            topic = topicText,
            difficulty = difficulty,
            questions = questions
        )
    }

    private fun createMathBlueprint(
        difficulty: Difficulty
    ): List<BlueprintQuestion> {

        return when (difficulty) {

            Difficulty.EASY -> listOf(
                BlueprintQuestion(
                    1,
                    CognitiveLevel.UNDERSTAND,
                    QuestionStrategy.DIRECT,
                    "Kiểm tra kỹ năng toán nền tảng.",
                    "Không chỉ đổi số."
                ),
                BlueprintQuestion(
                    2,
                    CognitiveLevel.APPLY,
                    QuestionStrategy.WORD_PROBLEM,
                    "Áp dụng kiến thức vào bài toán có ngữ cảnh.",
                    "Không dùng đúng mô hình câu 1."
                ),
                BlueprintQuestion(
                    3,
                    CognitiveLevel.REASON,
                    QuestionStrategy.ERROR_ANALYSIS,
                    "Phát hiện và sửa lỗi hoặc giải thích đúng/sai.",
                    "Không biến thành phép tính lặp."
                )
            )

            Difficulty.MEDIUM -> listOf(
                BlueprintQuestion(
                    1,
                    CognitiveLevel.UNDERSTAND,
                    QuestionStrategy.EXPLANATION,
                    "Kiểm tra hiểu bản chất.",
                    "Không chỉ yêu cầu đáp số."
                ),
                BlueprintQuestion(
                    2,
                    CognitiveLevel.APPLY,
                    QuestionStrategy.WORD_PROBLEM,
                    "Vận dụng vào tình huống thực tế.",
                    "Không chỉ thay số."
                ),
                BlueprintQuestion(
                    3,
                    CognitiveLevel.REASON,
                    QuestionStrategy.MULTI_STEP,
                    "Giải quyết bài toán nhiều bước.",
                    "Không yêu cầu kiến thức vượt chương trình."
                )
            )

            Difficulty.HARD -> listOf(
                BlueprintQuestion(
                    1,
                    CognitiveLevel.APPLY,
                    QuestionStrategy.WORD_PROBLEM,
                    "Vận dụng kiến thức trọng tâm.",
                    "Không dùng kiến thức ngoài chương trình."
                ),
                BlueprintQuestion(
                    2,
                    CognitiveLevel.REASON,
                    QuestionStrategy.MULTI_STEP,
                    "Kết hợp nhiều bước hoặc đại lượng.",
                    "Không chỉ tăng số phép tính."
                ),
                BlueprintQuestion(
                    3,
                    CognitiveLevel.REASON,
                    QuestionStrategy.ERROR_ANALYSIS,
                    "Phân tích cách giải hoặc chiến lược giải.",
                    "Không đánh đố."
                )
            )
        }
    }

    private fun mathQuestionCount(grade: Int): Int = when (grade) {
        in 1..5 -> 10
        in 6..9 -> 16
        else -> 22
    }

    private fun mathQuestionPoints(grade: Int, questionId: Int): Double = when (grade) {
        in 1..5 -> 1.0
        in 6..9 -> when (questionId) {
            in 1..7 -> 0.5
            in 8..9 -> 1.0
            in 10..14 -> 0.6
            else -> 0.75
        }
        else -> when (questionId) {
            in 1..12 -> 0.25
            in 13..16 -> 1.0
            else -> 0.5
        }
    }

    private fun createSexEducationBlueprint(
        grade: Int,
        topic: String,
        difficulty: Difficulty
    ): GenerationBlueprint {

        return GenerationBlueprint(
            subject = "Giáo dục sức khỏe giới tính",
            grade = grade,
            topic = topic,
            difficulty = difficulty,
            questions = listOf(
                BlueprintQuestion(
                    1,
                    CognitiveLevel.UNDERSTAND,
                    QuestionStrategy.DIRECT,
                    "Hiểu kiến thức nền tảng phù hợp độ tuổi.",
                    "Không hỏi trải nghiệm cá nhân."
                ),
                BlueprintQuestion(
                    2,
                    CognitiveLevel.APPLY,
                    QuestionStrategy.DECISION,
                    "Vận dụng kiến thức vào tình huống an toàn.",
                    "Không tạo tình huống nhạy cảm không cần thiết."
                ),
                BlueprintQuestion(
                    3,
                    CognitiveLevel.REASON,
                    QuestionStrategy.CAUSE_EFFECT,
                    "Phân tích tình huống và giải thích lựa chọn an toàn.",
                    "Không yêu cầu tiết lộ đời sống riêng tư."
                )
            )
        )
    }

    private fun blueprintAsPrompt(
        blueprint: GenerationBlueprint
    ): String {

        return buildString {
            appendLine("=== GENERATION BLUEPRINT V2 ===")
            appendLine(
                "Blueprint là ràng buộc nội bộ, không đưa vào JSON output."
            )
            appendLine()

            blueprint.questions.forEach { q ->
                appendLine("Q${q.id}:")
                appendLine("- Cognitive level: ${q.cognitiveLevel}")
                appendLine("- Strategy: ${q.strategy}")
                appendLine("- Purpose: ${q.purpose}")
                appendLine("- Avoid: ${q.avoid}")
                appendLine()
            }

            appendLine("YÊU CẦU TIẾN TRIỂN NHẬN THỨC:")
            appendLine("- Mỗi câu phải thể hiện cognitive level và mục tiêu riêng ghi phía trên.")
            appendLine("- Phân bố độ khó phải theo ma trận của cấp/lớp được yêu cầu.")
            appendLine("- Không tăng độ khó chỉ bằng số lớn hơn.")
            appendLine("- Không đổi tên nhân vật để giả tạo độ khó.")
            appendLine()
            appendLine("YÊU CẦU DIVERSITY:")
            appendLine("- Mỗi câu có mục tiêu nhận thức riêng.")
            appendLine("- Không lặp context, dữ kiện hoặc answer pattern.")
            appendLine("- Cùng kiến thức thì phải khác cách vận dụng hoặc suy luận.")
        }
    }

// ============================================================
// NORMAL PROMPT
// ============================================================

    private fun buildPrompt(
        grade: Int,
        subject: String,
        topic: String?,
        difficulty: Difficulty,
        previousAssignments: List<String> = emptyList(),
        qualityFeedback: String? = null,
        learningStepTitle: String? = null,
        learningStepSkill: String? = null,
        learningStepDescription: String? = null
    ): String {

        val topicText = topic
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: "Tự chọn nội dung phù hợp chương trình lớp $grade của môn $subject."

        val blueprint = createBlueprint(
            grade = grade,
            subject = subject,
            topic = topicText,
            difficulty = difficulty,
            learningStepTitle = learningStepTitle,
            learningStepSkill = learningStepSkill,
            learningStepDescription = learningStepDescription
        )

        val difficultyText = when (difficulty) {
            Difficulty.EASY ->
                "Cơ bản đến vừa phải. Ưu tiên hiểu, giải thích và vận dụng trực tiếp."

            Difficulty.MEDIUM ->
                "Trung bình. Phải có vận dụng, tình huống, nhiều bước hoặc phân tích khi phù hợp."

            Difficulty.HARD ->
                "Khá khó. Ưu tiên vận dụng cao, phân tích và suy luận nhưng không vượt chương trình."
        }

        val previousText =
            buildPreviousAssignmentsContext(previousAssignments)

        val qualityText = qualityFeedback
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let {
                """
    === FEEDBACK TỪ LẦN TẠO TRƯỚC ===
    $it

    Phải sửa toàn bộ lỗi được nêu.
    Không được tạo lại cùng lỗi dưới cách diễn đạt khác.
    """.trimIndent()
            }
            ?: ""
        val learningStepContext =
            buildLearningStepContext(
                learningStepTitle = learningStepTitle,
                learningStepSkill = learningStepSkill,
                learningStepDescription = learningStepDescription
            )
        val isMathSubject = subject.contains("toán", ignoreCase = true) ||
                subject.contains("math", ignoreCase = true)
        val questionCount = if (isMathSubject) mathQuestionCount(grade) else 3
        val matrixInstructions = if (!isMathSubject) "" else when (grade) {
            in 1..5 -> """
                MA TRẬN TOÁN TIỂU HỌC: 10 câu, tổng 10 điểm. Câu 1-8 thuộc Số học và phép tính (8 điểm), câu 9-10 thuộc Hình học và đo lường (2 điểm). Mức độ: câu 1-5 Mức 1, câu 6-9 Mức 2, câu 10 Mức 3. Cả 10 câu là trắc nghiệm 4 lựa chọn; mỗi câu 1 điểm.
            """.trimIndent()
            in 6..9 -> """
                MA TRẬN TOÁN THCS (16 câu): 9 câu Số và Đại số (5.5 điểm; ID 1-9), 5 câu Hình học và Đo lường (3 điểm; ID 10-14), 2 câu Thống kê và Xác suất (1.5 điểm; ID 15-16). Mức độ: 6 Nhận biết, 6 Thông hiểu, 3 Vận dụng, 1 Vận dụng cao (dùng đúng cognitive level của từng ID trong blueprint). Câu 1-11 trắc nghiệm 4 lựa chọn; câu 12-16 tự luận/trả lời ngắn. Điểm câu 1-7 là 0.5; câu 8-9 là 1.0; câu 10-14 là 0.6; câu 15-16 là 0.75.
            """.trimIndent()
            else -> """
                MA TRẬN TOÁN THPT: 22 câu, tổng 10 điểm. Câu 1-12 trắc nghiệm 4 lựa chọn (0.25 điểm/câu); câu 13-16 Đúng/Sai, mỗi câu có 4 mệnh đề (1 điểm/câu, chấm theo số ý đúng); câu 17-22 trả lời ngắn (0.5 điểm/câu). Chủ đề theo ID: 1-2 Lượng giác; 3 Dãy số/Cấp số; 4-5 Mũ/Logarit; 6-7 Hình học không gian; 8 Xác suất cổ điển; 9 Xác suất; 10-12 Hàm số; 13 Vectơ trong không gian; 14-17 Nguyên hàm/Tích phân; 18 Mẫu số liệu ghép nhóm; 19-22 Hình học Oxyz. Câu Đúng/Sai phải có "statements" gồm 4 mệnh đề và đáp án chuẩn là bốn ký hiệu Đ/S phân cách bằng dấu phẩy.
            """.trimIndent()
        }
        val choiceCount = if (!isMathSubject) 0 else when (grade) {
            in 1..5 -> 10
            in 6..9 -> 11
            else -> 12
        }
        val trueFalseIds = if (isMathSubject && grade >= 10) (13..16).toSet() else emptySet()
        val questionExamples = (1..questionCount).joinToString(",\n") { id ->
            val isChoice = id <= choiceCount
            val isTrueFalse = id in trueFalseIds
            val type = if (!isMathSubject || isChoice || isTrueFalse) "TEXT" else "HANDWRITING"
            val method = if (!isMathSubject) "AI_TEXT" else if (isChoice || isTrueFalse) "EXACT" else "OCR_AI"
            val localMethod = if (!isMathSubject || isChoice || isTrueFalse) "EXACT" else "NUMERIC"
            val mathKind = when {
                isChoice -> "MULTIPLE_CHOICE"
                isTrueFalse -> "TRUE_FALSE_SET"
                else -> "NUMBER"
            }
            val points = if (isMathSubject) mathQuestionPoints(grade, id) else if (id == questionCount) 4.0 else 3.0
            val mathSpec = if (isMathSubject) "\"mathAnswerSpec\":{\"kind\":\"$mathKind\"}" else "\"mathAnswerSpec\":null"
            val optionsExample = if (isChoice) "[\"Lựa chọn A\",\"Lựa chọn B\",\"Lựa chọn C\",\"Lựa chọn D\"]" else "[]"
            val statementsExample = if (isTrueFalse) "[\"Mệnh đề a\",\"Mệnh đề b\",\"Mệnh đề c\",\"Mệnh đề d\"]" else "[]"
            """            {"id":$id,"question":"...","learningObjective":"...","points":$points,"answerType":"$type","gradingMethod":"$method","sourceType":"SELF_CONTAINED","options":$optionsExample,"statements":$statementsExample,"gradingSpec":{"method":"$localMethod","acceptedAnswers":[],"requiredConcepts":[],"$mathSpec}}"""
        }
        val answerExamples = (1..questionCount).joinToString(",\n") { id ->
            "            {\"id\":$id,\"answer\":\"...\"}"
        }

        return """
        Bạn là giáo viên Việt Nam có kinh nghiệm thiết kế bài tập
        theo chương trình phổ thông.

        Hãy tạo MỘT bài tập cho:
        - Lớp: $grade
        - Môn: $subject
        - Chủ đề: $topicText
        - Độ khó: $difficulty
        - Mô tả độ khó: $difficultyText

      ${blueprintAsPrompt(blueprint)}

        $matrixInstructions

           $learningStepContext

=== NGUYÊN TẮC CHƯƠNG TRÌNH ===
        - Chỉ sử dụng kiến thức học sinh lớp $grade có thể đã học.
        - Không tự ý dùng kiến thức lớp cao hơn.
        - Nội dung phải thuộc chủ đề.
        - Dữ kiện phải đủ để giải.
        - Tiếng Việt tự nhiên.
        - Không mơ hồ.
        - AnswerKey phải thực sự trả lời câu hỏi.
        - Learning objective phải rõ ràng.

        === DIVERSITY ===
        Không được:
        - chỉ thay số;
        - chỉ đổi tên;
        - chỉ thay vài từ;
        - giữ nguyên context;
        - dùng cùng cách giải cho nhiều câu;
        - hỏi cùng kiến thức ba lần;
        - làm Q2/Q3 dài hơn nhưng không sâu hơn.

        === CẤU TRÚC NHẬN THỨC ===
        Tuân theo cognitive level và purpose ghi ở từng câu trong blueprint.

        === CHẤT LƯỢNG NGÔN NGỮ ===
        Không được tạo:
        - từ vô nghĩa;
        - câu dịch máy;
        - cụm từ sai ngữ nghĩa;
        - câu thiếu dữ kiện;
        - placeholder;
        - undefined;
        - null;
        - lorem ipsum;
        - chuỗi ký tự bất thường.

        === ANSWER TYPE ===
        Chỉ:
        - TEXT
        - HANDWRITING
        - SPEECH_TO_TEXT

        Nếu môn là Toán, số câu, chủ đề, dạng câu và điểm phải theo MA TRẬN TOÁN.
        Với câu trắc nghiệm, thêm "options" là mảng đúng 4 nội dung lựa chọn,
        cả 4 phải khác nhau, cụ thể, không để trống, không dùng văn bản mẫu/placeholder;
        không tự thêm chữ A./B. vào nội dung; answerKey.answer và
        gradingSpec.correctAnswer dùng duy nhất chữ cái A, B, C hoặc D.
        Dùng answerType=TEXT, gradingMethod=EXACT và
        gradingSpec.mathAnswerSpec.kind=MULTIPLE_CHOICE.
        Với câu Đúng/Sai, thêm "statements" là đúng 4 mệnh đề; đáp án chuẩn
        ghi theo thứ tự bốn ký hiệu Đ/S; dùng kind=TRUE_FALSE_SET.

        Mapping:
        - TEXT -> EXACT hoặc AI_TEXT
        - HANDWRITING -> OCR_AI
        - SPEECH_TO_TEXT -> EXACT hoặc AI_TEXT

        Không dùng DRAWING hoặc MIXED.

        === QUY TẮC VỀ NGUỒN NỘI DUNG ===

        Mỗi câu hỏi phải có sourceType:

        1. SELF_CONTAINED
        - Câu hỏi tự chứa đủ thông tin.
        - Không phụ thuộc tài liệu không được cung cấp.

        2. LESSON_CONTENT
        - Dựa trên nội dung bài học.
        - Nếu cần đọc nội dung bài học để trả lời,
          phải cung cấp trong learningMaterial.

        3. READING_PASSAGE
        - Câu hỏi đọc hiểu.
        - BẮT BUỘC có learningMaterial chứa đầy đủ bài đọc.
         - TUYỆT ĐỐI KHÔNG được tham chiếu tới một tác phẩm/truyện/bài thơ
          cụ thể (ví dụ: "câu chuyện Rùa và Thỏ", "truyện Thánh Gióng",
          "bài thơ Lượm") nếu tác phẩm đó KHÔNG được cung cấp đầy đủ
          trong learningMaterial.

        - Nếu muốn hỏi dựa trên một câu chuyện/bài thơ cụ thể, BẮT BUỘC:
          + cung cấp TOÀN BỘ nội dung (hoặc đoạn trích đầy đủ) trong
            learningMaterial, VÀ
          + đặt sourceType = READING_PASSAGE (hoặc LESSON_CONTENT).

        - Nếu không muốn cung cấp nội dung, phải tự tóm tắt/viết lại
          câu chuyện ngay trong câu hỏi (sourceType = SELF_CONTAINED),
          không được viện dẫn tên tác phẩm bên ngoài.

        QUY TẮC BẮT BUỘC:

        - Nếu có LESSON_CONTENT hoặc READING_PASSAGE,
          learningMaterial không được null/rỗng.
        - learningMaterial phải chứa đủ nội dung cần thiết.
        - Không viết "Đọc đoạn văn trên", "Dựa vào bài học trên",
          "Theo bảng trên", "Nhìn vào hình trên" nếu nội dung không tồn tại.
        - Không yêu cầu học sinh tự tìm Internet/sách/nguồn ngoài.
        - Nếu nhiều câu dùng chung bài đọc, đặt toàn bộ bài đọc
          vào learningMaterial.
        - learningMaterial phải thực sự có giá trị giáo dục.
        - Không tạo material chỉ để đối phó validator.
        - Ưu tiên SELF_CONTAINED nếu câu hỏi đã đủ dữ kiện.

        === ĐIỂM ===
        Chính xác $questionCount câu, tổng 10 điểm.

        === TỰ KIỂM TRA ===
        - Đúng $questionCount câu.
        - ID liên tục từ 1 đến $questionCount.
        - Có learningObjective.
        - Các câu khác nhau về kỹ năng, dữ kiện hoặc cách vận dụng theo blueprint.
        - Có đúng $questionCount answerKey.
        - ID answerKey khớp.
        - Tổng điểm = 10.
        - GradingGuide khớp.
        - Không DRAWING/MIXED.
        - AnswerType và GradingMethod tương thích.
        - Không lỗi chính tả/ngữ nghĩa.
        - Không kiến thức vượt lớp.
        - JSON hợp lệ.
        - Không markdown ngoài JSON.
        - Viết công thức toán trực tiếp, không bọc công thức bằng dấu phân cách Markdown/LaTeX.

        === GRADINGSPEC CHO RULE ENGINE LOCAL ===
        Mỗi câu phải có gradingSpec với method là EXACT, NUMERIC, REQUIRED_CONCEPTS hoặc STEP_RUBRIC.
        - EXACT: dùng cho đáp án ngắn; đưa các biến thể đúng vào acceptedAnswers.
        - NUMERIC: chỉ dùng cho phép chấm số đơn giản; với Toán, gradingSpec.mathAnswerSpec mới quyết định định dạng đáp án.
        - REQUIRED_CONCEPTS: dùng cho câu trả lời ngắn có 2-5 ý bắt buộc kiểm tra được.
        - STEP_RUBRIC: chỉ dùng cho bài Toán nhiều bước; rubric.version hiện là 1, criteria phải có id, description, points, method và bằng chứng chấm được. Các method tiêu chí: EVIDENCE (acceptedEvidence), REQUIRED_CONCEPTS (requiredConcepts), FINAL_NUMERIC (expectedNumber, numericTolerance). Tổng points của criteria phải bằng điểm câu.
          Cấu trúc: "rubric":{"version":1,"criteria":[{"id":"method","description":"...","points":1,"method":"REQUIRED_CONCEPTS","requiredConcepts":["..."]},{"id":"work","description":"...","points":1,"method":"EVIDENCE","acceptedEvidence":["..."]},{"id":"final","description":"...","points":1,"method":"FINAL_NUMERIC","expectedNumber":"...","numericTolerance":0}]}. Chỉ sinh tiêu chí có thể nhận diện trong câu trả lời.
        Nếu môn học là Toán, mọi câu phải có gradingSpec.mathAnswerSpec với kind cụ thể: NUMBER, CALCULATION, FILL_BLANK, MULTIPLE_CHOICE, TRUE_FALSE_SET, ORDERED_TUPLE, UNORDERED_SET, QUANTITY hoặc SYMBOLIC_EXPRESSION; không dùng AUTO. NUMBER: answerKey.answer chỉ là số/phân số, không kèm đơn vị hay lời giải. CALCULATION: dùng khi đáp án chuẩn là biểu thức/phương trình cần tính. FILL_BLANK: chỉ chọn khi question có từ 1 đến 8 placeholder hiển thị nguyên văn bằng ___, □ hoặc [ ]; luôn ưu tiên □ để tránh nhầm dấu câu. Nếu không có placeholder thì không được khai báo FILL_BLANK. Với nhiều chỗ trống, answerKey.answer chỉ ghi các giá trị theo thứ tự, phân cách bằng dấu chấm phẩy. QUANTITY phải có expectedUnit. Với biểu thức ký hiệu chỉ chấp nhận acceptedAnswers được liệt kê tường minh; không giả định tương đương đại số.
        Không dùng gradingSpec cho bài luận mở hoặc ý kiến chủ quan. acceptedAnswers chỉ chứa biến thể đúng.

        $previousText

        $qualityText

        === JSON OUTPUT ===
        Chỉ trả về JSON hợp lệ:

        {
          "title": "Tên bài",
          "learningMaterial": null,
          "questions": [
$questionExamples
          ],
          "answerKey": [
$answerExamples
          ],
          "gradingGuide": "...",
          "totalScore": 10
        }
    """.trimIndent()
    }

// ============================================================
// SEX EDUCATION PROMPT
// ============================================================

    private fun buildSexEducationPrompt(
        grade: Int,
        subject: String,
        topic: String?,
        difficulty: Difficulty,
        previousAssignments: List<String> = emptyList(),
        qualityFeedback: String? = null,
        learningStepTitle: String? = null,
        learningStepSkill: String? = null,
        learningStepDescription: String? = null
    ): String {

        val topicText = topic
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: "nội dung phù hợp chương trình sức khỏe giới tính lớp $grade"

        val blueprint = createSexEducationBlueprint(
            grade = grade,
            topic = topicText,
            difficulty = difficulty
        )

        val previousText =
            buildPreviousAssignmentsContext(previousAssignments)

        val qualityText = qualityFeedback
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let {
                """
            === QUALITY FEEDBACK ===
            $it

            Phải sửa toàn bộ vấn đề được nêu.
            """.trimIndent()
            }
            ?: ""
        val learningStepContext =
            buildLearningStepContext(
                learningStepTitle = learningStepTitle,
                learningStepSkill = learningStepSkill,
                learningStepDescription = learningStepDescription
            )
        return """
        Bạn là giáo viên Việt Nam thiết kế bài học giáo dục sức khỏe
        giới tính/sức khỏe sinh sản phù hợp tuổi.

        === THÔNG TIN ===
        - Lớp: $grade
        - Môn: $subject
        - Chủ đề: $topicText

        === PHẠM VI ĐƯỢC PHÉP ===
        ${getSexEducationScope(grade)}

        === ĐỘ KHÓ ===
        ${getSexEducationDifficulty(difficulty)}

        ${blueprintAsPrompt(blueprint)}
$learningStepContext
        === MỤC TIÊU ===
        Bài tập phải giúp học sinh:
        - hiểu kiến thức khoa học;
        - biết bảo vệ cơ thể;
        - biết ranh giới cá nhân;
        - biết quyền từ chối;
        - biết nhận diện nguy cơ;
        - biết cách tìm người lớn đáng tin cậy khi cần;
        - biết bảo vệ quyền riêng tư trên Internet khi phù hợp.

        === AN TOÀN ===
        Tuyệt đối không:
        - nội dung khiêu dâm;
        - sexual roleplay;
        - mô tả hành vi tình dục không cần thiết;
        - hướng dẫn hoạt động tình dục;
        - yêu cầu học sinh chia sẻ trải nghiệm cá nhân;
        - hỏi về đời sống tình dục cá nhân;
        - yêu cầu ảnh/video cơ thể;
        - sexualize trẻ em;
        - nội dung grooming;
        - làm học sinh xấu hổ hoặc đổ lỗi.

        Với học sinh nhỏ tuổi, tập trung:
        - cơ thể;
        - riêng tư;
        - ranh giới;
        - quyền nói không;
        - an toàn;
        - báo người lớn.

        === DIVERSITY ===
        Q1: hiểu kiến thức nền tảng.
        Q2: vận dụng vào tình huống giáo dục.
        Q3: phân tích/ra quyết định an toàn hoặc giải thích lý do.

        Không được tạo ba câu chỉ khác từ ngữ.
        Không được đổi tên nhân vật để giả tạo sự đa dạng.
        Không được chỉ thay số.
        Không được lặp cùng tình huống.
        Không được yêu cầu học sinh tiết lộ trải nghiệm riêng tư.

        === ANSWER TYPE ===
        Ưu tiên:
        - TEXT + AI_TEXT
        - SPEECH_TO_TEXT + AI_TEXT

        HANDWRITING + OCR_AI chỉ dùng khi thực sự có giá trị.

        Không dùng:
        - DRAWING
        - MIXED

        Không dùng EXACT cho câu hỏi mở.

        === CHẤT LƯỢNG ===
        Câu hỏi phải:
        - có nghĩa;
        - tự nhiên bằng tiếng Việt;
        - không mơ hồ;
        - đủ dữ kiện;
        - có câu trả lời xác định hoặc tiêu chí chấm;
        - phù hợp tuổi;
        - không chứa từ vô nghĩa;
        - không có placeholder.

        === QUY TẮC VỀ NGUỒN NỘI DUNG ===

        Mỗi câu hỏi phải có sourceType:

        1. SELF_CONTAINED
        - Câu hỏi tự chứa đầy đủ thông tin cần thiết.

        2. LESSON_CONTENT
        - Dựa trên nội dung bài học.
        - Nếu cần đọc nội dung bài học để trả lời,
          phải cung cấp trong learningMaterial.

        3. READING_PASSAGE
        - Câu hỏi đọc hiểu.
        - BẮT BUỘC có learningMaterial chứa đầy đủ bài đọc.

        QUY TẮC BẮT BUỘC:
        - Nếu bất kỳ câu nào có sourceType = LESSON_CONTENT
          hoặc READING_PASSAGE thì learningMaterial không được null/rỗng.
        - Không viết "Đọc đoạn văn trên...", "Dựa vào bài học trên...",
          "Theo nội dung trên...", "Theo bảng trên..."
          nếu nội dung không nằm trong learningMaterial.
        - Không yêu cầu học sinh tự tìm Internet, sách giáo khoa
          hoặc nguồn ngoài.
        - Nếu nhiều câu cùng sử dụng một bài đọc,
          đặt toàn bộ bài đọc trong learningMaterial.
        - learningMaterial phải thực sự liên quan.
        - Không tạo learningMaterial chỉ để đối phó validator.
        - Ưu tiên SELF_CONTAINED nếu câu hỏi đã đủ dữ kiện.

        === GRADINGSPEC CHO RULE ENGINE LOCAL ===
        Mỗi câu phải có gradingSpec với method là EXACT, NUMERIC, REQUIRED_CONCEPTS hoặc STEP_RUBRIC.
        - EXACT: dùng cho đáp án ngắn; đưa các biến thể đúng vào acceptedAnswers.
        - NUMERIC: chỉ dùng cho phép chấm số đơn giản; với Toán, gradingSpec.mathAnswerSpec mới quyết định định dạng đáp án.
        - REQUIRED_CONCEPTS: dùng cho câu trả lời ngắn có 2-5 ý bắt buộc kiểm tra được.
        - STEP_RUBRIC: chỉ dùng cho bài Toán nhiều bước; rubric.version hiện là 1, criteria phải có id, description, points, method và bằng chứng chấm được. Các method tiêu chí: EVIDENCE (acceptedEvidence), REQUIRED_CONCEPTS (requiredConcepts), FINAL_NUMERIC (expectedNumber, numericTolerance). Tổng points của criteria phải bằng điểm câu.
          Cấu trúc: "rubric":{"version":1,"criteria":[{"id":"method","description":"...","points":1,"method":"REQUIRED_CONCEPTS","requiredConcepts":["..."]},{"id":"work","description":"...","points":1,"method":"EVIDENCE","acceptedEvidence":["..."]},{"id":"final","description":"...","points":1,"method":"FINAL_NUMERIC","expectedNumber":"...","numericTolerance":0}]}. Chỉ sinh tiêu chí có thể nhận diện trong câu trả lời.
        Nếu môn học là Toán, mọi câu phải có gradingSpec.mathAnswerSpec với kind cụ thể: NUMBER, CALCULATION, FILL_BLANK, MULTIPLE_CHOICE, TRUE_FALSE_SET, ORDERED_TUPLE, UNORDERED_SET, QUANTITY hoặc SYMBOLIC_EXPRESSION; không dùng AUTO. NUMBER: answerKey.answer chỉ là số/phân số, không kèm đơn vị hay lời giải. CALCULATION: dùng khi đáp án chuẩn là biểu thức/phương trình cần tính. FILL_BLANK: chỉ chọn khi question có từ 1 đến 8 placeholder hiển thị nguyên văn bằng ___, □ hoặc [ ]; luôn ưu tiên □ để tránh nhầm dấu câu. Nếu không có placeholder thì không được khai báo FILL_BLANK. Với nhiều chỗ trống, answerKey.answer chỉ ghi các giá trị theo thứ tự, phân cách bằng dấu chấm phẩy. QUANTITY phải có expectedUnit. Với biểu thức ký hiệu chỉ chấp nhận acceptedAnswers được liệt kê tường minh; không giả định tương đương đại số.
        Không dùng gradingSpec cho bài luận mở hoặc ý kiến chủ quan. acceptedAnswers chỉ chứa biến thể đúng.

        $previousText

        $qualityText

        === JSON OUTPUT ===
        Chỉ trả về JSON hợp lệ:

        {
          "title": "Tên bài",
          "learningMaterial": null,
          "questions": [
            {
              "id": 1,
              "question": "...",
              "learningObjective": "...",
              "points": 3,
              "answerType": "TEXT",
              "gradingMethod": "AI_TEXT",
              "sourceType": "SELF_CONTAINED",
              "gradingSpec": {
                "method": "REQUIRED_CONCEPTS",
                "acceptedAnswers": [],
                "requiredConcepts": ["...", "..."],
                "caseSensitive": false,
                "ignoreWhitespace": false,
                "ignorePunctuation": true,
                "numericTolerance": 0,
                "allowPartialCredit": true
              }
            },
            {
              "id": 2,
              "question": "...",
              "learningObjective": "...",
              "points": 3,
              "answerType": "TEXT",
              "gradingMethod": "AI_TEXT",
              "sourceType": "SELF_CONTAINED",
              "gradingSpec": {
                "method": "EXACT",
                "acceptedAnswers": ["..."],
                "requiredConcepts": [],
                "caseSensitive": false,
                "ignoreWhitespace": true,
                "ignorePunctuation": false,
                "numericTolerance": 0,
                "allowPartialCredit": false
              }
            },
            {
              "id": 3,
              "question": "...",
              "learningObjective": "...",
              "points": 4,
              "answerType": "TEXT",
              "gradingMethod": "AI_TEXT",
              "sourceType": "SELF_CONTAINED",
              "gradingSpec": {
                "method": "REQUIRED_CONCEPTS",
                "acceptedAnswers": [],
                "requiredConcepts": ["...", "..."],
                "caseSensitive": false,
                "ignoreWhitespace": false,
                "ignorePunctuation": true,
                "numericTolerance": 0,
                "allowPartialCredit": true
              }
            }
          ],
          "answerKey": [
            {
              "id": 1,
              "answer": "..."
            },
            {
              "id": 2,
              "answer": "..."
            },
            {
              "id": 3,
              "answer": "..."
            }
          ],
          "gradingGuide": "...",
          "totalScore": 10
        }

        Bắt buộc:
        - đúng 3 câu;
        - ID 1,2,3;
        - có learningObjective;
        - đúng 3 answerKey;
        - tổng điểm 10;
        - totalScore = 10;
        - answerType/gradingMethod tương thích;
        - không DRAWING/MIXED;
        - không có nội dung không phù hợp tuổi.
    """.trimIndent()
    }

    private fun getSexEducationScope(grade: Int): String {

        return when (grade) {

            in 1..3 -> """
            - Cơ thể và các bộ phận cơ thể.
            - Riêng tư.
            - Ranh giới cá nhân.
            - Quyền nói không.
            - An toàn và báo người lớn đáng tin cậy.
            - Không đi sâu vào hoạt động tình dục.
        """.trimIndent()

            in 4..5 -> """
            - Thay đổi cơ thể ở tuổi dậy thì.
            - Vệ sinh cơ thể.
            - Kinh nguyệt ở mức cơ bản.
            - Cảm xúc và ranh giới.
            - An toàn trên Internet.
            - Nhận diện hành vi không phù hợp.
        """.trimIndent()

            in 6..7 -> """
            - Dậy thì.
            - Thay đổi thể chất và tâm lý.
            - Kinh nguyệt.
            - Đồng thuận và ranh giới.
            - Quan hệ lành mạnh.
            - An toàn hình ảnh và Internet.
            - Nhận diện hành vi không phù hợp.
            - Tìm người hỗ trợ.
        """.trimIndent()

            in 8..9 -> """
            - Sức khỏe sinh sản.
            - Hormone và thay đổi cơ thể.
            - Sức khỏe nam/nữ.
            - Kinh nguyệt.
            - Đồng thuận.
            - Quan hệ lành mạnh.
            - Phòng ngừa STI ở mức khoa học.
            - Phòng tránh mang thai ở mức giáo dục.
            - An toàn Internet.
            - Tìm hỗ trợ.
        """.trimIndent()

            else -> """
            - Sức khỏe sinh sản.
            - Sinh sản ở mức khoa học.
            - Sức khỏe tình dục có trách nhiệm.
            - Đồng thuận và ranh giới.
            - Quan hệ lành mạnh.
            - Kiến thức khoa học về tránh thai.
            - Phòng ngừa STI.
            - Sức khỏe thể chất và tinh thần.
            - Quyền riêng tư và Internet.
            - Phòng chống xâm hại.
            - Tìm hỗ trợ y tế/chuyên môn.
        """.trimIndent()
        }
    }

    private fun getSexEducationDifficulty(
        difficulty: Difficulty
    ): String {

        return when (difficulty) {

            Difficulty.EASY ->
                "Kiến thức cơ bản, nhận biết và hiểu. Không yêu cầu reasoning phức tạp."

            Difficulty.MEDIUM ->
                "Hiểu và vận dụng trong tình huống giáo dục. Có thể giải thích lý do hoặc chọn hành động an toàn."

            Difficulty.HARD ->
                "Vận dụng và phân tích tình huống, nhận diện nguy cơ, quyết định an toàn và giải thích lý do; luôn phù hợp độ tuổi."
        }
    }

// ============================================================
// PREVIOUS ASSIGNMENTS
// ============================================================

    private fun buildPreviousAssignmentsContext(
        previousAssignments: List<String>
    ): String {

        if (previousAssignments.isEmpty()) {
            return """
            === BÀI ĐÃ TẠO TRƯỚC ===
            Không có dữ liệu bài trước.
        """.trimIndent()
        }

        val limited = previousAssignments
            .filter { it.isNotBlank() }
            .takeLast(12)

        return buildString {

            appendLine("=== BÀI ĐÃ TẠO TRƯỚC ===")
            appendLine(
                "Có ${limited.size} bài trước. " +
                        "Phải tránh trùng ý tưởng, context, strategy và cách giải."
            )

            limited.forEachIndexed { index, assignment ->
                appendLine()
                appendLine("--- PREVIOUS ASSIGNMENT ${index + 1} ---")
                appendLine(assignment.take(7000))
            }

            appendLine()
            appendLine("=== SEMANTIC ANTI-REPETITION ===")
            appendLine(
                """
            Trước khi tạo từng câu, hãy tự hỏi:
            1. Có đang kiểm tra cùng ý tưởng với bài trước không?
            2. Nếu đổi tên và số liệu, có thành cùng một câu không?
            3. Cách giải có giống bài trước không?
            4. Context có bị lặp không?
            5. Có thể kiểm tra cùng kiến thức bằng tình huống khác không?

            Nếu quá giống:
            - bỏ câu;
            - chọn context mới;
            - thay reasoning;
            - thay strategy;
            - hoặc thay kỹ năng.

            Không coi "đổi số" là diversity thực sự.
            """.trimIndent()
            )
        }
    }

// ============================================================
// RETRY
// ============================================================

    private suspend fun callGeminiWithRetry(
        prompt: String,
        sexEducation: Boolean,
        temperature: Double = 0.35
    ): String {

        val key = if (sexEducation) {
            sexEducationApiKey ?: apiKey
        } else {
            apiKey
        }

        require(!key.isNullOrBlank()) {
            "Gemini API key is not configured"
        }

        val primaryModel = if (sexEducation) {
            sexEducationModel
        } else {
            model
        }

        val fallbackModel = if (sexEducation) {
            System.getenv("GEMINI_SEX_EDUCATION_FALLBACK_MODEL")
                ?: System.getenv("GEMINI_FALLBACK_MODEL")
                ?: "gemini-3.5-flash"
        } else {
            System.getenv("GEMINI_FALLBACK_MODEL")
                ?: "gemini-3.5-flash"
        }

        val models = listOf(primaryModel, fallbackModel)
            .filter { it.isNotBlank() }
            .distinct()

        var lastError: Throwable? = null

        for ((modelIndex, selectedModel) in models.withIndex()) {

            println(
                "[AIService] Gemini model=$selectedModel " +
                        "modelIndex=$modelIndex"
            )

            for (attempt in 1..3) {

                try {

                    println(
                        "[AIService] Gemini attempt=$attempt/3 " +
                                "model=$selectedModel"
                    )

                    return callGemini(
                        prompt = prompt,
                        key = key!!,
                        selectedModel = selectedModel,
                        sexEducation = sexEducation,
                        temperature = temperature
                    )

                } catch (e: GeminiRetryException) {

                    lastError = e

                    if (attempt < 3) {
                        delay(
                            when (attempt) {
                                1 -> 5_000L
                                2 -> 15_000L
                                else -> 30_000L
                            }
                        )
                    }

                } catch (e: SocketTimeoutException) {

                    lastError = e

                    if (attempt < 3) {
                        delay(
                            when (attempt) {
                                1 -> 5_000L
                                2 -> 15_000L
                                else -> 30_000L
                            }
                        )
                    }

                } catch (e: ConnectException) {

                    lastError = e

                    if (attempt < 3) {
                        delay(
                            when (attempt) {
                                1 -> 5_000L
                                2 -> 15_000L
                                else -> 30_000L
                            }
                        )
                    }

                } catch (e: Exception) {
                    throw e
                }
            }

            println(
                "[AIService] Model exhausted, switching if fallback exists."
            )
        }

        throw lastError ?: IllegalStateException(
            "Gemini request failed without a specific error"
        )
    }

// ============================================================
// GEMINI HTTP
// ============================================================

    private fun callGemini(
        prompt: String,
        key: String,
        selectedModel: String,
        sexEducation: Boolean,
        temperature: Double
    ): String {

        val url = URL(
            "https://generativelanguage.googleapis.com/v1beta/models/" +
                    "$selectedModel:generateContent?key=$key"
        )

        val connection =
            url.openConnection() as HttpURLConnection

        try {

            connection.requestMethod = "POST"
            connection.connectTimeout = 20_000
            // Large assignment prompts can take longer to generate.
            connection.readTimeout = 240_000
            connection.doOutput = true

            connection.setRequestProperty(
                "Content-Type",
                "application/json"
            )

            connection.setRequestProperty(
                "Accept",
                "application/json"
            )

            val generationConfig = buildJsonObject {
                put("temperature", temperature)
                put("responseMimeType", "application/json")
            }

            val requestBody = buildJsonObject {

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

                put(
                    "contents",
                    buildJsonArray {
                        add(
                            buildJsonObject {
                                put("role", "user")
                                put(
                                    "parts",
                                    buildJsonArray {
                                        add(
                                            buildJsonObject {
                                                put("text", prompt)
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
                    generationConfig
                )

                if (sexEducation) {
                    put(
                        "safetySettings",
                        buildSexEducationSafetySettings()
                    )
                }
            }

            connection.outputStream.use { output ->
                output.write(
                    requestBody.toString()
                        .toByteArray(Charsets.UTF_8)
                )
            }

            val responseCode = connection.responseCode

            val responseText =
                if (responseCode in 200..299) {
                    connection.inputStream
                        .bufferedReader()
                        .use { it.readText() }
                } else {
                    connection.errorStream
                        ?.bufferedReader()
                        ?.use { it.readText() }
                        ?: ""
                }

            if (responseCode in 200..299) {
                return responseText
            }

            if (
                responseCode == 429 ||
                responseCode == 500 ||
                responseCode == 502 ||
                responseCode == 503 ||
                responseCode == 504
            ) {
                throw GeminiRetryException(
                    "Gemini HTTP $responseCode: " +
                            responseText.take(1000)
                )
            }

            throw IllegalStateException(
                "Gemini HTTP $responseCode: " +
                        responseText.take(2000)
            )

        } finally {
            connection.disconnect()
        }
    }

    private class GeminiRetryException(
        message: String
    ) : Exception(message)

// ============================================================
// SEX EDUCATION SYSTEM
// ============================================================

    private fun buildSexEducationSystemInstruction(): String {
        return """
        Bạn là hệ thống AI hỗ trợ giáo dục sức khỏe giới tính
        và sức khỏe sinh sản cho học sinh.

        Nội dung phải:
        - khoa học;
        - chính xác;
        - giáo dục;
        - phù hợp độ tuổi;
        - không kích thích tình dục;
        - không khiêu dâm;
        - không mô tả tình dục không cần thiết;
        - không sexual roleplay;
        - không yêu cầu trẻ em mô tả trải nghiệm riêng tư;
        - không yêu cầu ảnh/video riêng tư;
        - không hướng dẫn hành vi tình dục;
        - không bình thường hóa grooming hoặc xâm hại;
        - không làm học sinh xấu hổ;
        - ưu tiên an toàn, ranh giới cá nhân, quyền từ chối
          và tìm người lớn đáng tin cậy.

        Không suy đoán trải nghiệm cá nhân của học sinh.
    """.trimIndent()
    }

    private fun buildSexEducationSafetySettings(): JsonArray {
        return buildJsonArray {

            add(
                buildJsonObject {
                    put(
                        "category",
                        "HARM_CATEGORY_SEXUALLY_EXPLICIT"
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
                        "BLOCK_LOW_AND_ABOVE"
                    )
                }
            )
        }
    }

// ============================================================
// PARSE GEMINI RESPONSE
// ============================================================

    private fun stripMathDelimiters(text: String): String {
        val output = StringBuilder(text.length)
        var index = 0
        while (index < text.length) {
            if (text[index].code != 36 || (index > 0 && text[index - 1] == '\\')) {
                output.append(text[index++])
                continue
            }

            val delimiterLength = if (index + 1 < text.length && text[index + 1].code == 36) 2 else 1
            var closingIndex = index + delimiterLength
            while (closingIndex < text.length) {
                if (text[closingIndex].code == 36 &&
                    (closingIndex == 0 || text[closingIndex - 1] != '\\')
                ) {
                    val closingLength =
                        if (closingIndex + 1 < text.length && text[closingIndex + 1].code == 36) 2 else 1
                    if (closingLength == delimiterLength) break
                }
                closingIndex++
            }

            if (closingIndex < text.length) {
                output.append(text, index + delimiterLength, closingIndex)
                index = closingIndex + delimiterLength
            } else {
                output.append(text[index++])
            }
        }
        return output.toString()
    }

    private fun parseResponse(
        responseText: String
    ): GeneratedAssignment {

        val root = parseGeminiJsonResponse(
            responseText = responseText
        )

        val obj = root.jsonObject

        val title = obj["title"]
            ?.jsonPrimitive
            ?.contentOrNull
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: "Bài tập"

        val learningMaterial = obj["learningMaterial"]
            ?.jsonPrimitive
            ?.contentOrNull
            ?.trim()
            ?.takeIf { it.isNotBlank() }

        val questionsElement =
            obj["questions"]
                ?: throw IllegalStateException(
                    "Gemini response missing questions"
                )

        val questions = questionsElement
            .jsonArray
            .map { element ->

                val q = element.jsonObject

                val id = q["id"]
                    ?.jsonPrimitive
                    ?.intOrNull
                    ?: throw IllegalStateException(
                        "Question missing id"
                    )

                val question = q["question"]
                    ?.jsonPrimitive
                    ?.contentOrNull
                    ?.trim()
                    ?.let(::stripMathDelimiters)
                    ?: throw IllegalStateException(
                        "Question $id missing question"
                    )

                val learningObjective =
                    q["learningObjective"]
                        ?.jsonPrimitive
                        ?.contentOrNull
                        ?.trim()
                        ?: throw IllegalStateException(
                            "Question $id missing learningObjective"
                        )

                val points = q["points"]
                    ?.jsonPrimitive
                    ?.doubleOrNull
                    ?: throw IllegalStateException(
                        "Question $id missing points"
                    )

                val answerTypeText =
                    q["answerType"]
                        ?.jsonPrimitive
                        ?.contentOrNull
                        ?: throw IllegalStateException(
                            "Question $id missing answerType"
                        )

                val gradingMethodText =
                    q["gradingMethod"]
                        ?.jsonPrimitive
                        ?.contentOrNull
                        ?: throw IllegalStateException(
                            "Question $id missing gradingMethod"
                        )

                val sourceTypeText =
                    q["sourceType"]
                        ?.jsonPrimitive
                        ?.contentOrNull
                        ?: "SELF_CONTAINED"

                val answerType = try {
                    AnswerType.valueOf(
                        answerTypeText.trim().uppercase()
                    )
                } catch (e: Exception) {
                    throw IllegalStateException(
                        "Question $id has invalid answerType: " +
                                answerTypeText
                    )
                }

                val gradingMethod = try {
                    GradingMethod.valueOf(
                        gradingMethodText.trim().uppercase()
                    )
                } catch (e: Exception) {
                    throw IllegalStateException(
                        "Question $id has invalid gradingMethod: " +
                                gradingMethodText
                    )
                }

                val sourceType = try {
                    QuestionSourceType.valueOf(
                        sourceTypeText.trim().uppercase()
                    )
                } catch (e: Exception) {
                    throw IllegalStateException(
                        "Question $id has invalid sourceType: " +
                                sourceTypeText
                    )
                }

                val gradingSpecElement =
                    q["gradingSpec"]
                        ?: throw IllegalStateException(
                            "Question $id missing gradingSpec"
                        )

                val gradingSpec = try {
                    json.decodeFromJsonElement<GradingSpec>(
                        gradingSpecElement
                    )
                } catch (e: Exception) {
                    throw IllegalStateException(
                        "Question $id has invalid gradingSpec: ${e.message}"
                    )
                }

                validateGradingCompatibility(
                    answerType = answerType,
                    gradingMethod = gradingMethod
                )

                GeneratedQuestion(
                    id = id,
                    question = question,
                    learningObjective = learningObjective,
                    points = points,
                    answerType = answerType,
                    gradingMethod = gradingMethod,
                    sourceType = sourceType,
                    gradingSpec = gradingSpec,
                    options = q["options"]?.jsonArray?.map { it.jsonPrimitive.content.trim() }
                        ?: emptyList(),
                    statements = q["statements"]?.jsonArray?.map { it.jsonPrimitive.content.trim() }
                        ?: emptyList()
                )
            }

        val answerKeyElement =
            obj["answerKey"]
                ?: throw IllegalStateException(
                    "Gemini response missing answerKey"
                )

        val answerKey = answerKeyElement
            .jsonArray
            .map { element ->

                val answerObject = element.jsonObject

                val id = answerObject["id"]
                    ?.jsonPrimitive
                    ?.intOrNull
                    ?: throw IllegalStateException(
                        "Answer missing id"
                    )

                val answer = answerObject["answer"]
                    ?.jsonPrimitive
                    ?.contentOrNull
                    ?.trim()
                    ?: throw IllegalStateException(
                        "Answer $id missing answer"
                    )

                GeneratedAnswer(
                    id = id,
                    answer = answer
                )
            }

        val gradingGuide = obj["gradingGuide"]
            ?.jsonPrimitive
            ?.contentOrNull
            ?.trim()
            ?: throw IllegalStateException(
                "Gemini response missing gradingGuide"
            )

        val totalScore = obj["totalScore"]
            ?.jsonPrimitive
            ?.doubleOrNull
            ?: throw IllegalStateException(
                "Gemini response missing totalScore"
            )

        val answersById = answerKey.associateBy { it.id }
        val questionsWithAnswers = questions.map { question ->
            val correctAnswer = answersById[question.id]?.answer
                ?: throw IllegalStateException(
                    "Missing answer key for question ${question.id}"
                )
            val gradingSpec = question.gradingSpec.copy(correctAnswer = correctAnswer)
            val mathSpec = gradingSpec.mathAnswerSpec
            val blankCount = Regex("_{2,}|\\.{2,}|…+|□|▢|\\[\\s*\\]")
                .findAll(question.question)
                .count()

            // If the model labels a scalar-answer question as FILL_BLANK but
            // omitted a visible blank token, NUMBER is the safe local format.
            val normalizedMathSpec = if (
                mathSpec != null &&
                mathSpec.kind == MathAnswerKind.FILL_BLANK &&
                blankCount == 0 &&
                (listOf(correctAnswer) + gradingSpec.acceptedAnswers).all(::isNumericAnswer)
            ) {
                println(
                    "[AIService] Question ${question.id}: normalized FILL_BLANK to NUMBER because no visible blank token was generated"
                )
                mathSpec.copy(kind = MathAnswerKind.NUMBER)
            } else {
                mathSpec
            }

            question.copy(
                gradingSpec = gradingSpec.copy(mathAnswerSpec = normalizedMathSpec)
            )
        }

        return GeneratedAssignment(
            title = title,
            learningMaterial = learningMaterial,
            questions = questionsWithAnswers,
            answerKey = answerKey,
            gradingGuide = gradingGuide,
            totalScore = totalScore
        )
    }

    private fun parseGeminiJsonResponse(
        responseText: String
    ): JsonElement {

        val root = json.parseToJsonElement(responseText)

        val candidateText =
            root.jsonObject["candidates"]
                ?.jsonArray
                ?.firstOrNull()
                ?.jsonObject
                ?.get("content")
                ?.jsonObject
                ?.get("parts")
                ?.jsonArray
                ?.firstOrNull()
                ?.jsonObject
                ?.get("text")
                ?.jsonPrimitive
                ?.contentOrNull

        val raw = candidateText ?: responseText
        val cleaned = cleanJsonText(raw)
        val validEscapes = escapeInvalidJsonBackslashes(cleaned)
        if (validEscapes != cleaned) {
            println("[AIService] Repaired invalid backslash escapes in Gemini JSON text")
        }
        return json.parseToJsonElement(validEscapes)
    }

    /** Gemini occasionally emits LaTeX-like text such as `\log` inside a JSON
     * string without escaping the backslash. Escape only sequences that are
     * invalid in JSON; preserve valid escapes such as `\n` and `\u00e9`. */
    private fun escapeInvalidJsonBackslashes(text: String): String {
        val output = StringBuilder(text.length)
        var insideString = false
        var index = 0

        while (index < text.length) {
            val char = text[index]
            if (!insideString) {
                output.append(char)
                if (char == '"') insideString = true
                index++
                continue
            }

            when (char) {
                '"' -> {
                    output.append(char)
                    insideString = false
                    index++
                }
                '\\' -> {
                    if (index + 1 >= text.length) {
                        output.append("\\\\")
                        index++
                        continue
                    }

                    val next = text[index + 1]
                    val validSimpleEscape = next in "\"\\/bfnrt"
                    val validUnicodeEscape = next == 'u' && index + 5 < text.length &&
                            text.substring(index + 2, index + 6).all { hex ->
                                hex in '0'..'9' || hex in 'a'..'f' || hex in 'A'..'F'
                            }
                    if (validSimpleEscape || validUnicodeEscape) {
                        output.append(char).append(next)
                        index += 2
                    } else {
                        output.append("\\\\")
                        index++
                    }
                }
                else -> {
                    output.append(char)
                    index++
                }
            }
        }
        return output.toString()
    }

    private fun cleanJsonText(
        text: String
    ): String {

        var result = text.trim()

        if (result.startsWith("```")) {
            result = result
                .removePrefix("```json")
                .removePrefix("```JSON")
                .removePrefix("```")
                .trim()

            if (result.endsWith("```")) {
                result = result
                    .removeSuffix("```")
                    .trim()
            }
        }

        val firstBrace = result.indexOf('{')
        val lastBrace = result.lastIndexOf('}')

        if (firstBrace >= 0 && lastBrace > firstBrace) {
            result = result.substring(
                firstBrace,
                lastBrace + 1
            )
        }

        return result
    }

// ============================================================
// STRUCTURAL VALIDATION
// ============================================================

    private fun validateParsedAssignment(
        assignment: GeneratedAssignment,
        grade: Int,
        subject: String,
        difficulty: Difficulty
    ) {

        val errors = mutableListOf<String>()
        val isMathSubject = subject.contains("toán", ignoreCase = true) ||
                subject.contains("math", ignoreCase = true)

        if (assignment.title.isBlank()) {
            errors += "Assignment title is empty"
        }

        if (assignment.title.trim().length < 5) {
            errors += "Assignment title is too short"
        }

        val expectedQuestionCount = if (isMathSubject) mathQuestionCount(grade) else 3
        if (assignment.questions.size != expectedQuestionCount) {
            errors += "Assignment must have exactly $expectedQuestionCount questions"
        }

        val expectedIds = (1..expectedQuestionCount).toList()

        if (assignment.questions.map { it.id } != expectedIds) {
            errors += "Question IDs must be continuous from 1 to $expectedQuestionCount"
        }

        if (assignment.answerKey.size != expectedQuestionCount) {
            errors += "Assignment must have exactly $expectedQuestionCount answers"
        }

        if (assignment.answerKey.map { it.id }.sorted() != expectedIds) {
            errors += "Answer IDs must match the question IDs 1 through $expectedQuestionCount"
        }

        if (assignment.gradingGuide.trim().length < 10) {
            errors += "Grading guide is too short"
        }

        if (abs(assignment.totalScore - 10.0) > 0.001) {
            errors += "Total score must be 10"
        }

        val pointsSum =
            assignment.questions.sumOf { it.points }

        if (abs(pointsSum - 10.0) > 0.001) {
            errors += "Question points must sum to 10"
        }

        val hasLearningMaterial =
            !assignment.learningMaterial.isNullOrBlank()

        val requiresLearningMaterial =
            assignment.questions.any {
                it.sourceType ==
                        QuestionSourceType.LESSON_CONTENT ||
                        it.sourceType ==
                        QuestionSourceType.READING_PASSAGE
            }

        if (
            requiresLearningMaterial &&
            !hasLearningMaterial
        ) {
            errors +=
                "Assignment requires learningMaterial but learningMaterial is missing."
        }

        val referencesExternalMaterial =
            assignment.questions.any { question ->
                sourceReferencePatterns.any { pattern ->
                    pattern.containsMatchIn(question.question)
                }
            }

        if (
            referencesExternalMaterial &&
            !hasLearningMaterial
        ) {
            errors +=
                "Question references reading/lesson material but learningMaterial is missing."
        }

        if (
            hasLearningMaterial &&
            assignment.learningMaterial!!
                .trim()
                .length < 80
        ) {
            errors +=
                "learningMaterial is too short."
        }

        val normalizedQuestions =
            mutableSetOf<String>()

        val normalizedObjectives =
            mutableSetOf<String>()

        assignment.questions.forEach { question ->

            val spec = question.gradingSpec
            if (spec.correctAnswer.isBlank()) {
                errors += "Question ${question.id} has no gradingSpec correctAnswer"
            }
            if (!spec.numericTolerance.isFinite() || spec.numericTolerance < 0.0) {
                errors += "Question ${question.id} has invalid numericTolerance"
            }
            if (
                spec.method == RuleGradingMethod.REQUIRED_CONCEPTS &&
                spec.requiredConcepts.isEmpty()
            ) {
                errors += "Question ${question.id} requires at least one grading concept"
            }
            if (
                !isMathSubject &&
                spec.method == RuleGradingMethod.NUMERIC &&
                !isNumericAnswer(spec.correctAnswer)
            ) {
                errors += "Question ${question.id} has a non-numeric answer for NUMERIC grading"
            }
            if (spec.method == RuleGradingMethod.STEP_RUBRIC) {
                if (!isMathSubject) {
                    errors += "Question ${question.id} uses STEP_RUBRIC outside a math subject"
                }
                val rubric = spec.rubric
                if (rubric == null) {
                    errors += "Question ${question.id} uses STEP_RUBRIC without a rubric"
                } else {
                    if (rubric.version != 1) {
                        errors += "Question ${question.id} uses unsupported rubric version ${rubric.version}"
                    }
                    if (rubric.criteria.isEmpty()) {
                        errors += "Question ${question.id} has an empty rubric"
                    }
                    val criterionIds = rubric.criteria.map { it.id.trim() }
                    if (criterionIds.any { it.isBlank() } || criterionIds.distinct().size != criterionIds.size) {
                        errors += "Question ${question.id} has blank or duplicate rubric criterion IDs"
                    }
                    val rubricPoints = rubric.criteria.sumOf { it.points }
                    if (rubric.criteria.any { !it.points.isFinite() || it.points <= 0.0 } ||
                        kotlin.math.abs(rubricPoints - question.points) > 0.01
                    ) {
                        errors += "Question ${question.id} rubric points must be positive and sum to question points"
                    }
                    rubric.criteria.forEach { criterion ->
                        if (criterion.description.isBlank()) {
                            errors += "Question ${question.id} rubric criterion ${criterion.id} has no description"
                        }
                        when (criterion.method) {
                            RubricCriterionMethod.EVIDENCE -> if (criterion.acceptedEvidence.isEmpty()) {
                                errors += "Question ${question.id} rubric criterion ${criterion.id} has no acceptedEvidence"
                            }
                            RubricCriterionMethod.REQUIRED_CONCEPTS -> if (criterion.requiredConcepts.isEmpty()) {
                                errors += "Question ${question.id} rubric criterion ${criterion.id} has no requiredConcepts"
                            }
                            RubricCriterionMethod.FINAL_NUMERIC -> {
                                val expected = criterion.expectedNumber?.replace(',', '.')?.toDoubleOrNull()
                                if (expected == null || !expected.isFinite() ||
                                    !criterion.numericTolerance.isFinite() || criterion.numericTolerance < 0.0
                                ) {
                                    errors += "Question ${question.id} rubric criterion ${criterion.id} has invalid numeric settings"
                                }
                            }
                        }
                    }
                }
            } else if (spec.rubric != null) {
                errors += "Question ${question.id} has a rubric but does not use STEP_RUBRIC"
            }
            if (isMathSubject) {
                val mathSpec = spec.mathAnswerSpec
                if (mathSpec == null) {
                    errors += "Math question ${question.id} has no mathAnswerSpec"
                } else {
                    if (mathSpec.kind == MathAnswerKind.AUTO) {
                        errors += "Math question ${question.id} must use an explicit math answer kind"
                    }
                    if (!mathSpec.absoluteTolerance.isFinite() || mathSpec.absoluteTolerance < 0.0 ||
                        !mathSpec.relativeTolerance.isFinite() || mathSpec.relativeTolerance < 0.0
                    ) {
                        errors += "Math question ${question.id} has invalid answer tolerances"
                    }
                    if (mathSpec.kind == MathAnswerKind.QUANTITY && mathSpec.expectedUnit.isNullOrBlank()) {
                        errors += "Math question ${question.id} uses QUANTITY without expectedUnit"
                    }
                    if (mathSpec.kind == MathAnswerKind.NUMBER) {
                        val invalidAnswers = listOf(spec.correctAnswer) + spec.acceptedAnswers
                        if (invalidAnswers.any { !isNumericAnswer(it) }) {
                            errors += "Math question ${question.id} uses NUMBER but correctAnswer/acceptedAnswers must contain only numbers or fractions"
                        }
                    }
                    if (mathSpec.kind == MathAnswerKind.FILL_BLANK) {
                        val blankCount = Regex("_{2,}|\\.{2,}|…+|□|▢|\\[\\s*\\]")
                            .findAll(question.question).count()
                        if (blankCount !in 1..8) {
                            errors += "Math question ${question.id} declares FILL_BLANK but contains $blankCount supported placeholders; include 1-8 visible ___, □, or [ ] placeholders, or change mathAnswerSpec.kind to NUMBER/CALCULATION"
                        }
                    }
                }
            } else if (spec.mathAnswerSpec != null) {
                errors += "Non-math question ${question.id} must not use mathAnswerSpec"
            }

            if (question.question.isBlank()) {
                errors +=
                    "Question ${question.id} is empty"
            }

            if (question.question.trim().length < 5) {
                errors +=
                    "Question ${question.id} is too short"
            }

            if (question.learningObjective.isBlank()) {
                errors +=
                    "Question ${question.id} has empty learningObjective"
            }

            if (question.learningObjective.trim().length < 5) {
                errors +=
                    "Question ${question.id} learningObjective is too short"
            }

            if (!question.points.isFinite() || question.points <= 0) {
                errors +=
                    "Question ${question.id} has invalid points"
            }

            if (
                question.answerType == AnswerType.DRAWING ||
                question.answerType == AnswerType.MIXED
            ) {
                errors +=
                    "Question ${question.id} uses unsupported answer type"
            }

            try {
                validateGradingCompatibility(
                    answerType = question.answerType,
                    gradingMethod = question.gradingMethod
                )
            } catch (e: Exception) {
                errors +=
                    "Question ${question.id}: ${e.message}"
            }

            if (containsSuspiciousText(question.question)) {
                errors +=
                    "Question ${question.id} contains suspicious text"
            }

            if (containsSuspiciousText(
                    question.learningObjective
                )
            ) {
                errors +=
                    "Question ${question.id} learningObjective contains suspicious text"
            }

            if (
                question.question.any { it == '\uFFFD' }
            ) {
                errors +=
                    "Question ${question.id} contains invalid characters"
            }

            val veryLongWord =
                question.question
                    .split(Regex("\\s+"))
                    .any { it.length > 40 }

            if (veryLongWord) {
                errors +=
                    "Question ${question.id} contains an unusually long word"
            }

            val repeatedCharacters =
                Regex("""(.)\1{7,}""")
                    .containsMatchIn(question.question)

            if (repeatedCharacters) {
                errors +=
                    "Question ${question.id} contains repeated characters"
            }

            val normalizedQuestion =
                normalizeQuestion(question.question)

            if (!normalizedQuestions.add(normalizedQuestion)) {
                errors +=
                    "Duplicate question detected: ${question.id}"
            }

            val normalizedObjective =
                normalizeSemanticText(
                    question.learningObjective
                )

            if (!normalizedObjectives.add(normalizedObjective)) {
                errors +=
                    "Duplicate learning objective detected: ${question.id}"
            }

            if (
                question.sourceType !=
                QuestionSourceType.SELF_CONTAINED &&
                !hasLearningMaterial
            ) {
                errors +=
                    "Question ${question.id} requires source material"
            }
        }

        val answersById =
            assignment.answerKey.associateBy { it.id }

        assignment.questions.forEach { question ->

            val answer = answersById[question.id]

            if (question.options.isNotEmpty()) {
                if (question.options.size != 4 ||
                    question.options.any { it.isBlank() } ||
                    question.options.map { normalizeSemanticText(it) }.distinct().size != 4
                ) {
                    errors += "Question ${question.id} must have exactly 4 distinct non-empty choices"
                }
                if (answer?.answer?.trim()?.uppercase(Locale.ROOT) !in setOf("A", "B", "C", "D")) {
                    errors += "Multiple-choice question ${question.id} answer must be A, B, C, or D"
                }
                if (isMathSubject && question.gradingSpec.mathAnswerSpec?.kind != MathAnswerKind.MULTIPLE_CHOICE) {
                    errors += "Math multiple-choice question ${question.id} must use MULTIPLE_CHOICE grading"
                }
            }

            if (answer == null) {
                errors +=
                    "Missing answer for question ${question.id}"
            } else if (answer.answer.isBlank()) {
                errors +=
                    "Answer ${answer.id} is empty"
            } else if (
                containsSuspiciousText(answer.answer)
            ) {
                errors +=
                    "Answer ${answer.id} contains suspicious text"
            }
        }

        if (isMathSubject) {
            val choiceCount = when (grade) {
                in 1..5 -> 10
                in 6..9 -> 11
                else -> 12
            }
            assignment.questions.forEach { question ->
                val expectsChoice = question.id <= choiceCount
                val expectsTrueFalse = grade >= 10 && question.id in 13..16
                val expectedPoints = mathQuestionPoints(grade, question.id)
                if (abs(question.points - expectedPoints) > 0.001) {
                    errors += "Math question ${question.id} must be worth $expectedPoints points in its grade matrix"
                }
                if (expectsChoice && question.options.size != 4) {
                    errors += "Math question ${question.id} must include four choices"
                }
                if (!expectsChoice && question.options.isNotEmpty()) {
                    errors += "Math question ${question.id} must be a written/structured response"
                }
                if (expectsTrueFalse && question.statements.size != 4) {
                    errors += "True/false question ${question.id} must include four statements"
                }
                if (!expectsTrueFalse && question.statements.isNotEmpty()) {
                    errors += "Question ${question.id} has unexpected true/false statements"
                }
                val answer = assignment.answerKey.firstOrNull { it.id == question.id }?.answer.orEmpty().trim()
                if (expectsChoice && answer.uppercase(Locale.ROOT) !in setOf("A", "B", "C", "D")) {
                    errors += "Multiple-choice question ${question.id} answer must be A, B, C, or D"
                }
                if (expectsChoice && question.gradingSpec.mathAnswerSpec?.kind != MathAnswerKind.MULTIPLE_CHOICE) {
                    errors += "Multiple-choice question ${question.id} must use MULTIPLE_CHOICE grading"
                }
                if (expectsTrueFalse) {
                    val truthValues = Regex("(?i)(đúng|sai|true|false|đ|s)(?![\\p{L}])")
                        .findAll(answer).count()
                    if (truthValues != 4) errors += "True/false answer ${question.id} must contain four Đ/S values"
                    if (question.gradingSpec.mathAnswerSpec?.kind != MathAnswerKind.TRUE_FALSE_SET) {
                        errors += "True/false question ${question.id} must use TRUE_FALSE_SET grading"
                    }
                }
            }
        }

        if (errors.isNotEmpty()) {
            throw IllegalStateException(
                "Assignment validation failed:\n" +
                        errors.joinToString("\n") {
                            "- $it"
                        }
            )
        }
    }

    private fun containsSuspiciousText(
        text: String
    ): Boolean {

        val suspicious = listOf(
            "undefined",
            "null null",
            "lorem ipsum",
            "asdf",
            "qwerty",
            "todo",
            "placeholder"
        )

        return suspicious.any {
            text.contains(
                it,
                ignoreCase = true
            )
        }
    }

    private fun normalizeQuestion(
        text: String
    ): String {
        return text
            .lowercase()
            .replace(Regex("\\s+"), " ")
            .trim()
            .replace(
                Regex("[.!?,;:]+$"),
                ""
            )
    }

    private fun normalizeSemanticText(
        text: String
    ): String {
        return text
            .lowercase()
            .replace(
                Regex("[^\\p{L}\\p{N}\\s]"),
                " "
            )
            .replace(Regex("\\s+"), " ")
            .trim()
    }

// ============================================================
// SEX EDUCATION VALIDATION
// ============================================================

    private fun validateSexEducationAssignment(
        assignment: GeneratedAssignment,
        grade: Int
    ) {

        val errors = mutableListOf<String>()

        if (assignment.questions.size != 3) {
            errors +=
                "Sex education assignment must have exactly 3 questions"
        }

        if (assignment.answerKey.size != 3) {
            errors +=
                "Sex education assignment must have exactly 3 answers"
        }

        if (abs(assignment.totalScore - 10.0) > 0.001) {
            errors +=
                "Sex education totalScore must be 10"
        }

        assignment.questions.forEach { question ->

            if (question.question.isBlank()) {
                errors +=
                    "Question ${question.id} is empty"
            }

            if (
                question.answerType == AnswerType.DRAWING ||
                question.answerType == AnswerType.MIXED
            ) {
                errors +=
                    "Question ${question.id} uses unsupported answer type"
            }

            if (
                question.answerType == AnswerType.TEXT &&
                question.gradingMethod == GradingMethod.EXACT
            ) {
                errors +=
                    "Question ${question.id}: open-ended sex education " +
                            "question cannot use EXACT grading"
            }

            val normalized =
                question.question.lowercase()

            val unsafePatterns = listOf(
                "hãy kể trải nghiệm tình dục",
                "hãy mô tả quan hệ tình dục",
                "gửi ảnh cơ thể",
                "gửi ảnh riêng tư",
                "video riêng tư"
            )

            unsafePatterns.forEach { pattern ->
                if (normalized.contains(pattern)) {
                    errors +=
                        "Question ${question.id} contains inappropriate private-content request"
                }
            }
        }

        if (grade in 1..5) {

            assignment.questions.forEach { question ->

                val text =
                    question.question.lowercase()

                val overlyAdvancedTerms = listOf(
                    "thuốc tránh thai",
                    "biện pháp tránh thai",
                    "sti",
                    "bệnh lây truyền qua đường tình dục"
                )

                if (
                    overlyAdvancedTerms.any {
                        text.contains(it)
                    }
                ) {
                    errors +=
                        "Question ${question.id} may exceed age scope for grade $grade"
                }
            }
        }

        if (errors.isNotEmpty()) {
            throw IllegalStateException(
                "Sex education validation failed:\n" +
                        errors.joinToString("\n") {
                            "- $it"
                        }
            )
        }

        println(
            "[AIService] Sex education validation PASS grade=$grade"
        )
    }

// ============================================================
// GRADING COMPATIBILITY
// ============================================================

    private fun validateGradingCompatibility(
        answerType: AnswerType,
        gradingMethod: GradingMethod
    ) {

        val valid = when (answerType) {

            AnswerType.TEXT ->
                gradingMethod == GradingMethod.EXACT ||
                        gradingMethod == GradingMethod.AI_TEXT

            AnswerType.HANDWRITING ->
                gradingMethod == GradingMethod.OCR_AI

            AnswerType.DRAWING ->
                gradingMethod == GradingMethod.OPENCV ||
                        gradingMethod ==
                        GradingMethod.OPENCV_VISION_AI

            AnswerType.SPEECH_TO_TEXT ->
                gradingMethod == GradingMethod.EXACT ||
                        gradingMethod == GradingMethod.AI_TEXT

            AnswerType.MIXED ->
                true
        }

        if (!valid) {
            throw IllegalStateException(
                "Invalid answerType/gradingMethod combination: " +
                        "$answerType + $gradingMethod"
            )
        }
    }

// ============================================================
// QUALITY REVIEW
// ============================================================

    suspend fun reviewGeneratedAssignment(
        assignment: GeneratedAssignment,
        grade: Int,
        subject: String,
        topic: String?,
        difficulty: Difficulty,
        learningStepTitle: String? = null,
        learningStepSkill: String? = null,
        learningStepDescription: String? = null
    ): AssignmentQualityReview {

        val sexEducation =
            isSexEducation(subject)

        println(
            "[AIService] Reviewing generated assignment " +
                    "grade=$grade subject=$subject topic=$topic difficulty=$difficulty"
        )

        val blueprint =
            if (sexEducation) {
                createSexEducationBlueprint(
                    grade = grade,
                    topic = topic
                        ?: "sức khỏe giới tính phù hợp độ tuổi",
                    difficulty = difficulty
                )
            } else {
                createBlueprint(
                    grade = grade,
                    subject = subject,
                    topic = topic,
                    difficulty = difficulty,
                    learningStepTitle = learningStepTitle,
                    learningStepSkill = learningStepSkill,
                    learningStepDescription = learningStepDescription
                )
            }

        val prompt =
            buildQualityReviewPrompt(
                assignment = assignment,
                grade = grade,
                subject = subject,
                topic = topic,
                difficulty = difficulty,
                blueprint = blueprint
            )

        val response =
            withContext(Dispatchers.IO) {
                callGeminiWithRetry(
                    prompt = prompt,
                    sexEducation = sexEducation,
                    temperature = 0.1
                )
            }

        val review =
            parseQualityReviewResponse(
                responseText = response
            )

        println(
            "[AIService] Quality review pass=${review.pass} " +
                    "issues=${review.issues}"
        )

        return review
    }

    private fun buildQualityReviewPrompt(
        assignment: GeneratedAssignment,
        grade: Int,
        subject: String,
        topic: String?,
        difficulty: Difficulty,
        blueprint: GenerationBlueprint,
        learningStepTitle: String? = null,
        learningStepSkill: String? = null,
        learningStepDescription: String? = null
    ): String {

        val isMathSubject = subject.contains("toán", ignoreCase = true) ||
                subject.contains("math", ignoreCase = true)
        val expectedQuestionCount = if (isMathSubject) mathQuestionCount(grade) else 3
        val cognitiveReview = if (isMathSubject) {
            "Kiểm tra đủ $expectedQuestionCount câu và phân bố mức độ theo blueprint/ma trận; không yêu cầu chỉ 3 câu."
        } else {
            "Q1 nền tảng, Q2 vận dụng, Q3 suy luận/phân tích. Nếu cả 3 câu chỉ cùng một thao tác thì FAIL."
        }
        val pedagogyReview = if (isMathSubject) {
            "Toàn bộ câu hỏi phải tuân thủ ma trận theo lớp, dạng câu, điểm và phạm vi kiến thức."
        } else {
            "Không được tạo ba câu chỉ để đủ số lượng. Ba câu phải tạo progression Q1 hiểu nền tảng, Q2 vận dụng, Q3 suy luận/phân tích."
        }
        val matrixReview = if (!isMathSubject) "" else when (grade) {
            in 1..5 -> "10 câu; ID 1-8 số học, 9-10 hình học; cả 10 câu bốn lựa chọn, 1 điểm/câu."
            in 6..9 -> "16 câu; ID 1-9 đại số, 10-14 hình học, 15-16 thống kê/xác suất; ID 1-11 có 4 lựa chọn; điểm phải theo ma trận 0.5/1.0/0.6/0.75."
            else -> "22 câu; ID 1-12 có 4 lựa chọn; ID 13-16 có 4 mệnh đề đúng/sai và tính điểm theo tỉ lệ; ID 17-22 trả lời ngắn; điểm 0.25/1/0.5 theo phần."
        }

        val questionsText = assignment.questions
            .joinToString("\n\n") { q ->
                """
            Q${q.id}
            Question: ${q.question}
            Learning objective: ${q.learningObjective}
            Points: ${q.points}
            Answer type: ${q.answerType}
            Grading method: ${q.gradingMethod}
            Source type: ${q.sourceType}
            Choices: ${q.options.mapIndexed { index, value -> "${('A'.code + index).toChar()}. $value" }.ifEmpty { listOf("(none)") }.joinToString(" | ")}
            Statements: ${q.statements.mapIndexed { index, value -> "${('a'.code + index).toChar()}) $value" }.ifEmpty { listOf("(none)") }.joinToString(" | ")}
            """.trimIndent()
            }

        val answersText = assignment.answerKey
            .joinToString("\n") {
                "Answer ${it.id}: ${it.answer}"
            }

        val learningMaterialText =
            assignment.learningMaterial
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: "(Không có learningMaterial)"
        val learningStepContext =
            buildLearningStepContext(
                learningStepTitle = learningStepTitle,
                learningStepSkill = learningStepSkill,
                learningStepDescription = learningStepDescription
            )
        return """
        Bạn là reviewer chất lượng giáo dục.

        KHÔNG sửa bài.
        KHÔNG viết lại câu hỏi.
        Chỉ đánh giá bài tập có PASS hay FAIL.

        === CONTEXT ===
        Grade: $grade
        Subject: $subject
        Topic: ${topic ?: "auto"}
        Difficulty: $difficulty
$learningStepContext
        === BLUEPRINT MONG MUỐN ===
        ${blueprintAsPrompt(blueprint)}

        === MA TRẬN CẦN ĐỐI CHIẾU ===
        $matrixReview

        === ASSIGNMENT ===

        Title:
        ${assignment.title}

        Learning material:
        $learningMaterialText

        Questions:
        $questionsText

        Answer key:
        $answersText

        Grading guide:
        ${assignment.gradingGuide}

        Total score:
        ${assignment.totalScore}

        ============================================================
        REVIEW CRITERIA
        ============================================================

        1. NGÔN NGỮ

        - Chính tả đúng.
        - Từ ngữ tự nhiên bằng tiếng Việt.
        - Ngữ pháp đúng.
        - Không có từ vô nghĩa.
        - Không có câu dịch máy khó hiểu.
        - Không có lỗi ngữ nghĩa.
        - Không có placeholder.
        - Không có từ hoặc cụm từ bị ghép sai.

        Các ví dụ như:
        - "bà giắt"
        - "bat ngô"
        - "con vật thực hiện phép cộng"
        - "một cây có thể chạy nhanh"

        hoặc bất kỳ câu tương tự vô nghĩa nào đều phải FAIL.

        ------------------------------------------------------------

        2. Ý NGHĨA CÂU HỎI

        Mỗi câu phải:

        - hiểu được ngay;
        - có đủ dữ kiện;
        - không mâu thuẫn;
        - không mơ hồ;
        - không có nhiều cách hiểu ngoài ý muốn;
        - thực sự kiểm tra kiến thức/kỹ năng đã nêu.

        Nếu câu hỏi có lỗi logic hoặc không thể trả lời hợp lý:
        FAIL.

        ------------------------------------------------------------

        3. ĐÚNG KIẾN THỨC

        Kiểm tra:

        - kiến thức;
        - công thức;
        - thuật ngữ;
        - dữ kiện;
        - answerKey;
        - gradingGuide.

        Không được có kết luận khoa học sai.

        Nếu answerKey sai hoặc không trả lời đúng câu hỏi:
        FAIL.

        ------------------------------------------------------------

        4. PHÙ HỢP LỚP

        - Kiến thức phải phù hợp lớp $grade.
        - Không sử dụng thuật ngữ vượt quá trình độ nếu không được giải thích.
        - Độ dài câu hỏi phù hợp.
        - Cách diễn đạt phù hợp học sinh.

        ------------------------------------------------------------

        5. ĐỘ KHÓ

        Độ khó mong muốn:

        $difficulty

        Không được:

        - quá dễ so với yêu cầu;
        - quá khó so với lớp;
        - tăng độ khó giả tạo bằng cách dùng số lớn;
        - tăng độ khó giả tạo bằng cách viết câu dài.

        ------------------------------------------------------------
5B. LEARNING PATH COMPLIANCE

Nếu có LearningStep hiện tại:

- LearningStep là mục tiêu curriculum bắt buộc.
- Tất cả câu hỏi phải chủ yếu đánh giá Skill hiện tại.
- Không được nhảy sang kỹ năng của step tiếp theo.
- Không được dùng skill nâng cao hơn chỉ để tăng độ khó.
- Topic rộng không được dùng làm lý do để bỏ qua LearningStep.

Đặc biệt:

Cognitive progression Q1 → Q2 → Q3 phải xảy ra
BÊN TRONG SKILL HIỆN TẠI.

Ví dụ:

Nếu current skill là:
"Nhận biết tử số và mẫu số"

thì:

PASS:
- nhận diện tử số;
- nhận diện mẫu số;
- giải thích vai trò;
- phát hiện lỗi xác định tử số/mẫu số;
- áp dụng trong ngữ cảnh vẫn yêu cầu nhận biết tử số/mẫu số.

FAIL:
- so sánh phân số;
- quy đồng mẫu số;
- cộng phân số;
- trừ phân số;

nếu các kỹ năng đó thuộc các LearningStep sau.

Nếu một hoặc nhiều câu vượt current LearningStep:
FAIL.
        6. COGNITIVE PROGRESSION

        $cognitiveReview

        ------------------------------------------------------------

        7. SEMANTIC DIVERSITY

        FAIL nếu:

        - chỉ đổi số;
        - chỉ đổi tên;
        - chỉ đổi vài từ;
        - cùng context;
        - cùng answer pattern;
        - cùng cách giải;
        - cùng learning objective;
        - các câu sau chỉ dài hơn nhưng không sâu hơn.

        PASS nếu:

        - cùng kiến thức nhưng khác cách vận dụng;
        - khác reasoning;
        - khác context;
        - khác kỹ năng;
        - hoặc có tiến triển nhận thức rõ ràng.

        ------------------------------------------------------------

        8. ANSWER ALIGNMENT

        Với từng Q:

        Question N phải khớp Answer N với mọi ID từ 1 đến $expectedQuestionCount.

        Phải kiểm tra:

        - answer thực sự trả lời question;
        - answer không thuộc câu khác;
        - answer không chứa thông tin mâu thuẫn;
        - answer đủ để chấm theo gradingGuide.

        Nếu answer không phù hợp câu hỏi:
        FAIL.

        ------------------------------------------------------------

        9. GRADING

        GradingGuide phải:

        - phù hợp câu hỏi;
        - phù hợp answerKey;
        - phù hợp số điểm;
        - có thể dùng để chấm câu trả lời thực tế.

        Không được yêu cầu tiêu chí mà answerKey không hỗ trợ.

        ------------------------------------------------------------

        10. ANSWER TYPE

        Chỉ được sử dụng:

        TEXT
        HANDWRITING
        SPEECH_TO_TEXT

        Mapping:

        TEXT -> EXACT hoặc AI_TEXT
        HANDWRITING -> OCR_AI
        SPEECH_TO_TEXT -> EXACT hoặc AI_TEXT

        DRAWING hoặc MIXED:
        FAIL.

        ------------------------------------------------------------

        11. PEDAGOGY

        Bài tập phải có giá trị giáo dục.

        $pedagogyReview

        ------------------------------------------------------------

        12. LEARNING MATERIAL / SOURCE TYPE

        Mỗi câu có:

        - SELF_CONTAINED
        - LESSON_CONTENT
        - hoặc READING_PASSAGE

        Kiểm tra chính xác sự phù hợp giữa sourceType,
        question và learningMaterial.

        === SELF_CONTAINED ===

        Nếu sourceType = SELF_CONTAINED:

        - Câu hỏi phải có đủ dữ kiện để trả lời.
        - Không được phụ thuộc vào tài liệu không được cung cấp.

        === LESSON_CONTENT ===

        Nếu sourceType = LESSON_CONTENT:

        - learningMaterial phải tồn tại.
        - learningMaterial phải liên quan trực tiếp đến câu hỏi.
        - Nội dung cần thiết phải có trong learningMaterial.
        - Học sinh không được phải tự tìm thêm nguồn bên ngoài.

        === READING_PASSAGE ===

        Nếu sourceType = READING_PASSAGE:

        - learningMaterial bắt buộc phải tồn tại.
        - learningMaterial phải là một bài đọc/nội dung đọc có ý nghĩa.
        - Câu hỏi phải dựa trực tiếp hoặc suy luận hợp lý từ bài đọc.
        - AnswerKey phải có thể tìm thấy hoặc suy luận hợp lý từ bài đọc.

        ------------------------------------------------------------

        13. LEARNING MATERIAL QUALITY

        Nếu assignment có learningMaterial:

        - material phải thực sự liên quan đến assignment;
        - không phải nội dung ngẫu nhiên;
        - không phải filler;
        - không được chỉ tạo material để đối phó validator;
        - phải phù hợp lớp;
        - phải phù hợp môn học;
        - phải phù hợp chủ đề.

        Nếu material quá ngắn để hỗ trợ các câu hỏi:
        FAIL.

        Nếu material chứa thông tin mâu thuẫn với answerKey:
        FAIL.

        Nếu câu hỏi yêu cầu thông tin không có trong material
        và sourceType cho biết câu hỏi phụ thuộc vào material:
        FAIL.

        ------------------------------------------------------------

        14. EXTERNAL REFERENCE CHECK

         FAIL nếu câu hỏi gọi tên một tác phẩm/truyện/bài thơ cụ thể
        (ví dụ: "câu chuyện Rùa và Thỏ", "truyện Thánh Gióng",
        "bài thơ Lượm", "tác phẩm Tắt đèn"...) mà learningMaterial
        không chứa nội dung tương ứng.

        Không được PASS chỉ vì AI ghi sourceType = SELF_CONTAINED
        trong khi câu hỏi vẫn yêu cầu học sinh biết nội dung
        của một tác phẩm bên ngoài.

        - "Đọc đoạn văn trên..."
        - "Dựa vào bài học trên..."
        - "Theo nội dung trên..."
        - "Theo bảng trên..."
        - "Nhìn vào hình trên..."
        - "Dựa vào đoạn văn..."
        - "Theo bài đọc..."

        nhưng learningMaterial không cung cấp nội dung tương ứng.

        Không được yêu cầu học sinh:

        - tự mở sách giáo khoa;
        - tự tìm Internet;
        - tự tìm tài liệu bên ngoài;

        để có thể trả lời một câu hỏi vốn được đánh dấu
        là LESSON_CONTENT hoặc READING_PASSAGE.

        ------------------------------------------------------------

        15. SEX EDUCATION

        Nếu đây là giáo dục sức khỏe giới tính:

        - phù hợp độ tuổi;
        - khoa học;
        - không khiêu dâm;
        - không sexualize trẻ em;
        - không yêu cầu trải nghiệm cá nhân;
        - không yêu cầu thông tin riêng tư;
        - không yêu cầu ảnh/video riêng tư;
        - không làm học sinh xấu hổ;
        - ưu tiên an toàn;
        - ưu tiên ranh giới cá nhân;
        - ưu tiên quyền từ chối;
        - ưu tiên tìm người lớn đáng tin cậy khi cần.

        ------------------------------------------------------------

        === QUYẾT ĐỊNH ===

        Chỉ PASS khi tất cả tiêu chí quan trọng đều đạt.

        Chỉ một lỗi nghiêm trọng về:

        - tính đúng đắn;
        - answer alignment;
        - learningMaterial;
        - sourceType;
        - độ tuổi;
        - ngôn ngữ;
        - hoặc an toàn

        cũng phải FAIL.

        Không được PASS chỉ vì bài có đúng cấu trúc JSON.

        Trả về JSON duy nhất:

        {
          "pass": true,
          "issues": [],
          "summary": "..."
        }

        Nếu FAIL:
        - pass = false
        - issues phải liệt kê rõ từng lỗi.
        - summary phải giải thích ngắn gọn nguyên nhân.

        Không trả markdown.
        Không trả text ngoài JSON.
    """.trimIndent()
    }

    private fun parseQualityReviewResponse(
        responseText: String
    ): AssignmentQualityReview {

        val root =
            parseGeminiJsonResponse(
                responseText
            ).jsonObject

        val pass =
            root["pass"]
                ?.jsonPrimitive
                ?.booleanOrNull
                ?: throw IllegalStateException(
                    "Quality review missing pass"
                )

        val issues =
            root["issues"]
                ?.jsonArray
                ?.mapNotNull {
                    it.jsonPrimitive.contentOrNull
                }
                ?: emptyList()

        val summary =
            root["summary"]
                ?.jsonPrimitive
                ?.contentOrNull
                ?: ""

        return AssignmentQualityReview(
            pass = pass,
            issues = issues,
            summary = summary
        )
    }

// ============================================================
// GRADING
// ============================================================

    suspend fun gradeAssignment(
        assignment: GeneratedAssignment,
        studentAnswer: String,
        subject: String = ""
    ): GradingResult {

        val sexEducation =
            isSexEducation(subject) ||
                    looksLikeSexEducationAssignment(
                        assignment
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

        val key =
            if (sexEducation) {
                sexEducationApiKey ?: apiKey
            } else {
                apiKey
            }

        require(!key.isNullOrBlank()) {
            "Gemini API key is not configured"
        }

        val response =
            withContext(Dispatchers.IO) {
                callGeminiWithRetry(
                    prompt = prompt,
                    sexEducation = sexEducation,
                    temperature = 0.2
                )
            }

        return parseGradingResponse(
            responseText = response
        )
    }

    private fun looksLikeSexEducationAssignment(
        assignment: GeneratedAssignment
    ): Boolean {

        val combined =
            buildString {
                append(assignment.title)
                append(" ")

                assignment.questions.forEach {
                    append(it.question)
                    append(" ")
                }

                assignment.learningMaterial
                    ?.let {
                        append(it)
                    }
            }.lowercase()

        val keywords = listOf(
            "dậy thì",
            "sức khỏe sinh sản",
            "kinh nguyệt",
            "đồng thuận",
            "ranh giới cá nhân",
            "cơ thể",
            "riêng tư"
        )

        return keywords.count {
            combined.contains(it)
        } >= 2
    }

    private fun buildGradingPrompt(
        assignment: GeneratedAssignment,
        studentAnswer: String
    ): String {

        val material =
            assignment.learningMaterial
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: "(Không có learningMaterial)"

        val questions =
            assignment.questions.joinToString("\n\n") { q ->

                """
            Question ${q.id}:
            ${q.question}

            Source type:
            ${q.sourceType}

            Expected answer:
            ${
                    assignment.answerKey
                        .firstOrNull { a ->
                            a.id == q.id
                        }
                        ?.answer ?: ""
                }

            Learning objective:
            ${q.learningObjective}

            Points:
            ${q.points}

            Answer type:
            ${q.answerType}

            Grading method:
            ${q.gradingMethod}
            """.trimIndent()
            }

        return """
    Bạn là giáo viên chấm bài cho học sinh.

    Nhiệm vụ của bạn không chỉ là cho điểm.
    Bạn phải:
    1. Chấm chính xác từng câu.
    2. Xác định học sinh đúng, sai hoặc đúng một phần.
    3. Nếu sai, phải chỉ rõ học sinh sai ở đâu.
    4. Giải thích tại sao cách làm hoặc câu trả lời đó sai.
    5. Hướng dẫn cách làm đúng hoặc cách sửa.
    6. Nếu đúng, giải thích ngắn gọn vì sao đúng để học sinh củng cố kiến thức.
    7. Feedback phải mang tính giảng dạy, giúp học sinh học được từ lỗi sai.
    8. Không được bịa thêm dữ kiện hoặc kiến thức không cần thiết.

    === ASSIGNMENT ===

    Title:
    ${assignment.title}

    === LEARNING MATERIAL ===

    $material

    === QUESTIONS ===

    $questions

    === GRADING GUIDE ===

    ${assignment.gradingGuide}

    === TOTAL SCORE ===

    ${assignment.totalScore}

    === STUDENT ANSWER ===

    $studentAnswer

    === RULES ===

    1. NGUYÊN TẮC CHẤM

    - Chấm đúng theo câu hỏi, answerKey, learningObjective và gradingGuide.
    - Không thay đổi thang điểm.
    - Tổng điểm tối đa là ${assignment.totalScore}.
    - Điểm của từng câu không được vượt quá số points của câu đó.
    - Tổng điểm phải bằng tổng điểm các câu.
    - Nếu học sinh đúng hoàn toàn, cho đầy đủ điểm.
    - Nếu học sinh đúng một phần, cho điểm tương ứng với phần kiến thức/kỹ năng đúng.
    - Nếu học sinh sai hoàn toàn, cho 0 điểm.
    - Chấp nhận cách diễn đạt khác answerKey nếu nội dung và ý nghĩa đúng.
    - Không phạt chỉ vì học sinh dùng từ khác answerKey.
    - Không đoán ý học sinh ngoài nội dung studentAnswer.
    - Không tự tạo dữ kiện không có trong assignment.

    2. ĐÁNH GIÁ TỪNG CÂU

    Với MỖI câu hỏi, phải tạo một QuestionGradingResult.

    Feedback của từng câu phải:

    - Nói rõ câu đó đúng, sai hoặc đúng một phần.
    - Nếu đúng:
      + giải thích ngắn gọn tại sao câu trả lời đúng;
      + nhắc lại kiến thức hoặc quy tắc quan trọng nếu phù hợp.

    - Nếu đúng một phần:
      + chỉ rõ phần nào học sinh đã làm đúng;
      + chỉ rõ phần nào còn thiếu hoặc sai;
      + hướng dẫn phần cần sửa.

    - Nếu sai:
      + chỉ rõ lỗi cụ thể;
      + giải thích nguyên nhân của lỗi;
      + đưa ra cách làm đúng;
      + nếu là bài toán, nên trình bày các bước giải ngắn gọn;
      + không chỉ nói "sai" hoặc "chưa chính xác".

    - Nếu bỏ trống:
      + cho 0 điểm;
      + nói rằng học sinh chưa trả lời;
      + đưa ra hướng dẫn hoặc đáp án giải thích ngắn gọn để học sinh học được.

    3. GIẢI THÍCH PHẢI DỰA TRÊN BÀI TẬP

    - Giải thích phải dựa trên question, answerKey, learningObjective,
      gradingGuide và learningMaterial khi phù hợp.
    - Không sử dụng thông tin ngoài assignment nếu không cần thiết.
    - Không bịa lời giải.
    - Không thay đổi đáp án đúng chỉ để khớp với studentAnswer.

    4. SOURCE TYPE

    SELF_CONTAINED:
    - Chấm dựa trên chính nội dung câu hỏi, answerKey,
      learningObjective và gradingGuide.
    - Không cần learningMaterial để chấm.

    LESSON_CONTENT:
    - Nếu câu hỏi yêu cầu kiến thức từ learningMaterial,
      sử dụng learningMaterial làm nguồn ngữ cảnh chính.
    - Không tự bổ sung thông tin từ Internet hoặc nguồn ngoài
      nếu learningMaterial đã cung cấp đủ thông tin.
    - Nếu câu trả lời phù hợp với learningMaterial,
      phải công nhận dù cách diễn đạt khác answerKey.

    READING_PASSAGE:
    - Đánh giá dựa trên learningMaterial.
    - Với câu hỏi đọc hiểu, chỉ chấp nhận thông tin có trong bài đọc
      hoặc suy luận hợp lý trực tiếp từ bài đọc.
    - Không yêu cầu học sinh biết thêm thông tin ngoài bài đọc.
    - Nếu học sinh trả lời đúng dựa trên bài đọc nhưng dùng cách diễn đạt
      khác answerKey, vẫn phải cho điểm tương ứng.

    5. LEARNING MATERIAL

    - learningMaterial chỉ là nguồn ngữ cảnh khi câu hỏi yêu cầu.
    - Không coi learningMaterial không tồn tại là lỗi đối với SELF_CONTAINED.
    - Nếu câu hỏi là LESSON_CONTENT hoặc READING_PASSAGE nhưng
      learningMaterial bị thiếu, không được tự bịa nội dung còn thiếu.

    6. KHÔNG SUY DIỄN QUÁ MỨC

    - Không suy đoán ý định của học sinh.
    - Không tự bổ sung câu trả lời còn thiếu.
    - Không coi câu trả lời mơ hồ là đúng nếu không có đủ căn cứ.
    - Tuy nhiên, không được đánh sai chỉ vì học sinh diễn đạt khác
      answerKey nhưng vẫn thể hiện đúng kiến thức.

    7. NGÔN NGỮ FEEDBACK

    Feedback phải viết bằng tiếng Việt.

    Feedback dành cho học sinh, không phải báo cáo kỹ thuật.

    Không dùng các câu chung chung như:
    - "Sai."
    - "Chưa đúng."
    - "Cần cố gắng."
    - "Đáp án chưa chính xác."

    Nếu câu trả lời sai, phải nói rõ:
    - Sai ở đâu?
    - Vì sao sai?
    - Cách làm đúng là gì?

    Nếu câu trả lời đúng, không cần viết quá dài nhưng nên củng cố
    kiến thức quan trọng.

    8. FEEDBACK TỔNG QUAN

    Trường "feedback" cấp assignment phải là nhận xét tổng quan.

    Feedback tổng quan cần:
    - nhận xét kết quả chung;
    - nêu điểm mạnh;
    - nêu lỗi hoặc kiến thức cần cải thiện;
    - đưa ra một lời khuyên học tập ngắn gọn.

    Không lặp lại toàn bộ feedback của từng câu trong feedback tổng quan.

    9. OUTPUT

    Chỉ trả về JSON hợp lệ.
    Không markdown.
    Không thêm text bên ngoài JSON.

    JSON bắt buộc có cấu trúc:

    {
      "score": 0,
      "feedback": "...",
      "questions": [
        {
          "id": 1,
          "score": 0,
          "feedback": "..."
        }
      ]
    }

    Quy tắc output:

    - Phải có đúng một phần tử trong "questions" cho MỖI câu hỏi.
    - "id" phải đúng với id của câu hỏi.
    - Không được bỏ sót câu hỏi.
    - Không được tạo thêm câu hỏi không tồn tại.
    - "score" của mỗi câu không được vượt quá points của câu đó.
    - "score" tổng phải nằm trong khoảng:
      0 <= score <= ${assignment.totalScore}

    - "score" tổng phải bằng tổng score của các câu.
    - Tất cả score phải là số hữu hạn.
    - Feedback phải là chuỗi tiếng Việt.
""".trimIndent()
    }



    private fun buildSexEducationGradingPrompt(
        assignment: GeneratedAssignment,
        studentAnswer: String
    ): String {

        val material =
            assignment.learningMaterial
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: "(Không có learningMaterial)"

        val questions =
            assignment.questions.joinToString("\n\n") { q ->

                """
            Question ${q.id}:
            ${q.question}

            Source type:
            ${q.sourceType}

            Expected answer:
            ${
                    assignment.answerKey
                        .firstOrNull { a ->
                            a.id == q.id
                        }
                        ?.answer ?: ""
                }

            Learning objective:
            ${q.learningObjective}

            Points:
            ${q.points}

            Answer type:
            ${q.answerType}

            Grading method:
            ${q.gradingMethod}
            """.trimIndent()
            }

        return """
        Bạn là giáo viên chấm bài giáo dục sức khỏe
        giới tính/sức khỏe sinh sản phù hợp độ tuổi.

        === ASSIGNMENT ===
        Title:
        ${assignment.title}

        === LEARNING MATERIAL ===
        $material

        === QUESTIONS ===
        $questions

        === GRADING GUIDE ===
        ${assignment.gradingGuide}

        === TOTAL SCORE ===
        ${assignment.totalScore}

        === STUDENT ANSWER ===
        $studentAnswer

        === NGUYÊN TẮC CHẤM ===

        1. CHẤM ĐÚNG NỘI DUNG
        - Chấm dựa trên câu hỏi, answerKey, learningObjective
          và gradingGuide.
        - Không thay đổi thang điểm.
        - Điểm tối đa là ${assignment.totalScore}.
        - Nếu học sinh đúng một phần, cho điểm tương ứng.
        - Chấp nhận cách diễn đạt khác answerKey nếu ý nghĩa khoa học đúng.
        - Không phạt chỉ vì học sinh dùng từ khác answerKey.
        - Không tự tạo dữ kiện không có trong assignment.
        - Không đoán ý học sinh ngoài nội dung studentAnswer.

        2. SOURCE TYPE

        SELF_CONTAINED:
        - Câu hỏi tự chứa thông tin cần thiết để trả lời.
        - Không cần learningMaterial để chấm.
        - Không tự yêu cầu học sinh cung cấp thông tin cá nhân
          nếu câu hỏi không yêu cầu.

        LESSON_CONTENT:
        - Nếu câu hỏi dựa trên nội dung bài học,
          sử dụng learningMaterial làm nguồn ngữ cảnh chính.
        - Không tự bổ sung kiến thức từ Internet hoặc nguồn ngoài
          nếu learningMaterial đã cung cấp đủ thông tin.
        - Nếu học sinh trả lời đúng kiến thức dựa trên learningMaterial
          nhưng diễn đạt khác answerKey, vẫn phải công nhận.

        READING_PASSAGE:
        - Nếu câu hỏi dựa trên bài đọc,
          phải sử dụng learningMaterial để đánh giá câu trả lời.
        - Chỉ yêu cầu thông tin có trong bài đọc
          hoặc suy luận hợp lý trực tiếp từ bài đọc.
        - Không yêu cầu học sinh biết thêm thông tin ngoài bài đọc.
        - Không đánh sai chỉ vì câu trả lời không giống nguyên văn answerKey.

        3. LEARNING MATERIAL
        - learningMaterial chỉ là nguồn thông tin khi câu hỏi yêu cầu.
        - Không coi việc learningMaterial không tồn tại là lỗi
          đối với câu hỏi SELF_CONTAINED.
        - Nếu câu hỏi là LESSON_CONTENT hoặc READING_PASSAGE
          nhưng learningMaterial bị thiếu,
          không được tự bịa nội dung còn thiếu.
        - Không dùng kiến thức bên ngoài để sửa một câu hỏi
          hoặc answerKey bị thiếu dữ kiện.

        4. TÍNH KHOA HỌC
        - Chấm theo kiến thức khoa học phù hợp với độ tuổi học sinh.
        - Không chấp nhận thông tin sai về cơ thể,
          tuổi dậy thì, sức khỏe sinh sản, ranh giới cá nhân,
          sự đồng thuận, an toàn hoặc quyền riêng tư.
        - Nếu học sinh diễn đạt chưa chính xác nhưng thể hiện
          một phần kiến thức đúng, cho điểm tương ứng với phần đúng.
        - Không yêu cầu câu trả lời phải dùng thuật ngữ y khoa
          nếu học sinh đã thể hiện đúng ý nghĩa bằng ngôn ngữ phù hợp lứa tuổi.

        5. AN TOÀN VÀ RIÊNG TƯ
        - Không yêu cầu học sinh tiết lộ trải nghiệm cá nhân.
        - Không yêu cầu học sinh mô tả đời sống tình dục cá nhân.
        - Không yêu cầu ảnh, video hoặc thông tin riêng tư.
        - Không suy đoán về trải nghiệm, hành vi hoặc hoàn cảnh cá nhân
          của học sinh từ studentAnswer.
        - Không đánh giá học sinh dựa trên đời sống hoặc hành vi cá nhân.
        - Chỉ đánh giá kiến thức thể hiện trong câu trả lời.

        6. TÌNH HUỐNG AN TOÀN
        Nếu câu hỏi yêu cầu lựa chọn hành động an toàn:
        - Ưu tiên câu trả lời thể hiện việc bảo vệ bản thân,
          ranh giới cá nhân và quyền từ chối.
        - Nếu phù hợp, công nhận việc tìm người lớn đáng tin cậy
          hoặc nguồn hỗ trợ phù hợp.
        - Không yêu cầu học sinh phải kể trải nghiệm thật của bản thân.
        - Nếu câu trả lời khác answerKey nhưng vẫn thể hiện
          hành động an toàn và phù hợp, cho điểm tương ứng.

        7. CHẤM ĐIỂM

        Nếu studentAnswer đúng:
        - cho điểm đầy đủ.

        Nếu studentAnswer đúng một phần:
        - cho điểm tương ứng với kiến thức hoặc kỹ năng đúng.

        Nếu studentAnswer sai:
        - cho điểm thấp hoặc 0 tùy mức độ;
        - feedback chỉ ra kiến thức cần sửa;
        - không dùng ngôn ngữ làm học sinh xấu hổ hoặc đổ lỗi.

        Nếu studentAnswer bỏ trống:
        - cho 0 điểm;
        - feedback ngắn gọn và khuyến khích học sinh xem lại kiến thức.

        8. KHÔNG SUY DIỄN QUÁ MỨC
        - Không đoán ý định của học sinh.
        - Không tự bổ sung câu trả lời còn thiếu.
        - Không coi câu trả lời mơ hồ là đúng nếu không có đủ căn cứ.
        - Không đánh sai chỉ vì học sinh dùng cách diễn đạt khác answerKey
          nhưng vẫn thể hiện đúng kiến thức.

        9. FEEDBACK
        Feedback phải:
        - ngắn;
        - rõ;
        - mang tính giáo dục;
        - phù hợp độ tuổi;
        - không phán xét;
        - không làm học sinh xấu hổ;
        - chỉ ra kiến thức hoặc lý do cần sửa khi câu trả lời chưa đúng.

        Không đưa vào feedback:
        - nội dung tình dục không cần thiết;
        - nhận xét về đời sống cá nhân;
        - suy đoán về trải nghiệm của học sinh;
        - yêu cầu học sinh cung cấp thông tin riêng tư.

        10. OUTPUT

        Chỉ trả về JSON hợp lệ, không markdown.

        {
          "score": 0,
          "feedback": "..."
        }

        score phải nằm trong khoảng:
        0 <= score <= ${assignment.totalScore}
    """.trimIndent()
    }

    private fun parseGradingResponse(
        responseText: String
    ): GradingResult {

        val root =
            parseGeminiJsonResponse(
                responseText
            ).jsonObject

        val score =
            root["score"]
                ?.jsonPrimitive
                ?.doubleOrNull
                ?: throw IllegalStateException(
                    "Grading response missing score"
                )

        val feedback =
            root["feedback"]
                ?.jsonPrimitive
                ?.contentOrNull
                ?: ""

        if (!score.isFinite()) {
            throw IllegalStateException(
                "Grading score is not finite"
            )
        }

        val questions =
            root["questions"]
                ?.jsonArray
                ?.map { element ->

                    val question =
                        element.jsonObject

                    val id =
                        question["id"]
                            ?.jsonPrimitive
                            ?.intOrNull
                            ?: throw IllegalStateException(
                                "Question grading missing id"
                            )

                    val questionScore =
                        question["score"]
                            ?.jsonPrimitive
                            ?.doubleOrNull
                            ?: throw IllegalStateException(
                                "Question grading missing score for id=$id"
                            )

                    val questionFeedback =
                        question["feedback"]
                            ?.jsonPrimitive
                            ?.contentOrNull
                            ?: ""

                    if (!questionScore.isFinite()) {
                        throw IllegalStateException(
                            "Question score is not finite for id=$id"
                        )
                    }

                    QuestionGradingResult(
                        id = id,
                        score = questionScore,
                        feedback = questionFeedback
                    )
                }
                ?: emptyList()

        return GradingResult(
            score = score,
            feedback = feedback,
            questions = questions
        )
    }


    private fun buildLearningStepContext(
        learningStepTitle: String?,
        learningStepSkill: String?,
        learningStepDescription: String?
    ): String {

        val hasLearningStep =
            !learningStepTitle.isNullOrBlank() ||
                    !learningStepSkill.isNullOrBlank() ||
                    !learningStepDescription.isNullOrBlank()

        if (!hasLearningStep) {
            return """
        === LEARNING PATH ===
        Không có LearningStep cụ thể.
        Có thể tạo bài theo Topic và chương trình lớp học.
        """.trimIndent()
        }

        return """
    === LEARNING PATH / CURRENT LEARNING STEP ===

    Đây là bước học HIỆN TẠI của học sinh.

    Step title:
    ${learningStepTitle ?: "(không có)"}

    Skill bắt buộc:
    ${learningStepSkill ?: "(không có)"}

    Mô tả:
    ${learningStepDescription ?: "(không có)"}

    === QUY TẮC LEARNING STEP — BẮT BUỘC ===

    1. LearningStep hiện tại là mục tiêu curriculum chính của bài này.

    2. Tất cả câu hỏi phải chủ yếu đánh giá SKILL của LearningStep
       hiện tại.

    3. Không được tự ý chuyển sang kỹ năng của LearningStep tiếp theo.

    4. Không được dùng kỹ năng nâng cao hơn chỉ để làm câu hỏi khó hơn.

    5. Nếu Topic rộng hơn LearningStep thì LearningStep được ưu tiên.

    6. Các câu có thể khác nhau về:
       - ngữ cảnh;
       - dữ kiện;
       - cách hỏi;
       - mức độ reasoning;
       - tình huống áp dụng;

       nhưng tất cả vẫn phải nằm trong cùng Skill hiện tại.

    7. Cognitive progression KHÔNG có nghĩa là chuyển sang skill tiếp theo.

       Ví dụ:
       Nếu Skill hiện tại là:
       "Nhận biết tử số và mẫu số"

       thì có thể tăng độ sâu bằng cách:
       - nhận diện;
       - giải thích;
       - áp dụng vào hình ảnh/ngữ cảnh;
       - phát hiện lỗi;

       nhưng KHÔNG được chuyển sang:
       - so sánh phân số;
       - cộng phân số;
       - trừ phân số.

    8. Không tạo câu hỏi chỉ thuộc Topic nhưng không phục vụ
       LearningStep hiện tại.

    9. Không coi việc đổi số hoặc đổi tên nhân vật là lý do
       để sử dụng một skill khác.

    10. Nếu không chắc một câu có thuộc LearningStep hiện tại hay không,
        hãy chọn cách hỏi đơn giản hơn nhưng chắc chắn nằm trong
        Skill hiện tại.
    """.trimIndent()
    }

    private fun isNumericAnswer(value: String): Boolean {
        val normalized = value.trim().replace(',', '.')
        val simpleNumber = normalized.toDoubleOrNull()
        if (simpleNumber != null) return simpleNumber.isFinite()

        val fraction = Regex("""^([+-]?\d+(?:\.\d+)?)\s*/\s*([+-]?\d+(?:\.\d+)?)$""")
            .matchEntire(normalized) ?: return false
        val numerator = fraction.groupValues[1].toDoubleOrNull() ?: return false
        val denominator = fraction.groupValues[2].toDoubleOrNull() ?: return false
        return numerator.isFinite() && denominator.isFinite() && denominator != 0.0
    }
}
