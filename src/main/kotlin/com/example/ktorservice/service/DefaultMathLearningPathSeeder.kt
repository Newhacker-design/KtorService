package com.example.ktorservice.service

import com.example.ktorservice.database.table.LearningPathsTable
import com.example.ktorservice.database.table.LearningStepsTable
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

/** Creates baseline math paths once, without replacing paths maintained by admins. */
object DefaultMathLearningPathSeeder {
    private data class StepSeed(
        val title: String,
        val skill: String,
        val description: String
    )

    private val stepsByGrade = mapOf(
        1 to listOf("Số trong phạm vi 100" to "Đọc, viết, so sánh số" , "Cộng trừ cơ bản" to "Cộng trừ trong phạm vi phù hợp", "Hình và đo lường" to "Nhận biết hình, độ dài, thời gian", "Toán có lời văn" to "Chọn phép tính giải tình huống"),
        2 to listOf("Số tự nhiên đến 1000" to "Đọc, viết, so sánh và cấu tạo số", "Cộng trừ có nhớ" to "Thực hiện phép cộng, trừ", "Bảng nhân chia" to "Vận dụng bảng nhân và chia", "Hình học và đo lường" to "Nhận biết hình, đo độ dài, khối lượng, thời gian", "Toán có lời văn" to "Giải toán một bước"),
        3 to listOf("Số và phép tính" to "Nhân chia, biểu thức và tìm thành phần chưa biết", "Phân số cơ bản" to "Nhận biết và so sánh phân số đơn giản", "Đại lượng và đo lường" to "Đổi đơn vị và giải toán đo lường", "Hình học" to "Chu vi, diện tích hình cơ bản", "Giải toán" to "Giải bài toán nhiều bước"),
        4 to listOf("Phân số" to "Nhận biết, rút gọn và quy đồng phân số", "So sánh phân số" to "So sánh phân số và vận dụng", "Cộng trừ phân số" to "Thực hiện phép cộng, trừ phân số", "Nhân chia phân số" to "Thực hiện phép nhân, chia phân số", "Số tự nhiên và phép tính" to "Tính toán với số tự nhiên", "Hình học và đo lường" to "Góc, hình và đơn vị đo", "Toán thực tế" to "Vận dụng phân số giải tình huống"),
        5 to listOf("Số thập phân" to "Đọc, viết, so sánh số thập phân", "Phép tính số thập phân" to "Cộng, trừ, nhân, chia số thập phân", "Tỉ số và phần trăm" to "Giải bài toán tỉ số, phần trăm", "Đại lượng và đo lường" to "Đổi đơn vị và vận dụng", "Hình học" to "Diện tích, thể tích hình cơ bản", "Toán thực tế" to "Giải bài toán tổng hợp"),
        6 to listOf("Số tự nhiên và tính chia hết" to "Ước, bội và dấu hiệu chia hết", "Số nguyên" to "So sánh và tính toán với số nguyên", "Phân số" to "Tính toán và giải toán với phân số", "Số thập phân" to "Thực hiện phép tính với số thập phân", "Hình học cơ bản" to "Đoạn thẳng, góc và hình phẳng", "Thống kê và xác suất" to "Đọc dữ liệu và xác suất đơn giản", "Bài toán thực tế" to "Mô hình hóa và giải quyết vấn đề"),
        7 to listOf("Số hữu tỉ" to "Tính toán với số hữu tỉ", "Tỉ lệ thức và đại lượng tỉ lệ" to "Lập và giải tỉ lệ thức", "Biểu thức đại số" to "Thu gọn và tính giá trị biểu thức", "Đa thức một biến" to "Cộng trừ và sắp xếp đa thức", "Hình học" to "Góc, tam giác và các quan hệ hình học", "Thống kê và xác suất" to "Phân tích dữ liệu và xác suất", "Bài toán vận dụng" to "Kết hợp đại số và hình học"),
        8 to listOf("Đa thức nhiều biến" to "Thu gọn và tính toán đa thức", "Hằng đẳng thức" to "Khai triển và vận dụng hằng đẳng thức", "Phân tích đa thức" to "Phân tích đa thức thành nhân tử", "Phân thức đại số" to "Rút gọn và tính toán phân thức", "Hàm số và đồ thị" to "Đọc và biểu diễn đồ thị", "Tứ giác và định lý Pythagore" to "Giải bài toán hình học", "Thống kê và xác suất" to "Thu thập, phân tích dữ liệu"),
        9 to listOf("Căn thức" to "Rút gọn biểu thức chứa căn", "Hàm số bậc nhất" to "Đồ thị và bài toán liên quan", "Hệ phương trình" to "Giải và lập hệ phương trình", "Phương trình bậc hai" to "Giải và biện luận phương trình", "Hệ thức lượng" to "Vận dụng trong tam giác vuông", "Đường tròn" to "Góc, tiếp tuyến và độ dài", "Thống kê và xác suất" to "Phân tích dữ liệu và xác suất"),
        10 to listOf("Mệnh đề và tập hợp" to "Các phép toán tập hợp", "Hàm số" to "Khảo sát cơ bản và đồ thị", "Phương trình và bất phương trình" to "Giải và biện luận", "Vectơ" to "Phép toán vectơ trong mặt phẳng", "Hệ thức lượng tam giác" to "Giải tam giác", "Thống kê và xác suất" to "Mô tả dữ liệu và xác suất", "Tọa độ phẳng" to "Tọa độ điểm và đường thẳng"),
        11 to listOf("Hàm số lượng giác" to "Giá trị và phương trình lượng giác", "Dãy số và cấp số" to "Cấp số cộng, cấp số nhân", "Mũ và logarit" to "Biến đổi và giải phương trình", "Đạo hàm" to "Tính đạo hàm và ứng dụng", "Hình học không gian" to "Quan hệ song song, vuông góc", "Xác suất" to "Quy tắc đếm và xác suất", "Ôn tập vận dụng" to "Kết hợp kiến thức theo chủ đề"),
        12 to listOf("Ứng dụng đạo hàm" to "Đơn điệu, cực trị, tiệm cận và tối ưu", "Hàm số mũ và logarit" to "Giải phương trình, bất phương trình", "Nguyên hàm và tích phân" to "Tính tích phân và ứng dụng", "Vectơ và tọa độ không gian" to "Phương trình đường thẳng, mặt phẳng", "Hình học Oxyz" to "Tính góc, khoảng cách và mặt cầu", "Xác suất và thống kê" to "Phân tích số liệu và xác suất", "Luyện tập tổng hợp" to "Giải bài toán theo cấu trúc kiểm tra")
    ).mapValues { (_, entries) -> entries.map { (title, skill) -> StepSeed(title, skill, "Rèn luyện kiến thức: $skill.") } }

