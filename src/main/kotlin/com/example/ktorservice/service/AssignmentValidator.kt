package com.example.ktorservice.service

import com.example.ktorservice.model.AssignmentQuestion
import com.example.ktorservice.model.AssignmentAnswerKey

object AssignmentValidator {

    data class ValidationResult(
        val valid: Boolean,
        val errors: List<String> = emptyList()
    )

    fun validate(
        title: String,
        questions: List<AIService.GeneratedQuestion>,
        answerKey: List<AIService.GeneratedAnswer>,
        gradingGuide: String,
        totalScore: Double
    ): ValidationResult {

        val errors = mutableListOf<String>()

        // =========================================================
        // TITLE
        // =========================================================

        if (title.isBlank()) {
            errors += "Title is empty"
        }

        if (title.length < 5) {
            errors += "Title is too short"
        }

        // =========================================================
        // QUESTIONS
        // =========================================================

        if (questions.size != 3) {
            errors += "Assignment must contain exactly 3 questions, found ${questions.size}"
        }

        val questionIds = questions.map { it.id }

        if (questionIds.distinct().size != questionIds.size) {
            errors += "Duplicate question IDs detected: $questionIds"
        }

        if (questionIds != listOf(1, 2, 3)) {
            errors += "Question IDs must be exactly [1, 2, 3], found $questionIds"
        }

        // =========================================================
        // QUESTION CONTENT
        // =========================================================

        questions.forEach { question ->

            if (question.question.isBlank()) {
                errors += "Question ${question.id} is empty"
            }

            if (question.question.trim().length < 10) {
                errors += "Question ${question.id} is too short"
            }
            // -----------------------------------------------------
            // LEARNING OBJECTIVE
            // -----------------------------------------------------

            if (question.learningObjective.isBlank()) {
                errors +=
                    "Question ${question.id} has empty learning objective"
            }

            if (question.learningObjective.trim().length < 10) {
                errors +=
                    "Question ${question.id} learning objective is too short"
            }

            if (question.points <= 0) {
                errors += "Question ${question.id} has invalid points: ${question.points}"
            }

            // -----------------------------------------------------
            // Không cho phép loại câu hỏi mà ControlReceiver
            // hiện tại chưa hỗ trợ.
            // -----------------------------------------------------

            when (question.answerType) {

                AIService.AnswerType.DRAWING -> {
                    errors +=
                        "Question ${question.id} uses DRAWING, " +
                                "but ControlReceiver does not support DRAWING yet"
                }

                AIService.AnswerType.MIXED -> {
                    errors +=
                        "Question ${question.id} uses MIXED, " +
                                "but ControlReceiver does not support MIXED yet"
                }

                else -> Unit
            }

            // -----------------------------------------------------
            // Kiểm tra answerType / gradingMethod
            // -----------------------------------------------------

            when (question.answerType) {

                AIService.AnswerType.TEXT -> {
                    if (
                        question.gradingMethod != AIService.GradingMethod.EXACT &&
                        question.gradingMethod != AIService.GradingMethod.AI_TEXT
                    ) {
                        errors +=
                            "Question ${question.id}: TEXT requires EXACT or AI_TEXT"
                    }
                }

                AIService.AnswerType.HANDWRITING -> {
                    if (
                        question.gradingMethod != AIService.GradingMethod.OCR_AI
                    ) {
                        errors +=
                            "Question ${question.id}: HANDWRITING requires OCR_AI"
                    }
                }

                AIService.AnswerType.SPEECH_TO_TEXT -> {
                    if (
                        question.gradingMethod != AIService.GradingMethod.EXACT &&
                        question.gradingMethod != AIService.GradingMethod.AI_TEXT
                    ) {
                        errors +=
                            "Question ${question.id}: SPEECH_TO_TEXT requires EXACT or AI_TEXT"
                    }
                }

                else -> Unit
            }

            // -----------------------------------------------------
            // Phát hiện text bất thường
            // -----------------------------------------------------

            val text = question.question.trim()

            if (text.contains("undefined", ignoreCase = true)) {
                errors += "Question ${question.id} contains 'undefined'"
            }

            if (text.contains("null null", ignoreCase = true)) {
                errors += "Question ${question.id} contains 'null null'"
            }

            if (text.contains("lorem ipsum", ignoreCase = true)) {
                errors += "Question ${question.id} contains placeholder text"
            }

            if (text.contains("asdf", ignoreCase = true)) {
                errors += "Question ${question.id} contains suspicious text"
            }

            if (text.contains("qwerty", ignoreCase = true)) {
                errors += "Question ${question.id} contains suspicious text"
            }

            // Một từ dài bất thường thường là lỗi AI
            val suspiciousLongWord =
                text.split(Regex("\\s+"))
                    .any { it.length > 40 }

            if (suspiciousLongWord) {
                errors +=
                    "Question ${question.id} contains an abnormally long word"
            }

            // Ký tự lặp quá nhiều lần
            if (Regex("(.)\\1{5,}").containsMatchIn(text)) {
                errors +=
                    "Question ${question.id} contains excessive repeated characters"
            }
        }

        // =========================================================
        // DUPLICATE QUESTIONS
        // =========================================================

        val normalizedQuestions =
            questions.map {
                normalizeQuestion(it.question)
            }

        if (normalizedQuestions.distinct().size != normalizedQuestions.size) {
            errors += "Duplicate questions detected"
        }

        // =========================================================
        // ANSWER KEY
        // =========================================================

        if (answerKey.size != 3) {
            errors +=
                "Answer key must contain exactly 3 answers, found ${answerKey.size}"
        }

        val answerIds = answerKey.map { it.id }

        if (answerIds.distinct().size != answerIds.size) {
            errors += "Duplicate answer IDs detected: $answerIds"
        }

        if (answerIds != listOf(1, 2, 3)) {
            errors +=
                "Answer IDs must be exactly [1, 2, 3], found $answerIds"
        }

        answerKey.forEach { answer ->

            if (answer.answer.isBlank()) {
                errors +=
                    "Answer ${answer.id} is empty"
            }

            if (answer.answer.trim().length < 1) {
                errors +=
                    "Answer ${answer.id} is invalid"
            }

            val text = answer.answer.trim()

            if (text.contains("undefined", ignoreCase = true)) {
                errors +=
                    "Answer ${answer.id} contains 'undefined'"
            }

            if (text.contains("lorem ipsum", ignoreCase = true)) {
                errors +=
                    "Answer ${answer.id} contains placeholder text"
            }
        }

        // =========================================================
        // QUESTION / ANSWER IDs MUST MATCH
        // =========================================================

        if (questionIds.toSet() != answerIds.toSet()) {
            errors +=
                "Question IDs and answer IDs do not match"
        }

        // =========================================================
        // GRADING GUIDE
        // =========================================================

        if (gradingGuide.isBlank()) {
            errors += "Grading guide is empty"
        }

        if (gradingGuide.trim().length < 10) {
            errors += "Grading guide is too short"
        }

        // =========================================================
        // SCORE
        // =========================================================

        val calculatedScore =
            questions.sumOf { it.points }

        if (kotlin.math.abs(calculatedScore - 10.0) > 0.001) {
            errors +=
                "Question points must total 10, actual=$calculatedScore"
        }

        if (kotlin.math.abs(totalScore - 10.0) > 0.001) {
            errors +=
                "totalScore must be 10, actual=$totalScore"
        }

        // =========================================================
        // RESULT
        // =========================================================

        return ValidationResult(
            valid = errors.isEmpty(),
            errors = errors
        )
    }

    private fun normalizeQuestion(text: String): String {
        return text
            .lowercase()
            .replace(Regex("\\s+"), " ")
            .trim()
            .replace(Regex("[.!?,;:]+$"), "")
    }
}