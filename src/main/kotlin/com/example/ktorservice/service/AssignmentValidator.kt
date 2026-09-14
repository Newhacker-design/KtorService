package com.example.ktorservice.service

import kotlin.math.abs

object AssignmentValidator {

    data class ValidationResult(
        val valid: Boolean,
        val errors: List<String> = emptyList()
    )

    // =========================================================
    // CONFIG
    // =========================================================

    private const val MIN_TITLE_LENGTH = 5
    private const val MIN_QUESTION_LENGTH = 10
    private const val MIN_OBJECTIVE_LENGTH = 10
    private const val MIN_GRADING_GUIDE_LENGTH = 10

    private const val MAX_WORD_LENGTH = 40
    private const val MAX_REPEATED_CHARACTERS = 5

    // Những từ/cụm từ thường xuất hiện khi AI sinh nội dung lỗi
    private val suspiciousPhrases = listOf(
        "undefined",
        "null null",
        "lorem ipsum",
        "asdf",
        "qwerty",
        "test test",
        "placeholder",
        "your answer here",
        "insert answer",
        "insert question",
        "todo",
        "tbd",
        "n/a"
    )

    // Các token thường không phải từ tiếng Việt/Anh hợp lệ
    private val suspiciousTokens = setOf(
        "xxxxx",
        "xxxx",
        "aaaaa",
        "bbbbbb",
        "zzzzz",
        "qqqqq"
    )

    // Các cụm thường báo hiệu câu AI bị lỗi
    private val malformedPhrases = listOf(
        "hãy hãy",
        "em em hãy",
        "cho cho biết",
        "là là",
        "có có",
        "nêu nêu",
        "giải thích giải thích",
        "tại sao tại sao",
        "vì sao vì sao"
    )

    // =========================================================
    // MAIN VALIDATION
    // =========================================================

    fun validate(
        title: String,
        questions: List<AIService.GeneratedQuestion>,
        answerKey: List<AIService.GeneratedAnswer>,
        gradingGuide: String,
        totalScore: Double
    ): ValidationResult {

        val errors = mutableListOf<String>()

        // =====================================================
        // TITLE
        // =====================================================

        validateTitle(title, errors)

        // =====================================================
        // QUESTIONS COUNT / IDS
        // =====================================================

        if (questions.size != 3) {
            errors +=
                "Assignment must contain exactly 3 questions, found ${questions.size}"
        }

        val questionIds = questions.map { it.id }

        if (questionIds.distinct().size != questionIds.size) {
            errors +=
                "Duplicate question IDs detected: $questionIds"
        }

        if (questionIds != listOf(1, 2, 3)) {
            errors +=
                "Question IDs must be exactly [1, 2, 3], found $questionIds"
        }

        // =====================================================
        // QUESTION CONTENT
        // =====================================================

        questions.forEach { question ->

            validateQuestion(
                question = question,
                errors = errors
            )
        }

        // =====================================================
        // DUPLICATE QUESTIONS
        // =====================================================

        validateDuplicateQuestions(
            questions = questions,
            errors = errors
        )

        // =====================================================
        // QUESTION SIMILARITY
        // =====================================================

        validateSimilarQuestions(
            questions = questions,
            errors = errors
        )

        // =====================================================
        // ANSWER KEY
        // =====================================================

        if (answerKey.size != 3) {
            errors +=
                "Answer key must contain exactly 3 answers, found ${answerKey.size}"
        }

        val answerIds = answerKey.map { it.id }

        if (answerIds.distinct().size != answerIds.size) {
            errors +=
                "Duplicate answer IDs detected: $answerIds"
        }

        if (answerIds != listOf(1, 2, 3)) {
            errors +=
                "Answer IDs must be exactly [1, 2, 3], found $answerIds"
        }

        answerKey.forEach { answer ->

            validateAnswer(
                answer = answer,
                errors = errors
            )
        }

        // =====================================================
        // QUESTION / ANSWER IDs
        // =====================================================

        if (questionIds.toSet() != answerIds.toSet()) {
            errors +=
                "Question IDs and answer IDs do not match"
        }

        // =====================================================
        // QUESTION ↔ ANSWER COMPATIBILITY
        // =====================================================

        validateQuestionAnswerCompatibility(
            questions = questions,
            answerKey = answerKey,
            errors = errors
        )

        // =====================================================
        // LEARNING OBJECTIVE ↔ QUESTION
        // =====================================================

        validateLearningObjectives(
            questions = questions,
            errors = errors
        )

        // =====================================================
        // GRADING GUIDE
        // =====================================================

        if (gradingGuide.isBlank()) {
            errors += "Grading guide is empty"
        } else if (gradingGuide.trim().length < MIN_GRADING_GUIDE_LENGTH) {
            errors +=
                "Grading guide is too short"
        }

        validateTextQuality(
            text = gradingGuide,
            fieldName = "Grading guide",
            errors = errors,
            checkQuestionStructure = false
        )

        // =====================================================
        // SCORE
        // =====================================================

        val calculatedScore =
            questions.sumOf { it.points }

        if (abs(calculatedScore - 10.0) > 0.001) {
            errors +=
                "Question points must total 10, actual=$calculatedScore"
        }

        if (totalScore.isNaN() || totalScore.isInfinite()) {
            errors +=
                "totalScore is invalid: $totalScore"
        } else if (abs(totalScore - 10.0) > 0.001) {
            errors +=
                "totalScore must be 10, actual=$totalScore"
        }

        // =====================================================
        // RESULT
        // =====================================================

        return ValidationResult(
            valid = errors.isEmpty(),
            errors = errors.distinct()
        )
    }

