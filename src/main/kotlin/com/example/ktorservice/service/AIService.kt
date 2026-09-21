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
            QuestionSourceType.SELF_CONTAINED
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

        val assignment = parseResponse(response)

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

        val questions = when {
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
            appendLine("- Q1: nền tảng, hiểu kiến thức.")
            appendLine("- Q2: vận dụng vào dữ kiện/ngữ cảnh khác.")
            appendLine("- Q3: suy luận/phân tích/giải quyết vấn đề khi phù hợp.")
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
        - dùng cùng cách giải cho cả 3 câu;
        - hỏi cùng kiến thức ba lần;
        - làm Q2/Q3 dài hơn nhưng không sâu hơn.

        === CẤU TRÚC NHẬN THỨC ===
        Q1: nền tảng, hiểu và sử dụng kiến thức cốt lõi.
        Q2: vận dụng, tình huống thực tế, so sánh hoặc giải thích.
        Q3: suy luận, phân tích, error analysis, cause-effect,
        decision hoặc problem solving.

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
        Chính xác 3 câu, tổng 10 điểm.

        === TỰ KIỂM TRA ===
        - Đúng 3 câu.
        - ID 1,2,3.
        - Có learningObjective.
        - Q1/Q2/Q3 khác nhau về tư duy.
        - Có đúng 3 answerKey.
        - ID answerKey khớp.
        - Tổng điểm = 10.
        - GradingGuide khớp.
        - Không DRAWING/MIXED.
        - AnswerType và GradingMethod tương thích.
        - Không lỗi chính tả/ngữ nghĩa.
        - Không kiến thức vượt lớp.
        - JSON hợp lệ.
        - Không markdown ngoài JSON.

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
              "sourceType": "SELF_CONTAINED"
            },
            {
              "id": 2,
              "question": "...",
              "learningObjective": "...",
              "points": 3,
              "answerType": "HANDWRITING",
              "gradingMethod": "OCR_AI",
              "sourceType": "SELF_CONTAINED"
            },
            {
              "id": 3,
              "question": "...",
              "learningObjective": "...",
              "points": 4,
              "answerType": "HANDWRITING",
              "gradingMethod": "OCR_AI",
              "sourceType": "SELF_CONTAINED"
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
              "sourceType": "SELF_CONTAINED"
            },
            {
              "id": 2,
              "question": "...",
              "learningObjective": "...",
              "points": 3,
              "answerType": "TEXT",
              "gradingMethod": "AI_TEXT",
              "sourceType": "SELF_CONTAINED"
            },
            {
              "id": 3,
              "question": "...",
              "learningObjective": "...",
              "points": 4,
              "answerType": "TEXT",
              "gradingMethod": "AI_TEXT",
              "sourceType": "SELF_CONTAINED"
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
            connection.readTimeout = 180_000
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
                    sourceType = sourceType
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

        return GeneratedAssignment(
            title = title,
            learningMaterial = learningMaterial,
            questions = questions,
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

        return json.parseToJsonElement(
            cleanJsonText(raw)
        )
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

        if (assignment.title.isBlank()) {
            errors += "Assignment title is empty"
        }

        if (assignment.title.trim().length < 5) {
            errors += "Assignment title is too short"
        }

        if (assignment.questions.size != 3) {
            errors += "Assignment must have exactly 3 questions"
        }

        val expectedIds = listOf(1, 2, 3)

        if (assignment.questions.map { it.id } != expectedIds) {
            errors += "Question IDs must be exactly 1,2,3"
        }

        if (assignment.answerKey.size != 3) {
            errors += "Assignment must have exactly 3 answers"
        }

        if (assignment.answerKey.map { it.id }.sorted() != expectedIds) {
            errors += "Answer IDs must be exactly 1,2,3"
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

        Q1 phải thiên về hiểu/nền tảng.

        Q2 phải thiên về vận dụng.

        Q3 phải thiên về reasoning/phân tích khi môn học cho phép.

        Nếu cả 3 câu thực chất chỉ dùng cùng một thao tác:
        FAIL.

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
        - Q2/Q3 chỉ dài hơn nhưng không sâu hơn.

        PASS nếu:

        - cùng kiến thức nhưng khác cách vận dụng;
        - khác reasoning;
        - khác context;
        - khác kỹ năng;
        - hoặc có tiến triển nhận thức rõ ràng.

        ------------------------------------------------------------

        8. ANSWER ALIGNMENT

        Với từng Q:

        Q1 -> Answer 1
        Q2 -> Answer 2
        Q3 -> Answer 3

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

        Không được tạo ba câu chỉ để đủ số lượng.

        Ba câu phải tạo thành một progression hợp lý:

        Q1 -> hiểu nền tảng
        Q2 -> vận dụng
        Q3 -> suy luận/phân tích

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

    2. Cả 3 câu hỏi phải chủ yếu đánh giá SKILL của LearningStep
       hiện tại.

    3. Không được tự ý chuyển sang kỹ năng của LearningStep tiếp theo.

    4. Không được dùng kỹ năng nâng cao hơn chỉ để làm câu hỏi khó hơn.

    5. Nếu Topic rộng hơn LearningStep thì LearningStep được ưu tiên.

    6. Q1, Q2 và Q3 có thể khác nhau về:
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
}
