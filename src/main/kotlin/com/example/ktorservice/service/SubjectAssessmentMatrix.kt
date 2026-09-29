package com.example.ktorservice.service

import java.util.Locale

/** Curriculum-aligned topic blueprints; weights are app sampling targets, not MOET-mandated ratios. */
object SubjectAssessmentMatrix {
    enum class Level { RECOGNITION, UNDERSTANDING, APPLICATION, ADVANCED_APPLICATION }

    data class Cell(val topic: String, val keywords: List<String>, val weight: Int)
    data class Blueprint(
        val gradeRange: IntRange,
        val strands: List<Cell>,
        val levels: Map<Level, Int>
    )

    private val standardLevels = mapOf(
        Level.RECOGNITION to 25,
        Level.UNDERSTANDING to 35,
        Level.APPLICATION to 30,
        Level.ADVANCED_APPLICATION to 10
    )

    private fun cells(vararg values: Triple<String, List<String>, Int>) = values.map { Cell(it.first, it.second, it.third) }
    private fun blueprint(range: IntRange, vararg values: Triple<String, List<String>, Int>) =
        Blueprint(range, cells(*values), standardLevels)

    private val matrices = mapOf(
        "literature" to listOf(
            blueprint(1..5,
                Triple("Đọc hiểu văn bản", listOf("đọc", "văn bản", "câu chuyện", "thơ"), 40),
                Triple("Tiếng Việt", listOf("từ", "câu", "dấu", "ngữ pháp"), 30),
                Triple("Viết", listOf("đoạn văn", "bài văn", "viết", "mở bài"), 30)),
            blueprint(6..9,
                Triple("Đọc hiểu", listOf("văn bản", "người kể", "nhân vật", "thơ"), 40),
                Triple("Tiếng Việt", listOf("từ", "câu", "liên kết", "biện pháp"), 25),
                Triple("Viết và tạo lập văn bản", listOf("nghị luận", "đoạn", "bài", "luận điểm"), 35)),
            blueprint(10..12,
                Triple("Đọc hiểu văn bản theo thể loại", listOf("tác phẩm", "văn bản", "nhân vật", "thơ"), 40),
                Triple("Tiếng Việt", listOf("ngôn ngữ", "từ", "câu", "biện pháp"), 20),
                Triple("Viết nghị luận và văn bản", listOf("nghị luận", "luận điểm", "dẫn chứng", "viết"), 40))
        ),
        "english" to listOf(
            blueprint(3..5,
                Triple("Ngữ âm và từ vựng theo chủ điểm", listOf("word", "vocabulary", "school", "food"), 35),
                Triple("Ngữ pháp và mẫu câu", listOf("verb", "plural", "article", "sentence"), 35),
                Triple("Giao tiếp và đọc hiểu ngắn", listOf("choose", "complete", "meaning", "read"), 30)),
            blueprint(6..9,
                Triple("Chủ điểm giao tiếp và từ vựng", listOf("word", "place", "science", "interested"), 30),
                Triple("Ngữ âm và ngữ pháp", listOf("tense", "form", "preposition", "conditional"), 35),
                Triple("Đọc hiểu và giao tiếp", listOf("complete", "reported", "relative", "meaning"), 35)),
            blueprint(10..12,
                Triple("Chủ đề và vốn từ", listOf("word", "experiment", "scientist", "results"), 30),
                Triple("Ngữ âm và cấu trúc ngôn ngữ", listOf("passive", "conditional", "relative", "reported"), 35),
                Triple("Đọc hiểu và sử dụng tiếng Anh", listOf("sentence", "meaning", "results", "purpose"), 35))
        ),
        "biology" to listOf(
            blueprint(1..5,
                Triple("Tự nhiên và xã hội: cơ thể, sinh vật và môi trường", listOf("cây", "con vật", "cơ thể", "môi trường"), 100)),
            blueprint(6..9,
                Triple("Khoa học tự nhiên: chất sống và cơ thể sinh vật", listOf("tế bào", "cơ thể", "quang hợp", "hô hấp"), 45),
                Triple("Khoa học tự nhiên: đa dạng và sinh thái", listOf("sinh thái", "chuỗi thức ăn", "sinh vật", "môi trường"), 35),
                Triple("Khoa học tự nhiên: sức khỏe", listOf("sức khỏe", "dinh dưỡng", "vệ sinh", "cơ quan"), 20)),
            blueprint(10..12,
                Triple("Sinh học tế bào", listOf("tế bào", "bào quan", "hô hấp tế bào"), 30),
                Triple("Sinh học cơ thể", listOf("cơ thể", "dinh dưỡng", "quang hợp", "sinh sản"), 35),
                Triple("Di truyền, tiến hóa và sinh thái", listOf("di truyền", "tiến hóa", "hệ sinh thái", "chuỗi thức ăn"), 35))
        ),
        "chemistry" to listOf(
            blueprint(1..5, Triple("Tự nhiên và xã hội: vật liệu và sự biến đổi", listOf("nước", "vật liệu", "chất", "tan"), 100)),
            blueprint(6..9,
                Triple("Khoa học tự nhiên: chất và sự biến đổi", listOf("chất", "phản ứng", "dung dịch", "hỗn hợp"), 45),
                Triple("Khoa học tự nhiên: nguyên tử và phân tử", listOf("nguyên tử", "phân tử", "nguyên tố", "liên kết"), 35),
                Triple("Khoa học tự nhiên: ứng dụng hóa học", listOf("axit", "base", "muối", "vật liệu"), 20)),
            blueprint(10..12,
                Triple("Cấu tạo chất và liên kết hóa học", listOf("nguyên tử", "liên kết", "electron", "phân tử"), 30),
                Triple("Phản ứng và các hợp chất", listOf("phản ứng", "axit", "base", "muối"), 40),
                Triple("Hóa học hữu cơ và ứng dụng", listOf("hữu cơ", "carbon", "hợp chất", "ứng dụng"), 30))
        ),
        "physics" to listOf(
            blueprint(1..5, Triple("Tự nhiên và xã hội: lực, ánh sáng, âm thanh và năng lượng", listOf("ánh sáng", "âm thanh", "lực", "năng lượng"), 100)),
            blueprint(6..9,
                Triple("Khoa học tự nhiên: chuyển động và lực", listOf("chuyển động", "tốc độ", "lực", "gia tốc"), 35),
                Triple("Khoa học tự nhiên: năng lượng và sóng", listOf("năng lượng", "sóng", "ánh sáng", "nhiệt"), 35),
                Triple("Khoa học tự nhiên: điện và từ", listOf("điện", "điện trở", "mạch", "dòng điện"), 30)),
            blueprint(10..12,
                Triple("Động học và động lực học", listOf("tốc độ", "gia tốc", "newton", "lực"), 35),
                Triple("Năng lượng, dao động và sóng", listOf("động năng", "sóng", "dao động", "tần số"), 35),
                Triple("Điện, từ và quang học", listOf("điện", "điện trở", "thấu kính", "hiệu điện thế"), 30))
        ),
        "giao_duc_gioi_tinh" to listOf(
            blueprint(1..5,
                Triple("An toàn cá nhân và ranh giới cơ thể", listOf("chạm", "cơ thể", "ranh giới", "không"), 50),
                Triple("An toàn số và tìm kiếm hỗ trợ", listOf("mạng", "mật khẩu", "người lớn", "bắt nạt"), 50)),
            blueprint(6..9,
                Triple("Tuổi dậy thì và sức khỏe", listOf("dậy thì", "cơ thể", "sức khỏe", "phát triển"), 40),
                Triple("Đồng thuận và quan hệ tôn trọng", listOf("đồng thuận", "tự nguyện", "tôn trọng", "từ chối"), 30),
                Triple("An toàn, quyền riêng tư và hỗ trợ", listOf("ảnh", "mạng", "đe dọa", "hỗ trợ"), 30)),
            blueprint(10..12,
                Triple("Sức khỏe sinh sản và chăm sóc sức khỏe", listOf("sinh sản", "sức khỏe", "y tế", "dậy thì"), 35),
                Triple("Đồng thuận, quyền và quan hệ lành mạnh", listOf("đồng thuận", "quyền", "quan hệ", "tôn trọng"), 35),
                Triple("An toàn số và tiếp cận hỗ trợ", listOf("ảnh", "mạng", "riêng tư", "hỗ trợ"), 30))
        )
    )