    // =========================================================
    // TITLE
    // =========================================================

    private fun validateTitle(
        title: String,
        errors: MutableList<String>
    ) {

        val text = title.trim()

        if (text.isBlank()) {
            errors += "Title is empty"
            return
        }

        if (text.length < MIN_TITLE_LENGTH) {
            errors +=
                "Title is too short"
        }

        validateTextQuality(
            text = text,
            fieldName = "Title",
            errors = errors,
            checkQuestionStructure = false
        )
    }

    // =========================================================
    // QUESTION
    // =========================================================

    private fun validateQuestion(
        question: AIService.GeneratedQuestion,
        errors: MutableList<String>
    ) {

        val id = question.id
        val text = question.question.trim()

        // -----------------------------------------------------
        // BASIC
        // -----------------------------------------------------

        if (text.isBlank()) {
            errors +=
                "Question $id is empty"
            return
        }

        if (text.length < MIN_QUESTION_LENGTH) {
            errors +=
                "Question $id is too short"
        }

        // -----------------------------------------------------
        // LEARNING OBJECTIVE
        // -----------------------------------------------------

        val objective =
            question.learningObjective.trim()

        if (objective.isBlank()) {

            errors +=
                "Question $id has empty learning objective"

        } else if (objective.length < MIN_OBJECTIVE_LENGTH) {

            errors +=
                "Question $id learning objective is too short"
        }

        // -----------------------------------------------------
        // POINTS
        // -----------------------------------------------------

        if (
            question.points.isNaN() ||
            question.points.isInfinite() ||
            question.points <= 0
        ) {
            errors +=
                "Question $id has invalid points: ${question.points}"
        }

        // -----------------------------------------------------
        // ANSWER TYPE
        // -----------------------------------------------------

        when (question.answerType) {

            AIService.AnswerType.DRAWING -> {
                errors +=
                    "Question $id uses DRAWING, " +
                            "but ControlReceiver does not support DRAWING yet"
            }

            AIService.AnswerType.MIXED -> {
                errors +=
                    "Question $id uses MIXED, " +
                            "but ControlReceiver does not support MIXED yet"
            }

            else -> Unit
        }

        // -----------------------------------------------------
        // ANSWER TYPE ↔ GRADING METHOD
        // -----------------------------------------------------

        when (question.answerType) {

            AIService.AnswerType.TEXT -> {

                if (
                    question.gradingMethod !=
                    AIService.GradingMethod.EXACT &&
                    question.gradingMethod !=
                    AIService.GradingMethod.AI_TEXT
                ) {
                    errors +=
                        "Question $id: TEXT requires EXACT or AI_TEXT"
                }
            }

            AIService.AnswerType.HANDWRITING -> {

                if (
                    question.gradingMethod !=
                    AIService.GradingMethod.OCR_AI
                ) {
                    errors +=
                        "Question $id: HANDWRITING requires OCR_AI"
                }
            }

            AIService.AnswerType.SPEECH_TO_TEXT -> {

                if (
                    question.gradingMethod !=
                    AIService.GradingMethod.EXACT &&
                    question.gradingMethod !=
                    AIService.GradingMethod.AI_TEXT
                ) {
                    errors +=
                        "Question $id: SPEECH_TO_TEXT requires EXACT or AI_TEXT"
                }
            }

            else -> Unit
        }

        // -----------------------------------------------------
        // TEXT QUALITY
        // -----------------------------------------------------

        validateTextQuality(
            text = text,
            fieldName = "Question $id",
            errors = errors,
            checkQuestionStructure = true
        )

        // -----------------------------------------------------
        // QUESTION MUST LOOK LIKE A QUESTION
        // -----------------------------------------------------

        validateQuestionStructure(
            id = id,
            text = text,
            errors = errors
        )
    }