    // The existing grade 4 path predates this seeder and focuses on fractions.
    // Append the missing strands without changing existing step IDs or progress.
    private val additionalGradeFourSteps = listOf(
        StepSeed("Số tự nhiên và cấu tạo số", "Đọc, viết, phân tích và so sánh số tự nhiên", "Đọc, viết, phân tích cấu tạo và so sánh các số tự nhiên trong phạm vi chương trình lớp 4."),
        StepSeed("Các phép tính với số tự nhiên", "Cộng, trừ, nhân, chia số tự nhiên", "Thực hiện phép tính, tính giá trị biểu thức và tìm thành phần chưa biết."),
        StepSeed("Giải toán có lời văn", "Giải bài toán nhiều bước và toán tìm hai số", "Lựa chọn phép tính phù hợp để giải bài toán thực tế nhiều bước."),
        StepSeed("Đại lượng và đo lường", "Đổi và tính toán với đơn vị đo", "Vận dụng các đơn vị đo khối lượng, thời gian, độ dài và diện tích."),
        StepSeed("Hình học", "Nhận biết góc, đường thẳng và tính diện tích", "Nhận biết góc, hai đường thẳng vuông góc hoặc song song; tính diện tích hình đã học."),
        StepSeed("Thống kê và biểu đồ", "Đọc, mô tả và giải quyết vấn đề từ dữ liệu", "Đọc bảng số liệu và biểu đồ, trả lời câu hỏi dựa trên dữ liệu.")
    )

    fun seedIfMissing() = transaction {
        for (grade in 1..12) {
            val path = LearningPathsTable.selectAll()
                .where { LearningPathsTable.grade eq grade }
                .firstOrNull { it[LearningPathsTable.subject].trim().equals("MATH", ignoreCase = true) }

            val pathId = path?.get(LearningPathsTable.id) ?: run {
                val inserted = LearningPathsTable.insert {
                    it[LearningPathsTable.grade] = grade
                    it[LearningPathsTable.subject] = "MATH"
                    it[LearningPathsTable.name] = "Lộ trình Toán lớp $grade"
                    it[LearningPathsTable.description] = "Lộ trình kiến thức Toán cốt lõi lớp $grade."
                    it[LearningPathsTable.createdAt] = System.currentTimeMillis()
                }
                inserted[LearningPathsTable.id]
            }

            val hasSteps = LearningStepsTable.selectAll()
                .where { LearningStepsTable.pathId eq pathId }
                .any()
            if (!hasSteps) {
                stepsByGrade.getValue(grade).forEachIndexed { index, seed ->
                    LearningStepsTable.insert {
                        it[LearningStepsTable.pathId] = pathId
                        it[LearningStepsTable.stepOrder] = index + 1
                        it[LearningStepsTable.title] = seed.title
                        it[LearningStepsTable.skill] = seed.skill
                        it[LearningStepsTable.description] = seed.description
                        it[LearningStepsTable.prerequisiteStepId] = null
                        it[LearningStepsTable.createdAt] = System.currentTimeMillis()
                    }
                }
            }

            if (grade == 4) {
                var nextOrder = (LearningStepsTable.selectAll()
                    .where { LearningStepsTable.pathId eq pathId }
                    .maxOfOrNull { it[LearningStepsTable.stepOrder] } ?: 0) + 1
                val existingTitles = LearningStepsTable.selectAll()
                    .where { LearningStepsTable.pathId eq pathId }
                    .map { it[LearningStepsTable.title].trim().lowercase() }
                    .toSet()
                additionalGradeFourSteps
                    .filterNot { it.title.trim().lowercase() in existingTitles }
                    .forEach { seed ->
                        LearningStepsTable.insert {
                            it[LearningStepsTable.pathId] = pathId
                            it[LearningStepsTable.stepOrder] = nextOrder++
                            it[LearningStepsTable.title] = seed.title
                            it[LearningStepsTable.skill] = seed.skill
                            it[LearningStepsTable.description] = seed.description
                            it[LearningStepsTable.prerequisiteStepId] = null
                            it[LearningStepsTable.createdAt] = System.currentTimeMillis()
                        }
                    }
            }
        }
    }
}