    fun forSubject(subject: String, grade: Int): Blueprint? {
        val key = subject.trim().lowercase(Locale.ROOT)
        return matrices[key]?.firstOrNull { grade in it.gradeRange }
    }

    fun classify(subject: String, grade: Int, prompt: String, objective: String, bankIndex: Int): Pair<String, Level>? {
        val matrix = forSubject(subject, grade) ?: return null
        val text = "$prompt $objective".lowercase(Locale.ROOT)
        val slot = ((bankIndex * 37) % 100).let { if (it < 0) it + 100 else it }
        val topic = matrix.strands.maxByOrNull { cell -> cell.keywords.count { text.contains(it) } }
            ?.takeIf { cell -> cell.keywords.any(text::contains) }
            ?: weightedPick(matrix.strands, slot) { it.weight }
        val level = when {
            listOf("áp dụng", "vận dụng", "tính ", "giải quyết", "liên hệ", "phân tích", "so sánh").any(text::contains) -> Level.APPLICATION
            listOf("vì sao", "vai trò", "ý nghĩa", "hiểu", "giải thích", "nguyên nhân").any(text::contains) -> Level.UNDERSTANDING
            listOf("nhận biết", "nhận diện", "xác định", "nêu được").any(text::contains) -> Level.RECOGNITION
            else -> weightedPick(matrix.levels.entries.toList(), slot) { it.value }.key
        }
        return topic.topic to level
    }

    private fun <T> weightedPick(values: List<T>, slot: Int, weight: (T) -> Int): T {
        var remainder = slot
        for (value in values) {
            remainder -= weight(value)
            if (remainder < 0) return value
        }
        return values.last()
    }
}