    // =========================================================
    // ANSWER
    // =========================================================

    private fun validateAnswer(
        answer: AIService.GeneratedAnswer,
        errors: MutableList<String>
    ) {

        val id = answer.id
        val text = answer.answer.trim()

        if (text.isBlank()) {
            errors +=
                "Answer $id is empty"
            return
        }

        if (text.length < 1) {
            errors +=
                "Answer $id is invalid"
        }

        validateTextQuality(
            text = text,
            fieldName = "Answer $id",
            errors = errors,
            checkQuestionStructure = false
        )

        // -----------------------------------------------------
        // Đáp án chỉ gồm ký tự vô nghĩa
        // -----------------------------------------------------

        if (
            text.length <= 3 &&
            text.matches(
                Regex("[.\\-_,;:!?]+")
            )
        ) {
            errors +=
                "Answer $id contains only punctuation"
        }

        // -----------------------------------------------------
        // Đáp án chỉ là placeholder
        // -----------------------------------------------------

        if (
            text.equals("n/a", ignoreCase = true) ||
            text.equals("none", ignoreCase = true) ||
            text.equals("unknown", ignoreCase = true)
        ) {
            errors +=
                "Answer $id appears to be a placeholder"
        }
    }

    // =========================================================
    // TEXT QUALITY
    // =========================================================

    private fun validateTextQuality(
        text: String,
        fieldName: String,
        errors: MutableList<String>,
        checkQuestionStructure: Boolean
    ) {

        val normalized = text.trim()

        if (normalized.isBlank()) return

        // -----------------------------------------------------
        // PLACEHOLDER / AI ARTIFACT
        // -----------------------------------------------------

        suspiciousPhrases.forEach { phrase ->

            if (
                normalized.contains(
                    phrase,
                    ignoreCase = true
                )
            ) {
                errors +=
                    "$fieldName contains suspicious text: '$phrase'"
            }
        }

        // -----------------------------------------------------
        // TOKEN RÁC
        // -----------------------------------------------------

        normalized
            .split(Regex("\\s+"))
            .map {
                it.trim(
                    ' ', '.', ',', ';', ':',
                    '!', '?', '(', ')',
                    '[', ']', '{', '}',
                    '"', '\''
                )
            }
            .filter { it.isNotBlank() }
            .forEach { token ->

                if (
                    token.lowercase() in suspiciousTokens
                ) {
                    errors +=
                        "$fieldName contains suspicious token: '$token'"
                }
            }

        // -----------------------------------------------------
        // TỪ QUÁ DÀI
        // -----------------------------------------------------

        val suspiciousLongWord =
            normalized
                .split(Regex("\\s+"))
                .any { word ->

                    word
                        .trim(
                            '.', ',', ';', ':',
                            '!', '?', '(', ')'
                        )
                        .length > MAX_WORD_LENGTH
                }

        if (suspiciousLongWord) {

            errors +=
                "$fieldName contains an abnormally long word"
        }

        // -----------------------------------------------------
        // KÝ TỰ LẶP
        // -----------------------------------------------------

        if (
            Regex(
                "(.)\\1{$MAX_REPEATED_CHARACTERS,}"
            ).containsMatchIn(normalized)
        ) {

            errors +=
                "$fieldName contains excessive repeated characters"
        }

        // -----------------------------------------------------
        // TỪ LẶP LIÊN TIẾP
        // -----------------------------------------------------

        val words =
            normalized
                .lowercase()
                .split(Regex("\\s+"))
                .map {
                    it.trim(
                        '.', ',', ';', ':',
                        '!', '?', '(', ')'
                    )
                }
                .filter { it.isNotBlank() }

        for (i in 0 until words.size - 1) {

            if (
                words[i].length >= 3 &&
                words[i] == words[i + 1]
            ) {

                errors +=
                    "$fieldName contains repeated word: '${words[i]}'"
                break
            }
        }

        // -----------------------------------------------------
        // CỤM TỪ BỊ LẶP
        // -----------------------------------------------------

        malformedPhrases.forEach { phrase ->

            if (
                normalized.contains(
                    phrase,
                    ignoreCase = true
                )
            ) {

                errors +=
                    "$fieldName contains malformed repeated phrase: '$phrase'"
            }
        }

        // -----------------------------------------------------
        // QUÁ NHIỀU DẤU CÂU LIÊN TIẾP
        // -----------------------------------------------------

        if (
            Regex("[!?.,;:]{4,}")
                .containsMatchIn(normalized)
        ) {

            errors +=
                "$fieldName contains excessive punctuation"
        }

        // -----------------------------------------------------
        // NHIỀU KÝ TỰ KHÔNG PHẢI CHỮ/SỐ
        // -----------------------------------------------------

        val alphanumericCount =
            normalized.count {
                it.isLetterOrDigit()
            }

        val suspiciousCharacterCount =
            normalized.count {
                !it.isLetterOrDigit() &&
                        !it.isWhitespace() &&
                        it !in ".,;:!?()[]{}\"'-/%"
            }

        if (
            alphanumericCount > 10 &&
            suspiciousCharacterCount >
            alphanumericCount * 0.3
        ) {

            errors +=
                "$fieldName contains an abnormal number of special characters"
        }

        // -----------------------------------------------------
        // CẢ ĐOẠN TOÀN HOA BẤT THƯỜNG
        // -----------------------------------------------------

        if (
            normalized.length >= 20 &&
            normalized.count { it.isLetter() } >= 10 &&
            normalized.filter { it.isLetter() }
                .all { it.isUpperCase() }
        ) {

            errors +=
                "$fieldName is entirely uppercase"
        }

        // -----------------------------------------------------
        // CHUỖI WORD KHÔNG CÓ Ý NGHĨA
        // -----------------------------------------------------

        val compact =
            normalized
                .lowercase()
                .replace(Regex("[^a-z0-9\\p{L}]"), "")

        if (
            compact.length >= 8 &&
            Regex("(.)\\1{4,}")
                .containsMatchIn(compact)
        ) {

            errors +=
                "$fieldName contains an abnormal repeated character sequence"
        }
    }

