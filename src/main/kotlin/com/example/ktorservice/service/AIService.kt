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

    // ============================================================
    // PUBLIC API / MODELS
    // ============================================================

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
        val gradingMethod: GradingMethod
    )

    @Serializable
    data class GeneratedAnswer(
        val id: Int,
        val answer: String
    )

    @Serializable
    data class GeneratedAssignment(
        val title: String,
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
    // V2 INTERNAL BLUEPRINT
    //
    // Không đưa các field này vào GeneratedQuestion để giữ API cũ.
    // Blueprint chỉ tồn tại trong quá trình tạo prompt/review.
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
        get() = System.getenv("GEMINI_MODEL") ?: "gemini-3.5-flash-lite"

    private val sexEducationModel: String
        get() = System.getenv("GEMINI_SEX_EDUCATION_MODEL") ?: model

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

        val sexEducationAliases = setOf(
            "giao duc gioi tinh",
            "giao duc suc khoe sinh san",
            "suc khoe sinh san",
            "gioi tinh",
            "sex education",
            "sexual health",
            "reproductive health",
            "sexual education"
        )

        return if (normalized in sexEducationAliases) {
            SubjectType.SEX_EDUCATION
        } else {
            SubjectType.NORMAL
        }
    }

    private fun isSexEducation(subject: String): Boolean {
        return getSubjectType(subject) == SubjectType.SEX_EDUCATION
    }

    // ============================================================
    // MAIN GENERATION API
    // ============================================================

    suspend fun generateAssignment(
        grade: Int,
        subject: String,
        topic: String?,
        difficulty: Difficulty,
        previousAssignments: List<String> = emptyList(),
        qualityFeedback: String? = null
    ): GeneratedAssignment {

        println(
            "[AIService] generateAssignment " +
                    "grade=$grade subject=$subject topic=$topic difficulty=$difficulty " +
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
                qualityFeedback = qualityFeedback
            )
        } else {
            buildPrompt(
                grade = grade,
                subject = subject,
                topic = topic,
                difficulty = difficulty,
                previousAssignments = previousAssignments,
                qualityFeedback = qualityFeedback
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
        difficulty: Difficulty
    ): GenerationBlueprint {

        val normalizedSubject = subject.trim().lowercase()

        val topicText = topic
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: "nội dung phù hợp chương trình lớp $grade"

        val isMath = normalizedSubject.contains("toán") ||
                normalizedSubject.contains("math")

        val isLanguage = normalizedSubject.contains("ngữ văn") ||
                normalizedSubject.contains("văn") ||
                normalizedSubject.contains("literature") ||
                normalizedSubject.contains("tiếng việt")

        val isEnglish = normalizedSubject.contains("anh") ||
                normalizedSubject.contains("english")

        val questions = when {
            isMath -> createMathBlueprint(difficulty)

            isLanguage -> listOf(
                BlueprintQuestion(
                    1,
                    CognitiveLevel.UNDERSTAND,
                    QuestionStrategy.EVIDENCE,
                    "Xác định và giải thích nội dung/ý nghĩa chính dựa trực tiếp trên kiến thức hoặc ngữ liệu.",
                    "Không chỉ yêu cầu chép lại một câu hoặc định nghĩa."
                ),
                BlueprintQuestion(
                    2,
                    CognitiveLevel.APPLY,
                    QuestionStrategy.COMPARISON,
                    "Vận dụng kiến thức để phân tích, so sánh hoặc giải thích một trường hợp cụ thể.",
                    "Không lặp lại đúng thao tác của câu 1."
                ),
                BlueprintQuestion(
                    3,
                    CognitiveLevel.REASON,
                    QuestionStrategy.EXPLANATION,
                    "Phân tích, lập luận hoặc đưa ra nhận xét có căn cứ.",
                    "Không biến thành câu hỏi nhớ lại đơn giản."
                )
            )

            isEnglish -> listOf(
                BlueprintQuestion(
                    1,
                    CognitiveLevel.UNDERSTAND,
                    QuestionStrategy.DIRECT,
                    "Kiểm tra kiến thức/ngôn ngữ cốt lõi của chủ đề.",
                    "Không dùng cấu trúc ngoài trình độ."
                ),
                BlueprintQuestion(
                    2,
                    CognitiveLevel.APPLY,
                    QuestionStrategy.REAL_WORLD,
                    "Đưa kiến thức vào một tình huống giao tiếp hoặc ngữ cảnh cụ thể.",
                    "Không chỉ thay vài từ của câu 1."
                ),
                BlueprintQuestion(
                    3,
                    CognitiveLevel.REASON,
                    QuestionStrategy.ERROR_ANALYSIS,
                    "Phát hiện, sửa hoặc giải thích lỗi trong một tình huống phù hợp.",
                    "Không sử dụng lỗi giả tạo hoặc không tự nhiên."
                )
            )

            else -> listOf(
                BlueprintQuestion(
                    1,
                    CognitiveLevel.UNDERSTAND,
                    QuestionStrategy.DIRECT,
                    "Kiểm tra kiến thức nền tảng và khả năng giải thích bằng lời của học sinh.",
                    "Không chỉ yêu cầu học thuộc máy móc."
                ),
                BlueprintQuestion(
                    2,
                    CognitiveLevel.APPLY,
                    QuestionStrategy.REAL_WORLD,
                    "Vận dụng kiến thức vào một tình huống cụ thể, gần với thực tế hoặc bài học.",
                    "Không lặp lại dữ kiện hoặc cách giải của câu 1."
                ),
                BlueprintQuestion(
                    3,
                    CognitiveLevel.REASON,
                    QuestionStrategy.CAUSE_EFFECT,
                    "Phân tích nguyên nhân, hậu quả, bằng chứng hoặc lựa chọn phù hợp.",
                    "Không hỏi lại cùng một kiến thức dưới cách diễn đạt khác."
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
                    "Kiểm tra kỹ năng/toán kiến thức nền tảng.",
                    "Không chỉ đổi số từ một bài có sẵn."
                ),
                BlueprintQuestion(
                    2,
                    CognitiveLevel.APPLY,
                    QuestionStrategy.WORD_PROBLEM,
                    "Áp dụng kiến thức vào bài toán có ngữ cảnh rõ ràng.",
                    "Không dùng đúng mô hình câu 1."
                ),
                BlueprintQuestion(
                    3,
                    CognitiveLevel.REASON,
                    QuestionStrategy.ERROR_ANALYSIS,
                    "Phát hiện và sửa lỗi hoặc giải thích vì sao một cách làm đúng/sai.",
                    "Không biến thành phép tính lặp lại."
                )
            )

            Difficulty.MEDIUM -> listOf(
                BlueprintQuestion(
                    1,
                    CognitiveLevel.UNDERSTAND,
                    QuestionStrategy.EXPLANATION,
                    "Kiểm tra hiểu bản chất và khả năng giải thích.",
                    "Không chỉ yêu cầu một đáp số."
                ),
                BlueprintQuestion(
                    2,
                    CognitiveLevel.APPLY,
                    QuestionStrategy.WORD_PROBLEM,
                    "Vận dụng vào tình huống thực tế với dữ kiện đầy đủ.",
                    "Không chỉ thay số."
                ),
                BlueprintQuestion(
                    3,
                    CognitiveLevel.REASON,
                    QuestionStrategy.MULTI_STEP,
                    "Giải quyết bài toán nhiều bước hoặc cần kết hợp ít nhất hai ý.",
                    "Không yêu cầu kiến thức vượt chương trình."
                )
            )

            Difficulty.HARD -> listOf(
                BlueprintQuestion(
                    1,
                    CognitiveLevel.APPLY,
                    QuestionStrategy.WORD_PROBLEM,
                    "Vận dụng chắc chắn kiến thức trọng tâm.",
                    "Không dùng kiến thức ngoài chương trình."
                ),
                BlueprintQuestion(
                    2,
                    CognitiveLevel.REASON,
                    QuestionStrategy.MULTI_STEP,
                    "Kết hợp nhiều bước hoặc nhiều đại lượng để giải quyết vấn đề.",
                    "Không chỉ tăng số lượng phép tính."
                ),
                BlueprintQuestion(
                    3,
                    CognitiveLevel.REASON,
                    QuestionStrategy.ERROR_ANALYSIS,
                    "Phân tích một cách giải, tìm lỗi hoặc lựa chọn chiến lược giải hợp lý.",
                    "Không đánh đố hoặc dùng kiến thức chưa học."
                )
            )
        }
    }

    private fun blueprintAsPrompt(
        blueprint: GenerationBlueprint
    ): String {

        return buildString {
            appendLine("=== GENERATION BLUEPRINT V2 ===")
            appendLine("Blueprint này là ràng buộc nội bộ. Không đưa blueprint vào JSON output.")
            appendLine("Mục tiêu: tạo 3 câu hỏi có vai trò khác nhau, không phải 3 biến thể của cùng một bài.")
            appendLine()

            blueprint.questions.forEach { q ->
                appendLine(
                    """
                    Q${q.id}:
                    - Cognitive level: ${q.cognitiveLevel}
                    - Strategy: ${q.strategy}
                    - Purpose: ${q.purpose}
                    - Avoid: ${q.avoid}
                    """.trimIndent()
                )
                appendLine()
            }

            appendLine("YÊU CẦU TIẾN TRIỂN NHẬN THỨC:")
            appendLine("- Q1 phải tạo nền tảng để kiểm tra hiểu biết/kỹ năng cốt lõi.")
            appendLine("- Q2 phải vận dụng kiến thức vào dữ kiện hoặc ngữ cảnh khác Q1.")
            appendLine("- Q3 phải yêu cầu suy luận/phân tích/đánh giá lỗi/giải quyết vấn đề khi phù hợp.")
            appendLine("- Không được tăng độ khó chỉ bằng cách thêm số lớn hơn.")
            appendLine("- Không được tăng độ khó chỉ bằng cách đổi tên nhân vật.")
            appendLine("- Không được dùng cùng một cấu trúc giải cho cả 3 câu nếu không có lý do sư phạm rõ ràng.")
            appendLine()
            appendLine("YÊU CẦU DIVERSITY:")
            appendLine("- Mỗi câu phải có một mục tiêu nhận thức riêng.")
            appendLine("- Mỗi câu nên có strategy khác nhau.")
            appendLine("- Không lặp cùng context, cùng dữ kiện hoặc cùng answer pattern.")
            appendLine("- Nếu cùng một kiến thức, phải thay đổi cách vận dụng hoặc cách suy luận.")
            appendLine("- Không tạo ba câu chỉ khác con số.")
        }
    }

    // ============================================================
    // NORMAL PROMPT V2
    // ============================================================

    private fun buildPrompt(
        grade: Int,
        subject: String,
        topic: String?,
        difficulty: Difficulty,
        previousAssignments: List<String> = emptyList(),
        qualityFeedback: String? = null
    ): String {

        val topicText = topic
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: "Tự chọn nội dung phù hợp chương trình lớp $grade của môn $subject."

        val blueprint = createBlueprint(
            grade = grade,
            subject = subject,
            topic = topicText,
            difficulty = difficulty
        )

        val difficultyText = when (difficulty) {
            Difficulty.EASY ->
                "Cơ bản đến vừa phải. Ưu tiên hiểu kiến thức, nhận biết, giải thích và vận dụng trực tiếp."

            Difficulty.MEDIUM ->
                "Trung bình. Phải có vận dụng, tình huống, nhiều bước hoặc phân tích khi phù hợp."

            Difficulty.HARD ->
                "Khá khó. Ưu tiên vận dụng cao, phân tích, suy luận và giải quyết vấn đề nhưng tuyệt đối không vượt chương trình."
        }

        val previousText = buildPreviousAssignmentsContext(previousAssignments)

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

        return """
            Bạn là giáo viên Việt Nam có kinh nghiệm thiết kế bài tập theo chương trình phổ thông.

            Hãy tạo MỘT bài tập cho:
            - Lớp: $grade
            - Môn: $subject
            - Chủ đề: $topicText
            - Độ khó: $difficulty
            - Mô tả độ khó: $difficultyText

            ${blueprintAsPrompt(blueprint)}

            === NGUYÊN TẮC CHƯƠNG TRÌNH ===
            1. Chỉ sử dụng kiến thức học sinh lớp $grade có thể đã được học.
            2. Không tự ý đưa công thức, định lý, thuật ngữ hoặc phương pháp của lớp cao hơn.
            3. Nội dung phải thực sự thuộc chủ đề.
            4. Dữ kiện phải đủ để giải.
            5. Câu hỏi phải có nghĩa tự nhiên bằng tiếng Việt.
            6. Không tạo câu hỏi mơ hồ hoặc có nhiều đáp án đúng nếu không nói rõ.
            7. Đáp án phải thực sự trả lời đúng câu hỏi.
            8. Learning objective phải thể hiện rõ học sinh cần biết/làm được gì.

            === QUY TẮC DIVERSITY ===
            Ba câu hỏi phải khác nhau về giá trị giáo dục.

            KHÔNG được:
            - sao chép câu cũ;
            - chỉ thay vài từ;
            - chỉ đổi số;
            - chỉ đổi tên nhân vật;
            - giữ nguyên context rồi đổi đáp án;
            - dùng cùng một quy trình giải cho cả ba câu;
            - hỏi cùng một kiến thức ba lần;
            - tạo Q2/Q3 chỉ dài hơn Q1 nhưng không sâu hơn.

            Nếu có thể dùng lại cùng kiến thức thì phải thay đổi:
            - ngữ cảnh;
            - loại dữ kiện;
            - cách suy luận;
            - mục tiêu kỹ năng;
            - hoặc cách kiểm tra hiểu biết.

            === CẤU TRÚC NHẬN THỨC ===
            Q1:
            - nền tảng;
            - kiểm tra hiểu và sử dụng kiến thức cốt lõi.

            Q2:
            - vận dụng;
            - ưu tiên word problem, tình huống thực tế, so sánh hoặc giải thích.

            Q3:
            - suy luận/phân tích;
            - ưu tiên multi-step, error analysis, cause-effect, decision hoặc problem solving.

            Nếu môn học không phù hợp với một strategy cụ thể, hãy chọn strategy tương đương nhưng vẫn phải đảm bảo Q1/Q2/Q3 khác nhau về tư duy.

            === CHẤT LƯỢNG NGÔN NGỮ ===
            Không được tạo:
            - từ vô nghĩa;
            - câu dịch máy khó hiểu;
            - cụm từ sai ngữ nghĩa;
            - câu hỏi thiếu chủ ngữ/dữ kiện;
            - từ bị ghép sai;
            - placeholder;
            - "undefined";
            - "null";
            - "lorem ipsum";
            - chuỗi ký tự bất thường.

            Tự đọc lại từng câu như một giáo viên trước khi trả JSON.

            === ANSWER TYPE ===
            Chỉ sử dụng:
            - TEXT
            - HANDWRITING
            - SPEECH_TO_TEXT

            Không sử dụng DRAWING hoặc MIXED vì ControlReceiver hiện chưa hỗ trợ.

            Mapping bắt buộc:
            - TEXT -> EXACT hoặc AI_TEXT
            - HANDWRITING -> OCR_AI
            - SPEECH_TO_TEXT -> EXACT hoặc AI_TEXT

            SPEECH_TO_TEXT chỉ dùng khi việc trả lời bằng lời thực sự phù hợp.

            === MÔN HỌC ===
            Nếu là Toán:
            - dữ kiện phải đủ;
            - không có đáp án mâu thuẫn;
            - đáp số và lời giải phải khớp;
            - nếu yêu cầu giải thích thì answerKey phải chứa tiêu chí giải thích.

            Nếu là Ngữ văn:
            - đánh giá ý nghĩa, lập luận, bằng chứng hoặc khả năng phân tích;
            - chấp nhận cách diễn đạt khác nếu nội dung đúng;
            - không biến thành câu hỏi học thuộc đơn thuần.

            Nếu là Tiếng Anh:
            - câu hỏi phải phù hợp trình độ;
            - nếu có nhiều cách trả lời đúng thì gradingGuide phải nói rõ.

            Các môn khác:
            - phải có tiêu chí chấm rõ ràng;
            - không dùng thuật ngữ không phù hợp lớp học.

            === ĐIỂM ===
            Chính xác 3 câu, tổng 10 điểm.
            Có thể dùng:
            - 3 + 3 + 4
            - 2 + 3 + 5
            hoặc phân bố hợp lý khác.
            Câu yêu cầu tư duy cao hơn có thể có nhiều điểm hơn.

            === TỰ KIỂM TRA TRƯỚC KHI TRẢ ===
            - Đúng 3 câu.
            - ID là 1,2,3.
            - Có learningObjective cho từng câu.
            - LearningObjective không trùng nhau.
            - Q1/Q2/Q3 khác nhau về tư duy.
            - Q2 thực sự vận dụng.
            - Q3 thực sự sâu hơn khi môn học cho phép.
            - Tổng điểm = 10.
            - Có đúng 3 answerKey.
            - ID answerKey khớp question.
            - Mỗi câu có đáp án.
            - GradingGuide khớp câu hỏi.
            - Không DRAWING/MIXED.
            - AnswerType và GradingMethod tương thích.
            - Không trùng nội dung.
            - Không sai chính tả/ngữ nghĩa.
            - Không kiến thức vượt lớp.
            - JSON hợp lệ.
            - Không có markdown hoặc text ngoài JSON.

            $previousText

            $qualityText

            === JSON OUTPUT ===
            Chỉ trả về JSON hợp lệ:

            {
              "title": "Tên bài",
              "questions": [
                {
                  "id": 1,
                  "question": "...",
                  "learningObjective": "...",
                  "points": 3,
                  "answerType": "TEXT",
                  "gradingMethod": "AI_TEXT"
                },
                {
                  "id": 2,
                  "question": "...",
                  "learningObjective": "...",
                  "points": 3,
                  "answerType": "HANDWRITING",
                  "gradingMethod": "OCR_AI"
                },
                {
                  "id": 3,
                  "question": "...",
                  "learningObjective": "...",
                  "points": 4,
                  "answerType": "HANDWRITING",
                  "gradingMethod": "OCR_AI"
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
    // PREVIOUS ASSIGNMENTS / SEMANTIC DIVERSITY
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
                        "Phải tránh trùng về ý tưởng, context, strategy và cách giải."
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
                1. Câu này có đang kiểm tra đúng ý tưởng của bài trước không?
                2. Nếu thay tên và số liệu, nó có trở thành cùng một câu không?
                3. Cách giải có giống bài trước không?
                4. Context có bị lặp không?
                5. Có thể kiểm tra cùng kiến thức bằng một tình huống khác sâu hơn không?

                Nếu câu quá giống một bài trước:
                - bỏ câu đó;
                - chọn context mới;
                - thay đổi reasoning;
                - thay đổi question strategy;
                - hoặc thay đổi kỹ năng được kiểm tra.

                Không được coi "đổi số" là sự đa dạng thực sự.
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
                "[AIService] Gemini model=${selectedModel} " +
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

                    println(
                        "[AIService] Retryable Gemini error " +
                                "attempt=$attempt model=$selectedModel " +
                                "message=${e.message}"
                    )

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

                    println(
                        "[AIService] Gemini timeout " +
                                "attempt=$attempt model=$selectedModel"
                    )

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

                    println(
                        "[AIService] Gemini connection error " +
                                "attempt=$attempt model=$selectedModel"
                    )

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
                "[AIService] Primary model exhausted, " +
                        "switching if fallback exists."
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

        val connection = url.openConnection() as HttpURLConnection

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

            val body = requestBody.toString()

            connection.outputStream.use { output ->
                output.write(body.toByteArray(Charsets.UTF_8))
            }

            val responseCode = connection.responseCode

            val responseText = if (responseCode in 200..299) {
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
    // SEX EDUCATION
    // ============================================================

    private fun buildSexEducationSystemInstruction(): String {
        return """
            Bạn là hệ thống AI hỗ trợ giáo dục sức khỏe giới tính và sức khỏe sinh sản
            cho học sinh.

            Nội dung phải:
            - khoa học;
            - chính xác;
            - giáo dục;
            - phù hợp độ tuổi;
            - không kích thích tình dục;
            - không mang tính khiêu dâm;
            - không mô tả tình dục không cần thiết;
            - không sexual roleplay;
            - không yêu cầu trẻ em mô tả trải nghiệm riêng tư;
            - không yêu cầu chia sẻ ảnh/video riêng tư;
            - không hướng dẫn hành vi tình dục;
            - không bình thường hóa hành vi grooming hoặc xâm hại;
            - không làm học sinh xấu hổ;
            - ưu tiên an toàn, ranh giới cá nhân, quyền từ chối và tìm người lớn đáng tin cậy.

            Khi nội dung vượt quá độ tuổi:
            - chuyển về kiến thức khoa học;
            - an toàn;
            - quyền riêng tư;
            - sức khỏe;
            - hoặc tìm người lớn/chuyên gia y tế đáng tin cậy.

            Không suy đoán trải nghiệm cá nhân của học sinh.
        """.trimIndent()
    }

    private fun buildSexEducationSafetySettings(): JsonArray {
        return buildJsonArray {
            add(
                buildJsonObject {
                    put("category", "HARM_CATEGORY_SEXUALLY_EXPLICIT")
                    put("threshold", "BLOCK_LOW_AND_ABOVE")
                }
            )

            add(
                buildJsonObject {
                    put("category", "HARM_CATEGORY_DANGEROUS_CONTENT")
                    put("threshold", "BLOCK_LOW_AND_ABOVE")
                }
            )
        }
    }

    private fun getSexEducationScope(grade: Int): String {
        return when (grade) {
            in 1..3 -> """
                - Nhận biết cơ thể và quyền riêng tư cơ bản.
                - Khu vực riêng tư của cơ thể.
                - Không ai được chạm vào cơ thể mình nếu mình không đồng ý,
                  ngoại trừ tình huống chăm sóc y tế phù hợp.
                - Biết nói không, tránh xa và báo người lớn đáng tin cậy.
                - Vệ sinh cơ thể.
                - Không yêu cầu kiến thức về hoạt động tình dục.
            """.trimIndent()

            in 4..5 -> """
                - Những thay đổi cơ thể cơ bản khi lớn lên.
                - Vệ sinh cá nhân và tuổi dậy thì ở mức phù hợp.
                - Kinh nguyệt ở mức giáo dục cơ bản.
                - Cảm xúc và sự tôn trọng cơ thể.
                - Ranh giới cá nhân.
                - Quyền từ chối.
                - An toàn trên Internet và quyền riêng tư.
            """.trimIndent()

            in 6..7 -> """
                - Tuổi dậy thì.
                - Thay đổi thể chất, tâm lý và cảm xúc.
                - Vệ sinh và chăm sóc sức khỏe.
                - Kinh nguyệt.
                - Ranh giới cá nhân và sự đồng thuận.
                - Tôn trọng cơ thể.
                - Quan hệ lành mạnh phù hợp tuổi.
                - An toàn hình ảnh và Internet.
                - Nhận biết hành vi không phù hợp và tìm trợ giúp.
            """.trimIndent()

            in 8..9 -> """
                - Sức khỏe sinh sản.
                - Tuổi dậy thì và hormone.
                - Sức khỏe sinh sản nam/nữ.
                - Kinh nguyệt.
                - Đồng thuận và ranh giới.
                - Quan hệ lành mạnh và trách nhiệm.
                - Phòng ngừa STI ở mức khoa học.
                - Phòng ngừa mang thai ngoài ý muốn ở mức giáo dục.
                - An toàn Internet và nguy cơ xâm hại.
                - Tìm sự hỗ trợ từ người lớn/chuyên gia y tế.
            """.trimIndent()

            else -> """
                - Sức khỏe sinh sản.
                - Kiến thức khoa học về sinh sản.
                - Sức khỏe tình dục có trách nhiệm.
                - Đồng thuận.
                - Ranh giới cá nhân.
                - Quan hệ lành mạnh.
                - Trách nhiệm.
                - Biện pháp tránh thai ở mức giáo dục khoa học.
                - Phòng ngừa STI.
                - Sức khỏe thể chất và tinh thần.
                - Quyền riêng tư và an toàn Internet.
                - Phòng ngừa xâm hại.
                - Tìm hỗ trợ y tế/chuyên gia khi cần.
            """.trimIndent()
        }
    }

    private fun getSexEducationDifficulty(
        difficulty: Difficulty
    ): String {
        return when (difficulty) {
            Difficulty.EASY -> """
                Kiến thức cơ bản, nhận biết và hiểu.
                Không yêu cầu suy luận phức tạp.
                Không tạo áp lực tâm lý.
            """.trimIndent()

            Difficulty.MEDIUM -> """
                Hiểu và vận dụng.
                Có thể sử dụng tình huống giáo dục.
                Học sinh giải thích lý do hoặc lựa chọn cách xử lý an toàn.
            """.trimIndent()

            Difficulty.HARD -> """
                Vận dụng và phân tích tình huống.
                Có thể yêu cầu nhận diện rủi ro, lựa chọn cách xử lý an toàn
                và giải thích quyết định.
                Không sử dụng nội dung vượt độ tuổi hoặc mang tính kích thích.
            """.trimIndent()
        }
    }

    // ============================================================
    // SEX EDUCATION PROMPT V2
    // ============================================================

    private fun buildSexEducationPrompt(
        grade: Int,
        subject: String,
        topic: String?,
        difficulty: Difficulty,
        previousAssignments: List<String> = emptyList(),
        qualityFeedback: String? = null
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

        val previousText = buildPreviousAssignmentsContext(
            previousAssignments
        )

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

        return """
            Bạn là giáo viên Việt Nam thiết kế bài học giáo dục sức khỏe
            giới tính/sức khỏe sinh sản phù hợp tuổi.

            === THÔNG TIN ===
            - Lớp: $grade
            - Môn: $subject
            - Chủ đề: $topicText
            - Độ khó: $difficulty

            === PHẠM VI ĐƯỢC PHÉP ===
            ${getSexEducationScope(grade)}

            === ĐỘ KHÓ ===
            ${getSexEducationDifficulty(difficulty)}

            ${blueprintAsPrompt(blueprint)}

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

            Không dùng EXACT cho câu hỏi mở về kiến thức/giải thích.

            === CHẤT LƯỢNG ===
            Câu hỏi phải:
            - có nghĩa;
            - tự nhiên bằng tiếng Việt;
            - không mơ hồ;
            - đủ dữ kiện;
            - có câu trả lời xác định hoặc tiêu chí chấm xác định;
            - phù hợp tuổi;
            - không chứa từ vô nghĩa;
            - không có lỗi ngữ nghĩa;
            - không có placeholder.

            $previousText

            $qualityText

            === JSON OUTPUT ===
            Chỉ trả về JSON hợp lệ, không markdown:

            {
              "title": "Tên bài",
              "questions": [
                {
                  "id": 1,
                  "question": "...",
                  "learningObjective": "...",
                  "points": 3,
                  "answerType": "TEXT",
                  "gradingMethod": "AI_TEXT"
                },
                {
                  "id": 2,
                  "question": "...",
                  "learningObjective": "...",
                  "points": 3,
                  "answerType": "TEXT",
                  "gradingMethod": "AI_TEXT"
                },
                {
                  "id": 3,
                  "question": "...",
                  "learningObjective": "...",
                  "points": 4,
                  "answerType": "TEXT",
                  "gradingMethod": "AI_TEXT"
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

    private fun createSexEducationBlueprint(
        grade: Int,
        topic: String,
        difficulty: Difficulty
    ): GenerationBlueprint {

        return GenerationBlueprint(
            subject = "SEX_EDUCATION",
            grade = grade,
            topic = topic,
            difficulty = difficulty,
            questions = listOf(
                BlueprintQuestion(
                    1,
                    CognitiveLevel.UNDERSTAND,
                    QuestionStrategy.DIRECT,
                    "Hiểu một kiến thức sức khỏe cơ bản.",
                    "Không hỏi trải nghiệm cá nhân."
                ),
                BlueprintQuestion(
                    2,
                    CognitiveLevel.APPLY,
                    QuestionStrategy.DECISION,
                    "Áp dụng kiến thức để chọn hành động an toàn trong tình huống giáo dục.",
                    "Không yêu cầu chia sẻ thông tin riêng tư."
                ),
                BlueprintQuestion(
                    3,
                    CognitiveLevel.REASON,
                    QuestionStrategy.CAUSE_EFFECT,
                    "Giải thích vì sao một lựa chọn an toàn phù hợp hoặc phân tích nguy cơ.",
                    "Không mô tả nội dung tình dục không cần thiết."
                )
            )
        )
    }

    // ============================================================
    // PARSE RESPONSE
    // ============================================================

    private fun parseResponse(
        responseText: String
    ): GeneratedAssignment {

        val root = parseGeminiJsonResponse(
            responseText = responseText
        )

        val title = root
            .jsonObject["title"]
            ?.jsonPrimitive
            ?.contentOrNull
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: "Bài tập"

        val questionsElement = root
            .jsonObject["questions"]
            ?: throw IllegalStateException(
                "Gemini response missing questions"
            )

        val questions = questionsElement
            .jsonArray
            .map { element ->

                val obj = element.jsonObject

                val id = obj["id"]
                    ?.jsonPrimitive
                    ?.intOrNull
                    ?: throw IllegalStateException(
                        "Question ID is missing"
                    )

                val question = obj["question"]
                    ?.jsonPrimitive
                    ?.contentOrNull
                    ?.trim()
                    ?: throw IllegalStateException(
                        "Question $id text is missing"
                    )

                val learningObjective = obj["learningObjective"]
                    ?.jsonPrimitive
                    ?.contentOrNull
                    ?.trim()
                    ?: throw IllegalStateException(
                        "Question $id learningObjective is missing"
                    )

                val points = obj["points"]
                    ?.jsonPrimitive
                    ?.doubleOrNull
                    ?: throw IllegalStateException(
                        "Question $id points is missing"
                    )

                val answerTypeText = obj["answerType"]
                    ?.jsonPrimitive
                    ?.contentOrNull
                    ?: throw IllegalStateException(
                        "Question $id answerType is missing"
                    )

                val gradingMethodText = obj["gradingMethod"]
                    ?.jsonPrimitive
                    ?.contentOrNull
                    ?: throw IllegalStateException(
                        "Question $id gradingMethod is missing"
                    )

                val answerType = try {
                    AnswerType.valueOf(
                        answerTypeText.trim().uppercase()
                    )
                } catch (e: Exception) {
                    throw IllegalStateException(
                        "Question $id has invalid answerType: $answerTypeText"
                    )
                }

                val gradingMethod = try {
                    GradingMethod.valueOf(
                        gradingMethodText.trim().uppercase()
                    )
                } catch (e: Exception) {
                    throw IllegalStateException(
                        "Question $id has invalid gradingMethod: $gradingMethodText"
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
                    gradingMethod = gradingMethod
                )
            }

        if (questions.size != 3) {
            throw IllegalStateException(
                "Assignment must contain exactly 3 questions, found ${questions.size}"
            )
        }

        if (questions.map { it.id } != listOf(1, 2, 3)) {
            throw IllegalStateException(
                "Question IDs must be exactly [1, 2, 3], found ${questions.map { it.id }}"
            )
        }

        val answerKeyElement = root
            .jsonObject["answerKey"]
            ?: throw IllegalStateException(
                "Gemini response missing answerKey"
            )

        val answerKey = answerKeyElement
            .jsonArray
            .map { element ->

                val obj = element.jsonObject

                val id = obj["id"]
                    ?.jsonPrimitive
                    ?.intOrNull
                    ?: throw IllegalStateException(
                        "Answer ID is missing"
                    )

                val answer = obj["answer"]
                    ?.jsonPrimitive
                    ?.contentOrNull
                    ?.trim()
                    ?: throw IllegalStateException(
                        "Answer $id is missing"
                    )

                GeneratedAnswer(
                    id = id,
                    answer = answer
                )
            }

        if (answerKey.size != 3) {
            throw IllegalStateException(
                "Answer key must contain exactly 3 answers"
            )
        }

        if (answerKey.map { it.id } != listOf(1, 2, 3)) {
            throw IllegalStateException(
                "Answer IDs must be exactly [1,2,3]"
            )
        }

        if (questions.map { it.id }.toSet() != answerKey.map { it.id }.toSet()) {
            throw IllegalStateException(
                "Question IDs and answer IDs do not match"
            )
        }

        val gradingGuide = root
            .jsonObject["gradingGuide"]
            ?.jsonPrimitive
            ?.contentOrNull
            ?.trim()
            ?: ""

        val totalScore = root
            .jsonObject["totalScore"]
            ?.jsonPrimitive
            ?.doubleOrNull
            ?: 10.0

        if (!totalScore.isFinite()) {
            throw IllegalStateException(
                "totalScore is not finite"
            )
        }

        val calculatedScore = questions.sumOf { it.points }

        if (
            !calculatedScore.isFinite() ||
            abs(calculatedScore - totalScore) > 0.001
        ) {
            throw IllegalStateException(
                "Question points ($calculatedScore) do not match totalScore ($totalScore)"
            )
        }

        if (abs(totalScore - 10.0) > 0.001) {
            throw IllegalStateException(
                "totalScore must be exactly 10, actual=$totalScore"
            )
        }

        return GeneratedAssignment(
            title = title,
            questions = questions,
            answerKey = answerKey,
            gradingGuide = gradingGuide,
            totalScore = totalScore
        )
    }

    private fun parseGeminiJsonResponse(
        responseText: String
    ): JsonElement {

        val responseRoot = try {
            json.parseToJsonElement(responseText).jsonObject
        } catch (e: Exception) {
            throw IllegalStateException(
                "Invalid Gemini HTTP JSON response",
                e
            )
        }

        val candidateText = responseRoot["candidates"]
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
            ?: throw IllegalStateException(
                "Gemini response does not contain candidate text"
            )

        val cleaned = cleanJsonText(candidateText)

        return try {
            json.parseToJsonElement(cleaned)
        } catch (e: Exception) {
            throw IllegalStateException(
                "Gemini returned invalid assignment JSON: " +
                        cleaned.take(1000),
                e
            )
        }
    }

    private fun cleanJsonText(
        text: String
    ): String {

        var cleaned = text.trim()

        if (cleaned.startsWith("```")) {
            cleaned = cleaned
                .removePrefix("```json")
                .removePrefix("```JSON")
                .removePrefix("```")
                .trim()

            if (cleaned.endsWith("```")) {
                cleaned = cleaned
                    .removeSuffix("```")
                    .trim()
            }
        }

        val firstBrace = cleaned.indexOf('{')
        val lastBrace = cleaned.lastIndexOf('}')

        if (
            firstBrace >= 0 &&
            lastBrace > firstBrace
        ) {
            cleaned = cleaned.substring(
                firstBrace,
                lastBrace + 1
            )
        }

        return cleaned.trim()
    }

    // ============================================================
    // STRUCTURAL VALIDATION AFTER PARSING
    // ============================================================

    private fun validateParsedAssignment(
        assignment: GeneratedAssignment,
        grade: Int,
        subject: String,
        difficulty: Difficulty
    ) {

        val errors = mutableListOf<String>()

        if (assignment.title.isBlank()) {
            errors += "Title is empty"
        }

        if (assignment.title.length < 5) {
            errors += "Title is too short"
        }

        if (assignment.questions.size != 3) {
            errors += "Expected exactly 3 questions"
        }

        if (assignment.questions.map { it.id } != listOf(1, 2, 3)) {
            errors += "Question IDs must be [1,2,3]"
        }

        if (assignment.answerKey.size != 3) {
            errors += "Expected exactly 3 answers"
        }

        if (assignment.answerKey.map { it.id } != listOf(1, 2, 3)) {
            errors += "Answer IDs must be [1,2,3]"
        }

        if (assignment.gradingGuide.length < 10) {
            errors += "Grading guide is too short"
        }

        if (!assignment.totalScore.isFinite()) {
            errors += "totalScore is not finite"
        }

        if (abs(assignment.totalScore - 10.0) > 0.001) {
            errors += "totalScore must be 10"
        }

        val pointSum = assignment.questions.sumOf { it.points }

        if (
            !pointSum.isFinite() ||
            abs(pointSum - 10.0) > 0.001
        ) {
            errors += "Question points must total 10"
        }

        assignment.questions.forEach { q ->

            if (q.question.isBlank()) {
                errors += "Question ${q.id} is empty"
            }

            if (q.question.trim().length < 10) {
                errors += "Question ${q.id} is too short"
            }

            if (q.learningObjective.isBlank()) {
                errors += "Question ${q.id} has empty learning objective"
            }

            if (q.learningObjective.trim().length < 10) {
                errors += "Question ${q.id} learning objective is too short"
            }

            if (
                !q.points.isFinite() ||
                q.points <= 0.0
            ) {
                errors += "Question ${q.id} has invalid points"
            }

            if (
                q.answerType == AnswerType.DRAWING ||
                q.answerType == AnswerType.MIXED
            ) {
                errors +=
                    "Question ${q.id}: DRAWING/MIXED is not supported"
            }

            val normalized = q.question
                .lowercase()
                .replace(Regex("\\s+"), " ")
                .trim()

            if (containsSuspiciousText(normalized)) {
                errors +=
                    "Question ${q.id} contains suspicious/generated placeholder text"
            }

            val longWord = normalized
                .split(Regex("\\s+"))
                .any { it.length > 40 }

            if (longWord) {
                errors +=
                    "Question ${q.id} contains an abnormally long word"
            }

            if (
                Regex("(.)\\1{5,}")
                    .containsMatchIn(normalized)
            ) {
                errors +=
                    "Question ${q.id} contains excessive repeated characters"
            }
        }

        assignment.answerKey.forEach { answer ->

            if (answer.answer.isBlank()) {
                errors += "Answer ${answer.id} is empty"
            }

            if (containsSuspiciousText(answer.answer.lowercase())) {
                errors +=
                    "Answer ${answer.id} contains suspicious text"
            }
        }

        val normalizedQuestions = assignment.questions
            .map { normalizeQuestion(it.question) }

        if (
            normalizedQuestions.distinct().size !=
            normalizedQuestions.size
        ) {
            errors += "Duplicate questions detected"
        }

        val normalizedObjectives = assignment.questions
            .map {
                normalizeSemanticText(
                    it.learningObjective
                )
            }

        if (
            normalizedObjectives.distinct().size !=
            normalizedObjectives.size
        ) {
            errors +=
                "Learning objectives are duplicated"
        }

        val answerIds = assignment.answerKey
            .map { it.id }
            .toSet()

        val questionIds = assignment.questions
            .map { it.id }
            .toSet()

        if (answerIds != questionIds) {
            errors +=
                "Question and answer IDs do not match"
        }

        if (errors.isNotEmpty()) {
            throw IllegalStateException(
                buildString {
                    appendLine(
                        "Generated assignment failed structural validation."
                    )
                    appendLine(
                        "Grade=$grade subject=$subject difficulty=$difficulty"
                    )
                    errors.forEach {
                        appendLine("- $it")
                    }
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
            text.contains(it, ignoreCase = true)
        }
    }

    private fun normalizeQuestion(
        text: String
    ): String {
        return text
            .lowercase()
            .replace(Regex("\\s+"), " ")
            .trim()
            .replace(Regex("[.!?,;:]+$"), "")
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
            errors += "Sex education assignment must have exactly 3 questions"
        }

        if (assignment.answerKey.size != 3) {
            errors += "Sex education assignment must have exactly 3 answers"
        }

        if (abs(assignment.totalScore - 10.0) > 0.001) {
            errors += "Sex education totalScore must be 10"
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

            val normalized = question.question
                .lowercase()
                .replace(Regex("\\s+"), " ")

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

                val text = question.question.lowercase()

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
                        "Question ${question.id} may exceed the age scope for grade $grade"
                }
            }
        }

        if (errors.isNotEmpty()) {
            throw IllegalStateException(
                "Sex education validation failed:\n" +
                        errors.joinToString("\n") { "- $it" }
            )
        }

        println(
            "[AIService] Sex education validation PASS " +
                    "grade=$grade"
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
                        gradingMethod == GradingMethod.OPENCV_VISION_AI

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
        difficulty: Difficulty
    ): AssignmentQualityReview {

        val sexEducation = isSexEducation(subject)

        println(
            "[AIService] Reviewing generated assignment " +
                    "grade=$grade subject=$subject topic=$topic difficulty=$difficulty"
        )

        val blueprint = if (sexEducation) {
            createSexEducationBlueprint(
                grade = grade,
                topic = topic ?: "sức khỏe giới tính phù hợp độ tuổi",
                difficulty = difficulty
            )
        } else {
            createBlueprint(
                grade = grade,
                subject = subject,
                topic = topic,
                difficulty = difficulty
            )
        }

        val prompt = buildQualityReviewPrompt(
            assignment = assignment,
            grade = grade,
            subject = subject,
            topic = topic,
            difficulty = difficulty,
            blueprint = blueprint
        )

        val response = withContext(Dispatchers.IO) {
            callGeminiWithRetry(
                prompt = prompt,
                sexEducation = sexEducation,
                temperature = 0.1
            )
        }

        val review = parseQualityReviewResponse(
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
        blueprint: GenerationBlueprint
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
                """.trimIndent()
            }

        val answersText = assignment.answerKey
            .joinToString("\n") {
                "Answer ${it.id}: ${it.answer}"
            }

        return """
            Bạn là reviewer chất lượng giáo dục.
            KHÔNG sửa bài. Chỉ đánh giá PASS hoặc FAIL.

            === CONTEXT ===
            Grade: $grade
            Subject: $subject
            Topic: ${topic ?: "auto"}
            Difficulty: $difficulty

            === BLUEPRINT MONG MUỐN ===
            ${blueprintAsPrompt(blueprint)}

            === ASSIGNMENT ===
            Title:
            ${assignment.title}

            Questions:
            $questionsText

            Answer key:
            $answersText

            Grading guide:
            ${assignment.gradingGuide}

            Total score:
            ${assignment.totalScore}

            === REVIEW CRITERIA ===

            1. NGÔN NGỮ
            - Chính tả đúng.
            - Từ ngữ tự nhiên.
            - Ngữ pháp đúng.
            - Không có từ vô nghĩa.
            - Không có câu dịch máy khó hiểu.
            - Không có lỗi kiểu AI như:
              "bà giắt", "bat ngô",
              "con vật thực hiện phép cộng",
              "một cây có thể chạy nhanh"
              hoặc các câu tương tự vô nghĩa.

            2. Ý NGHĨA CÂU HỎI
            - Câu hỏi phải hiểu được ngay.
            - Đủ dữ kiện.
            - Không mâu thuẫn.
            - Không mơ hồ.
            - Không có nhiều cách hiểu ngoài ý muốn.

            3. ĐÚNG KIẾN THỨC
            - Kiến thức đúng.
            - Công thức đúng.
            - Thuật ngữ đúng.
            - AnswerKey đúng.
            - Không có kết luận khoa học sai.

            4. PHÙ HỢP LỚP
            - Không dùng kiến thức vượt lớp.
            - Không dùng thuật ngữ chưa phù hợp.
            - Độ dài và cách diễn đạt phù hợp.

            5. ĐỘ KHÓ
            - Phù hợp $difficulty.
            - Không quá dễ.
            - Không quá khó.
            - Không tăng độ khó giả tạo chỉ bằng số lớn hoặc câu dài.

            6. COGNITIVE PROGRESSION
            Q1 phải thiên về hiểu/nền tảng.
            Q2 phải thiên về vận dụng.
            Q3 phải thiên về reasoning/phân tích khi môn học cho phép.

            Nếu cả 3 câu thực chất dùng cùng một thao tác,
            phải FAIL.

            7. SEMANTIC DIVERSITY
            FAIL nếu:
            - chỉ đổi số;
            - chỉ đổi tên;
            - chỉ đổi một vài từ;
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

            8. ANSWER ALIGNMENT
            Mỗi answer phải trực tiếp trả lời question tương ứng.

            9. GRADING
            GradingGuide phải phù hợp câu hỏi.
            Không được yêu cầu tiêu chí mà answerKey không hỗ trợ.

            10. ANSWER TYPE
            Chỉ:
            TEXT,
            HANDWRITING,
            SPEECH_TO_TEXT.

            Mapping:
            TEXT -> EXACT/AI_TEXT
            HANDWRITING -> OCR_AI
            SPEECH_TO_TEXT -> EXACT/AI_TEXT

            DRAWING/MIXED phải FAIL.

            11. PEDAGOGY
            Bài tập phải có giá trị giáo dục.
            Không được tạo ba câu chỉ để đủ số lượng.

            12. SEX EDUCATION
            Nếu là giáo dục giới tính:
            - phù hợp độ tuổi;
            - khoa học;
            - không khiêu dâm;
            - không yêu cầu thông tin riêng tư;
            - không yêu cầu trải nghiệm cá nhân;
            - không sexualize trẻ em;
            - ưu tiên an toàn và tìm trợ giúp.

            === QUYẾT ĐỊNH ===
            Chỉ PASS khi tất cả tiêu chí quan trọng đều đạt.
            Chỉ một lỗi nghiêm trọng cũng phải FAIL.

            Trả về JSON duy nhất:

            {
              "pass": true,
              "issues": [],
              "summary": "..."
            }
        """.trimIndent()
    }

    private fun parseQualityReviewResponse(
        responseText: String
    ): AssignmentQualityReview {

        val root = parseGeminiJsonResponse(
            responseText
        ).jsonObject

        val pass = root["pass"]
            ?.jsonPrimitive
            ?.booleanOrNull
            ?: throw IllegalStateException(
                "Quality review missing pass"
            )

        val issues = root["issues"]
            ?.jsonArray
            ?.mapNotNull {
                it.jsonPrimitive.contentOrNull
            }
            ?: emptyList()

        val summary = root["summary"]
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
                    looksLikeSexEducationAssignment(assignment)

        val prompt = if (sexEducation) {
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

        val key = if (sexEducation) {
            sexEducationApiKey ?: apiKey
        } else {
            apiKey
        }

        require(!key.isNullOrBlank()) {
            "Gemini API key is not configured"
        }

        val selectedModel = if (sexEducation) {
            sexEducationModel
        } else {
            model
        }

        val response = withContext(Dispatchers.IO) {
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

        val combined = buildString {
            append(assignment.title)
            append(" ")
            assignment.questions.forEach {
                append(it.question)
                append(" ")
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

        val questions = assignment.questions
            .joinToString("\n\n") { q ->
                """
                Question ${q.id}:
                ${q.question}

                Expected answer:
                ${assignment.answerKey
                    .firstOrNull { a -> a.id == q.id }
                    ?.answer ?: ""}

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
            Bạn là giáo viên chấm bài.

            === ASSIGNMENT ===
            Title:
            ${assignment.title}

            $questions

            Grading guide:
            ${assignment.gradingGuide}

            Total score:
            ${assignment.totalScore}

            === STUDENT ANSWER ===
            $studentAnswer

            === RULES ===
            - Chấm đúng theo nội dung bài.
            - Không thay đổi thang điểm.
            - Điểm tối đa là ${assignment.totalScore}.
            - Cho điểm từng phần nếu câu trả lời đúng một phần.
            - Chấp nhận cách diễn đạt khác nếu nội dung đúng.
            - Không phạt chỉ vì cách diễn đạt khác answerKey nếu ý nghĩa đúng.
            - Không tự tạo dữ kiện không có trong bài.
            - Không đoán ý học sinh ngoài câu trả lời.
            - Feedback ngắn, rõ, phù hợp học sinh.

            Nếu studentAnswer trả lời đúng:
            - cho điểm đầy đủ.

            Nếu đúng một phần:
            - cho điểm tương ứng.

            Nếu sai:
            - điểm thấp hoặc 0 tùy mức độ.
            - feedback chỉ ra kiến thức cần sửa.

            Trả JSON:

            {
              "score": 0,
              "feedback": "..."
            }
        """.trimIndent()
    }

    private fun buildSexEducationGradingSystemInstruction(): String {
        return """
            Bạn chấm câu trả lời giáo dục sức khỏe giới tính/sức khỏe sinh sản.

            Chỉ đánh giá kiến thức trong câu trả lời.
            Không suy đoán đời sống riêng tư.
            Không yêu cầu học sinh kể trải nghiệm cá nhân.
            Không phán xét hoặc làm xấu hổ học sinh.
            Chấp nhận cách diễn đạt khác nếu kiến thức đúng.
            Với câu hỏi an toàn, ưu tiên:
            - nhận diện nguy cơ;
            - bảo vệ bản thân;
            - ranh giới;
            - tìm người lớn đáng tin cậy;
            - tìm hỗ trợ chuyên môn khi cần.

            Không đưa nội dung tình dục không cần thiết vào feedback.
        """.trimIndent()
    }

    private fun buildSexEducationGradingPrompt(
        assignment: GeneratedAssignment,
        studentAnswer: String
    ): String {

        val questions = assignment.questions
            .joinToString("\n\n") { q ->
                """
                Question ${q.id}:
                ${q.question}

                Expected answer:
                ${assignment.answerKey
                    .firstOrNull { a -> a.id == q.id }
                    ?.answer ?: ""}

                Learning objective:
                ${q.learningObjective}

                Points:
                ${q.points}
                """.trimIndent()
            }

        return """
            Chấm bài giáo dục sức khỏe phù hợp độ tuổi.

            $questions

            Grading guide:
            ${assignment.gradingGuide}

            Total score:
            ${assignment.totalScore}

            Student answer:
            $studentAnswer

            Quy tắc:
            - Chỉ đánh giá kiến thức.
            - Không đánh giá đời sống cá nhân.
            - Không yêu cầu trải nghiệm cá nhân.
            - Không phán xét.
            - Chấp nhận cách diễn đạt đúng khác answerKey.
            - Nếu có hiểu lầm, giải thích ngắn gọn kiến thức đúng.
            - Nếu câu hỏi liên quan an toàn, ưu tiên hành động an toàn và tìm người đáng tin cậy.
            - Không thay đổi thang điểm.

            Chỉ trả:
            {
              "score": 0,
              "feedback": "..."
            }
        """.trimIndent()
    }

    private fun parseGradingResponse(
        responseText: String
    ): GradingResult {

        val root = parseGeminiJsonResponse(
            responseText
        ).jsonObject

        val score = root["score"]
            ?.jsonPrimitive
            ?.doubleOrNull
            ?: throw IllegalStateException(
                "Grading response missing score"
            )

        val feedback = root["feedback"]
            ?.jsonPrimitive
            ?.contentOrNull
            ?: ""

        if (!score.isFinite()) {
            throw IllegalStateException(
                "Grading score is not finite"
            )
        }

        return GradingResult(
            score = score,
            feedback = feedback,
            questions = emptyList()
        )
    }
}