    // =========================================================
    // QUESTION STRUCTURE
    // =========================================================

    private fun validateQuestionStructure(
        id: Int,
        text: String,
        errors: MutableList<String>
    ) {

        val normalized =
            text.trim()

        // -----------------------------------------------------
        // Một câu hỏi bình thường thường phải có ít nhất
        // một số từ mang tính chất hỏi/yêu cầu.
        //
        // KHÔNG bắt buộc phải có dấu '?'
        // vì tiếng Việt có các câu mệnh lệnh:
        // "Nêu...", "Giải thích...", "Tính..."
        // -----------------------------------------------------

        val questionWords = listOf(
            "hãy",
            "nêu",
            "cho biết",
            "giải thích",
            "tại sao",
            "vì sao",
            "như thế nào",
            "thế nào",
            "bao nhiêu",
            "khi nào",
            "ở đâu",
            "ai",
            "cái gì",
            "điều gì",
            "tính",
            "xác định",
            "so sánh",
            "trình bày",
            "mô tả",
            "phân tích",
            "kể",
            "viết"
        )

        val hasQuestionWord =
            questionWords.any {
                normalized.contains(
                    it,
                    ignoreCase = true
                )
            }

        val hasQuestionMark =
            normalized.contains("?")

        if (!hasQuestionWord && !hasQuestionMark) {

            errors +=
                "Question $id does not appear to contain a valid question/instruction"
        }

        // -----------------------------------------------------
        // Câu hỏi không nên bắt đầu bằng ký tự lạ
        // -----------------------------------------------------

        val first =
            normalized.firstOrNull()

        if (
            first != null &&
            !first.isLetterOrDigit() &&
            first !in "\"'("
        ) {

            errors +=
                "Question $id starts with an abnormal character"
        }

        // -----------------------------------------------------
        // Câu hỏi chỉ có một/two token
        // -----------------------------------------------------

        val wordCount =
            normalized
                .split(Regex("\\s+"))
                .count { it.isNotBlank() }

        if (wordCount < 3) {

            errors +=
                "Question $id contains too few meaningful words"
        }
    }

    // =========================================================
    // DUPLICATE QUESTIONS
    // =========================================================

    private fun validateDuplicateQuestions(
        questions: List<AIService.GeneratedQuestion>,
        errors: MutableList<String>
    ) {

        val normalizedQuestions =
            questions.map {
                normalizeQuestion(it.question)
            }

        if (
            normalizedQuestions.distinct().size !=
            normalizedQuestions.size
        ) {

            errors +=
                "Duplicate questions detected"
        }
    }

    // =========================================================
    // SIMILAR QUESTIONS
    // =========================================================

    private fun validateSimilarQuestions(
        questions: List<AIService.GeneratedQuestion>,
        errors: MutableList<String>
    ) {

        for (i in questions.indices) {

            for (j in i + 1 until questions.size) {

                val a =
                    tokenizeQuestion(
                        questions[i].question
                    )

                val b =
                    tokenizeQuestion(
                        questions[j].question
                    )

                if (a.isEmpty() || b.isEmpty()) {
                    continue
                }

                val intersection =
                    a.intersect(b).size.toDouble()

                val union =
                    a.union(b).size.toDouble()

                if (union <= 0) continue

                val similarity =
                    intersection / union

                /*
                 * > 0.80:
                 * hai câu gần như cùng một câu hỏi.
                 *
                 * Không dùng threshold quá thấp vì:
                 * "Nêu nguyên nhân ô nhiễm nước"
                 * và
                 * "Nêu nguyên nhân ô nhiễm không khí"
                 * vẫn có thể là hai câu hợp lệ.
                 */

                if (similarity >= 0.80) {

                    errors +=
                        "Questions ${questions[i].id} and " +
                                "${questions[j].id} are too similar"
                }
            }
        }
    }

    // =========================================================
    // QUESTION ↔ ANSWER
    // =========================================================

    private fun validateQuestionAnswerCompatibility(
        questions: List<AIService.GeneratedQuestion>,
        answerKey: List<AIService.GeneratedAnswer>,
        errors: MutableList<String>
    ) {

        val answersById =
            answerKey.associateBy { it.id }

        questions.forEach { question ->

            val answer =
                answersById[question.id]
                    ?: return@forEach

            val questionText =
                question.question.trim()

            val answerText =
                answer.answer.trim()

            if (
                answerText.isBlank() ||
                questionText.isBlank()
            ) {
                return@forEach
            }

            // -------------------------------------------------
            // Không kiểm tra semantic quá mạnh.
            //
            // Ví dụ:
            // "Thủ đô Việt Nam là gì?"
            // "Hà Nội"
            //
            // Hai chuỗi gần như không có từ chung nhưng
            // hoàn toàn đúng.
            //
            // Vì vậy chỉ bắt những trường hợp rõ ràng bất thường.
            // -------------------------------------------------

            val answerWords =
                tokenizeQuestion(answerText)

            val questionWords =
                tokenizeQuestion(questionText)

            // -------------------------------------------------
            // Câu hỏi rất cụ thể nhưng answer quá ngắn/rỗng
            // -------------------------------------------------

            if (
                questionWords.size >= 8 &&
                answerWords.size == 1 &&
                answerText.length == 1
            ) {

                errors +=
                    "Answer ${question.id} appears too short for Question ${question.id}"
            }

            // -------------------------------------------------
            // Answer lại chính nguyên câu hỏi
            // -------------------------------------------------

            if (
                normalizeQuestion(questionText) ==
                normalizeQuestion(answerText)
            ) {

                errors +=
                    "Answer ${question.id} is identical to Question ${question.id}"
            }

            // -------------------------------------------------
            // Answer chứa nguyên câu hỏi + placeholder
            // -------------------------------------------------

            if (
                answerText.contains(
                    "câu hỏi",
                    ignoreCase = true
                ) &&
                answerText.contains(
                    "trả lời",
                    ignoreCase = true
                )
            ) {

                errors +=
                    "Answer ${question.id} appears to contain instruction text instead of an answer"
            }

            // -------------------------------------------------
            // AI có thể trả về:
            // "Đáp án: ..."
            //
            // Không reject vì đây vẫn có thể là answer hợp lệ,
            // nhưng nếu cả answer là dạng template thì reject.
            // -------------------------------------------------

            if (
                Regex(
                    "^\\s*(answer|đáp án|câu trả lời)\\s*[:：]\\s*(answer|đáp án|câu trả lời)\\s*$",
                    RegexOption.IGNORE_CASE
                ).matches(answerText)
            ) {

                errors +=
                    "Answer ${question.id} contains an invalid answer template"
            }
        }
    }

    // =========================================================
    // LEARNING OBJECTIVE
    // =========================================================

    private fun validateLearningObjectives(
        questions: List<AIService.GeneratedQuestion>,
        errors: MutableList<String>
    ) {

        questions.forEach { question ->

            val objective =
                tokenizeQuestion(
                    question.learningObjective
                )

            val questionWords =
                tokenizeQuestion(
                    question.question
                )

            if (
                objective.isEmpty() ||
                questionWords.isEmpty()
            ) {
                return@forEach
            }

            /*
             * Không yêu cầu learningObjective phải chứa từ giống
             * câu hỏi, vì:
             *
             * Question:
             * "Tại sao cây cần ánh sáng để quang hợp?"
             *
             * Objective:
             * "Hiểu vai trò của ánh sáng trong quá trình quang hợp."
             *
             * Hai câu có thể diễn đạt rất khác nhau.
             *
             * Chỉ bắt trường hợp objective hoàn toàn vô nghĩa/
             * không có từ khóa chung trong một số trường hợp.
             */

            val meaningfulQuestionWords =
                questionWords.filter {
                    it.length >= 4 &&
                            !isCommonWord(it)
                }.toSet()

            val meaningfulObjectiveWords =
                objective.filter {
                    it.length >= 4 &&
                            !isCommonWord(it)
                }.toSet()

            if (
                meaningfulQuestionWords.isNotEmpty() &&
                meaningfulObjectiveWords.isNotEmpty()
            ) {

                val common =
                    meaningfulQuestionWords
                        .intersect(
                            meaningfulObjectiveWords
                        )

                /*
                 * Chỉ cảnh báo/reject khi hoàn toàn không có
                 * bất kỳ keyword chung nào và objective rất dài.
                 *
                 * Threshold này cố ý khá nhẹ để tránh false positive.
                 */

                if (
                    common.isEmpty() &&
                    meaningfulQuestionWords.size >= 3 &&
                    meaningfulObjectiveWords.size >= 4
                ) {

                    errors +=
                        "Question ${question.id} learning objective may not match the question"
                }
            }
        }
    }

    // =========================================================
    // TOKENIZER
    // =========================================================

    private fun tokenizeQuestion(
        text: String
    ): Set<String> {

        return text
            .lowercase()
            .replace(
                Regex("[^\\p{L}\\p{N}\\s]"),
                " "
            )
            .split(Regex("\\s+"))
            .map { it.trim() }
            .filter {
                it.length >= 2 &&
                        !isCommonWord(it)
            }
            .toSet()
    }

    // =========================================================
    // COMMON VIETNAMESE WORDS
    // =========================================================

    private fun isCommonWord(
        word: String
    ): Boolean {

        return word in setOf(

            // -------------------------------------------------
            // Đại từ / từ nối
            // -------------------------------------------------

            "em",
            "bạn",
            "con",
            "học",
            "sinh",
            "hãy",
            "cho",
            "biết",
            "nêu",
            "trình",
            "bày",
            "giải",
            "thích",
            "một",
            "những",
            "các",
            "của",
            "và",
            "hoặc",
            "hay",
            "với",
            "trong",
            "ngoài",
            "trên",
            "dưới",
            "đến",
            "từ",
            "để",
            "là",
            "có",
            "được",
            "thì",
            "khi",
            "nào",
            "này",
            "đó",
            "như",
            "thế",
            "nào",
            "tại",
            "sao",
            "vì",
            "theo",
            "về",
            "cho",
            "mỗi",
            "một",

            // -------------------------------------------------
            // Câu hỏi
            // -------------------------------------------------

            "gì",
            "ai",
            "đâu",
            "bao",
            "nhiêu",

            // -------------------------------------------------
            // English common words
            // -------------------------------------------------

            "the",
            "a",
            "an",
            "is",
            "are",
            "what",
            "why",
            "how",
            "when",
            "where",
            "and",
            "or",
            "of",
            "to",
            "in",
            "on"
        )
    }

    // =========================================================
    // NORMALIZE
    // =========================================================

    private fun normalizeQuestion(
        text: String
    ): String {

        return text
            .lowercase()
            .replace(
                Regex("\\s+"),
                " "
            )
            .trim()
            .replace(
                Regex("[.!?,;:]+$"),
                ""
            )
    }
}
