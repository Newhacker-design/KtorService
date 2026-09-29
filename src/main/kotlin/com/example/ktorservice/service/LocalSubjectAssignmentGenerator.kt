package com.example.ktorservice.service

import kotlin.random.Random
import java.util.Locale

/** Builds objective assignments whose answer keys can be graded on the Receiver without AI. */
object LocalSubjectAssignmentGenerator {
    private data class Item(
        val prompt: String,
        val objective: String,
        val choices: List<String>,
        val answer: Int
    )

    private data class SetOfItems(
        val primary: List<Item>,
        val secondary: List<Item>,
        val upperSecondary: List<Item>
    )

    private val banks = mapOf(
        "literature" to SetOfItems(
            primary = listOf(
                item("Trong câu 'Mặt trời thức dậy sau rặng tre', biện pháp nào được dùng?", "Nhận biết nhân hóa trong câu văn.", "So sánh", "Nhân hóa", "Điệp ngữ", "Nói quá", 1),
                item("Từ nào sau đây là từ chỉ đặc điểm?", "Phân biệt từ chỉ đặc điểm trong câu.", "chạy", "xanh", "bàn", "học", 1),
                item("Một đoạn văn thường gồm nhiều câu cùng tập trung vào điều gì?", "Nhận biết sự thống nhất chủ đề của đoạn văn.", "Một chủ đề", "Nhiều tiêu đề", "Một dấu câu", "Một nhân vật", 0),
                item("Từ nào sau đây là danh từ chỉ sự vật?", "Nhận diện danh từ chỉ người, vật hoặc sự việc.", "dịu dàng", "quyển sách", "đang đọc", "xanh biếc", 1),
                item("Trong câu 'Bạn Lan đọc sách', từ nào là hoạt động?", "Xác định từ chỉ hoạt động trong câu đơn.", "Bạn", "Lan", "đọc", "sách", 2),
                item("Dấu chấm thường được đặt ở đâu?", "Sử dụng dấu chấm để kết thúc câu kể.", "Cuối câu kể", "Giữa hai tiếng", "Trước tên riêng", "Đầu đoạn văn", 0),
                item("Từ nào gần nghĩa nhất với 'chăm chỉ'?", "Nhận biết từ đồng nghĩa gần gũi.", "siêng năng", "lười biếng", "ồn ào", "vội vàng", 0),
                item("Câu nào sau đây là câu hỏi?", "Phân biệt câu hỏi qua mục đích giao tiếp.", "Em đang đọc sách.", "Bạn có khỏe không?", "Trời hôm nay đẹp.", "Hãy mở cửa sổ.", 1),
                item("Trong câu 'Những bông hoa nở rực rỡ', từ nào bổ sung đặc điểm?", "Nhận diện từ ngữ miêu tả đặc điểm.", "Những", "bông hoa", "nở", "rực rỡ", 3),
                item("Một bài thơ thường được chia thành những đơn vị nào?", "Nhận biết cách tổ chức cơ bản của bài thơ.", "Khổ thơ và dòng thơ", "Chương và mục lục", "Câu hỏi và đáp án", "Mở bài và kết bài", 0)
            ),
            secondary = listOf(
                item("Câu 'Tiếng suối trong như tiếng hát xa' sử dụng biện pháp nào?", "Nhận biết phép so sánh trong thơ.", "Ẩn dụ", "So sánh", "Nhân hóa", "Hoán dụ", 1),
                item("Người kể chuyện xưng 'tôi' thường kể câu chuyện từ ngôi nào?", "Xác định ngôi kể thứ nhất.", "Ngôi thứ nhất", "Ngôi thứ hai", "Ngôi thứ ba", "Không có ngôi kể", 0),
                item("Trong văn bản nghị luận, luận cứ chủ yếu dùng để làm gì?", "Hiểu vai trò của luận cứ trong lập luận.", "Làm rõ và hỗ trợ luận điểm", "Thay thế nhan đề", "Tạo vần cho câu", "Miêu tả bối cảnh", 0),
                item("Phần mở đầu của một bài văn thường có nhiệm vụ gì?", "Nhận biết vai trò giới thiệu vấn đề của mở bài.", "Giới thiệu vấn đề hoặc câu chuyện", "Liệt kê tài liệu tham khảo", "Nêu toàn bộ đáp án", "Thay cho các đoạn thân bài", 0),
                item("Từ 'nhưng' thường biểu thị quan hệ nào giữa hai ý?", "Nhận biết quan hệ tương phản giữa các vế câu.", "Tương phản", "Nguyên nhân", "Thời gian", "Mục đích", 0),
                item("Chi tiết nào thường giúp người đọc nhận ra tính cách nhân vật?", "Dùng chi tiết trong văn bản để suy luận về nhân vật.", "Hành động và lời nói", "Số trang của sách", "Cỡ chữ của văn bản", "Tên nhà xuất bản", 0)
            ),
            upperSecondary = listOf(
                item("Một hình ảnh được dùng để gợi một ý nghĩa khác có nét tương đồng thường là gì?", "Phân biệt ẩn dụ với các biện pháp tu từ khác.", "Ẩn dụ", "Liệt kê", "Điệp âm", "Nói giảm", 0),
                item("Khi phân tích nhân vật, căn cứ phù hợp nhất là gì?", "Dựa vào chi tiết văn bản để nhận xét nhân vật.", "Hành động, lời nói và suy nghĩ trong văn bản", "Chỉ dựa vào tên tác phẩm", "Số trang của truyện", "Sở thích của người đọc", 0),
                item("Vai trò chính của từ nối 'tuy nhiên' trong đoạn văn là gì?", "Nhận diện quan hệ tương phản giữa các ý.", "Bổ sung ví dụ", "Biểu thị tương phản", "Nêu nguyên nhân", "Kết thúc câu chuyện", 1),
                item("Khi hai đoạn văn cùng chủ đề nhưng dùng dẫn chứng khác nhau, điều nào cần được giữ nhất quán?", "Đánh giá tính mạch lạc và thống nhất của lập luận.", "Luận điểm chính", "Số câu mỗi đoạn", "Vị trí dấu phẩy", "Độ dài từng từ", 0),
                item("Một hình ảnh lặp lại nhiều lần trong tác phẩm thường có thể góp phần tạo nên điều gì?", "Hiểu tác dụng liên kết và gợi chủ đề của hình ảnh lặp.", "Mạch liên kết và ý nghĩa chủ đề", "Danh sách nhân vật mới", "Dấu câu bắt buộc", "Thời gian xuất bản", 0),
                item("Khi đánh giá một nhận định về tác phẩm, căn cứ thuyết phục nhất là gì?", "Chọn bằng chứng phù hợp khi đọc hiểu văn bản.", "Chi tiết và ngôn ngữ trong tác phẩm", "Ý kiến không giải thích", "Tên người đọc", "Số lần tái bản", 0)
            )
        ),
        "english" to SetOfItems(
            primary = listOf(
                item("Choose the correct word: She ___ to school every day.", "Use the present simple form with a singular subject.", "go", "goes", "going", "went", 1),
                item("What is the plural form of 'child'?", "Form an irregular plural noun correctly.", "childs", "children", "childes", "childrens", 1),
                item("Choose the correct article: I saw ___ elephant.", "Choose an article before a vowel sound.", "a", "an", "the a", "no article", 1),
                item("Choose the correct pronoun: Mai is my friend. ___ is kind.", "Use a subject pronoun for a female person.", "He", "She", "It", "They", 1),
                item("What is the opposite of 'hot'?", "Recognize a common adjective and its antonym.", "warm", "cold", "high", "long", 1),
                item("Choose the correct form: We ___ students.", "Use the correct form of be with we.", "am", "is", "are", "be", 2),
                item("Which word names a place where students learn?", "Identify basic school-related vocabulary.", "school", "pencil", "teacher's", "quickly", 0),
                item("Choose the correct plural: one box, two ___.", "Form a regular plural ending in x.", "boxs", "boxes", "boxies", "boxen", 1),
                item("Complete the sentence: My birthday is ___ May.", "Use the correct preposition with a month.", "in", "on", "at", "by", 0),
                item("Choose the correct possessive: This is ___ bag.", "Choose a possessive adjective for I.", "my", "me", "mine's", "I", 0)
            ),
            secondary = listOf(
                item("Choose the correct form: They ___ football when it started to rain.", "Use past continuous for an action in progress in the past.", "play", "played", "were playing", "are playing", 2),
                item("Complete the sentence: This book is ___ than that one.", "Form a comparative adjective.", "interesting", "more interesting", "most interesting", "the more interesting", 1),
                item("Choose the correct preposition: She is interested ___ science.", "Use the correct preposition after interested.", "on", "at", "in", "for", 2),
                item("Complete the sentence: I have lived here ___ 2020.", "Use since with a starting point in time.", "for", "since", "during", "until", 1),
                item("Choose the correct word: There isn't ___ milk left.", "Use an uncountable quantifier in a negative sentence.", "many", "much", "few", "several", 1),
                item("Choose the correct form: The children ___ their homework yesterday.", "Use the past simple form of a regular verb.", "finish", "finishes", "finished", "finishing", 2)
            ),
            upperSecondary = listOf(
                item("Choose the correct passive form: People speak English worldwide.", "Convert a present simple sentence to passive voice.", "English is spoken worldwide.", "English was spoken worldwide.", "English speaks worldwide.", "English has speak worldwide.", 0),
                item("Complete the conditional: If water reaches 100°C, it ___.", "Use the zero conditional for a general fact.", "boiled", "will boiled", "boils", "would boil", 2),
                item("Choose the correct relative pronoun: The scientist ___ discovered the element won an award.", "Use a relative pronoun for a person.", "which", "who", "where", "when", 1),
                item("Choose the correct reported speech: He said, 'I am tired.'", "Change a present statement to reported speech.", "He said that he is tired.", "He said that he was tired.", "He says that I was tired.", "He said he tired.", 1),
                item("Complete the sentence: The experiment was repeated ___ the results could be checked.", "Choose a conjunction that expresses purpose.", "so that", "although", "unless", "whereas", 0),
                item("Choose the correct form: By next June, she ___ here for five years.", "Use future perfect continuous for duration up to a future point.", "works", "will work", "will have been working", "has worked", 2)
            )
        ),
        "biology" to SetOfItems(
            primary = listOf(
                item("Bộ phận nào của cây thường hút nước và muối khoáng từ đất?", "Nhận biết chức năng cơ bản của rễ cây.", "Rễ", "Hoa", "Quả", "Lá", 0),
                item("Sinh vật nào sau đây là sinh vật sản xuất trong hệ sinh thái?", "Phân biệt sinh vật sản xuất với sinh vật tiêu thụ.", "Cây xanh", "Thỏ", "Nấm", "Hổ", 0),
                item("Cơ quan nào giúp cá trao đổi khí hòa tan trong nước?", "Nhận biết cơ quan hô hấp của cá.", "Mang", "Phổi", "Da khô", "Vây", 0),
                item("Con vật nào sau đây thường ăn cỏ?", "Phân biệt động vật ăn thực vật.", "Thỏ", "Mèo", "Đại bàng", "Cá mập", 0),
                item("Bộ phận nào của cây thường tạo ra hạt?", "Nhận biết vai trò sinh sản của hoa.", "Hoa", "Rễ", "Thân", "Lá già", 0),
                item("Nước cần thiết với cơ thể người chủ yếu vì lý do nào?", "Hiểu vai trò cơ bản của nước đối với cơ thể.", "Giúp các hoạt động sống diễn ra", "Thay thế mọi thức ăn", "Làm xương biến mất", "Ngăn cơ thể cần không khí", 0),
                item("Môi trường sống của một sinh vật là gì?", "Nhận biết nơi sinh vật sống và nhận các điều kiện cần thiết.", "Nơi sinh vật sống", "Tên của sinh vật", "Thức ăn duy nhất", "Màu sắc cơ thể", 0),
                item("Cơ quan nào giúp con người hít thở?", "Nhận diện cơ quan chính của hệ hô hấp.", "Phổi", "Dạ dày", "Thận", "Xương", 0),
                item("Trong chuỗi thức ăn, con cáo ăn thỏ thì cáo là gì?", "Xác định vai trò sinh vật tiêu thụ trong chuỗi thức ăn.", "Sinh vật tiêu thụ", "Sinh vật sản xuất", "Đất khoáng", "Ánh sáng", 0),
                item("Việc rửa tay bằng xà phòng trước khi ăn giúp ích điều gì?", "Liên hệ vệ sinh cá nhân với phòng ngừa mầm bệnh.", "Giảm mầm bệnh trên tay", "Tăng vi khuẩn có hại", "Thay thế giấc ngủ", "Làm thức ăn chín", 0)
            ),
            secondary = listOf(
                item("Đơn vị cấu trúc và chức năng cơ bản của cơ thể sống là gì?", "Nêu được đơn vị cơ bản của sự sống.", "Mô", "Cơ quan", "Tế bào", "Hệ cơ quan", 2),
                item("Quang hợp ở thực vật sử dụng nguồn năng lượng chủ yếu nào?", "Hiểu vai trò của ánh sáng trong quang hợp.", "Ánh sáng", "Âm thanh", "Nhiệt từ đất", "Từ trường", 0),
                item("Trong chuỗi thức ăn, sinh vật tiêu thụ bậc một thường ăn gì?", "Xác định vị trí của sinh vật tiêu thụ bậc một.", "Sinh vật sản xuất", "Sinh vật phân giải", "Sinh vật tiêu thụ bậc cao", "Đá và nước", 0),
                item("Bào quan nào là nơi diễn ra phần lớn quá trình hô hấp tế bào?", "Liên hệ ti thể với giải phóng năng lượng trong tế bào.", "Ti thể", "Lục lạp", "Không bào", "Thành tế bào", 0),
                item("Ở người, chất dinh dưỡng được hấp thụ chủ yếu tại cơ quan nào?", "Nhận biết vai trò hấp thụ của ruột non.", "Ruột non", "Thực quản", "Khí quản", "Tim", 0),
                item("Thành phần nào của máu vận chuyển phần lớn khí oxygen?", "Liên hệ hồng cầu với vận chuyển oxygen.", "Hồng cầu", "Tiểu cầu", "Huyết tương", "Bạch cầu", 0)
            ),
            upperSecondary = listOf(
                item("Nếu hai alen khác nhau cùng biểu hiện ở kiểu hình dị hợp, đó thường là kiểu di truyền nào?", "Nhận biết hiện tượng đồng trội.", "Đồng trội", "Đột biến gen", "Phân li độc lập", "Nhân đôi ADN", 0),
                item("Quá trình nào tạo ra các giao tử mang một alen của mỗi cặp alen?", "Liên hệ giảm phân với sự phân li của alen.", "Nguyên phân", "Giảm phân", "Thụ tinh", "Hô hấp tế bào", 1),
                item("Trong lưới thức ăn, sự suy giảm mạnh sinh vật sản xuất có thể gây hậu quả nào?", "Phân tích tác động của biến động ở đáy lưới thức ăn.", "Giảm nguồn năng lượng cho các bậc tiêu thụ", "Tăng năng lượng cho mọi bậc", "Không ảnh hưởng sinh vật khác", "Ngừng chu trình nước", 0),
                item("Một alen lặn thường biểu hiện thành kiểu hình khi nào trong trường hợp trội hoàn toàn?", "Vận dụng quy luật trội hoàn toàn vào kiểu gen.", "Ở trạng thái đồng hợp lặn", "Chỉ khi dị hợp", "Trong mọi kiểu gen", "Không bao giờ biểu hiện", 0),
                item("Trong quang hợp, carbon dioxide được cây sử dụng để tạo chất hữu cơ nào?", "Hiểu nguyên liệu vô cơ được chuyển thành carbohydrate.", "Đường", "Protein động vật", "Muối khoáng", "Oxygen", 0),
                item("Quan hệ giữa vật ăn thịt và con mồi có thể góp phần điều hòa điều gì?", "Phân tích tác động của quan hệ sinh thái lên kích thước quần thể.", "Kích thước quần thể", "Độ nghiêng trục Trái Đất", "Số nguyên tố hóa học", "Chu kỳ ngày đêm", 0)
            )
        ),
        "chemistry" to SetOfItems(
            primary = listOf(
                item("Nước đá thuộc thể nào của chất?", "Nhận biết các thể phổ biến của vật chất.", "Rắn", "Lỏng", "Khí", "Plasma", 0),
                item("Chất nào sau đây là một nguyên tố hóa học?", "Phân biệt nguyên tố với hợp chất và hỗn hợp.", "Sắt", "Nước muối", "Không khí", "Đường", 0),
                item("Khi nước lỏng đông thành nước đá, chất nào được tạo thành?", "Nhận biết biến đổi trạng thái không tạo chất mới.", "Nước đá", "Oxi", "Hiđro", "Muối", 0),
                item("Không khí sạch là ví dụ gần đúng của loại chất nào?", "Nhận biết không khí là hỗn hợp nhiều chất khí.", "Hỗn hợp", "Nguyên tố tinh khiết", "Một nguyên tử", "Kim loại", 0),
                item("Đường tan trong nước tạo thành dạng nào?", "Nhận biết dung dịch tạo bởi chất tan và dung môi.", "Dung dịch", "Nguyên tố", "Chất khí", "Kim loại", 0),
                item("Vật liệu nào thường bị nam châm hút?", "Nhận biết tính chất từ của một số kim loại.", "Sắt", "Gỗ", "Nhựa", "Thủy tinh", 0),
                item("Khi đun nước, nước lỏng chuyển thành hơi là quá trình gì?", "Nhận diện sự bay hơi khi chất lỏng nhận nhiệt.", "Bay hơi", "Đông đặc", "Ngưng tụ", "Nóng chảy", 0),
                item("Muối ăn thường có vị nào?", "Nhận biết một tính chất cảm quan quen thuộc của muối ăn.", "Mặn", "Ngọt", "Đắng", "Chua", 0),
                item("Một chất được tạo từ hai nguyên tố hóa học trở lên liên kết với nhau gọi là gì?", "Phân biệt hợp chất với nguyên tố hóa học.", "Hợp chất", "Đơn chất", "Hỗn hợp cơ học", "Dung môi", 0),
                item("Dụng cụ nào dùng để đo thể tích chất lỏng trong phòng học?", "Chọn dụng cụ đo thể tích chất lỏng.", "Ống đong", "Nhiệt kế", "Nam châm", "Đồng hồ bấm giây", 0)
            ),
            secondary = listOf(
                item("Hạt nào mang điện tích âm trong nguyên tử?", "Nhận biết các hạt cấu tạo nên nguyên tử.", "Proton", "Nơtron", "Electron", "Phân tử", 2),
                item("Dung dịch có pH nhỏ hơn 7 thường có tính chất nào?", "Liên hệ thang pH với tính axit và bazơ.", "Tính axit", "Tính bazơ", "Trung tính", "Luôn là muối", 0),
                item("Trong phản ứng hóa học kín, đại lượng nào được bảo toàn?", "Áp dụng định luật bảo toàn khối lượng.", "Tổng khối lượng các chất", "Số phân tử của từng chất", "Thể tích mọi chất", "Màu sắc chất", 0),
                item("Nguyên tử trung hòa về điện khi nào?", "Liên hệ số proton và electron trong nguyên tử trung hòa.", "Số proton bằng số electron", "Số proton bằng số neutron", "Không có electron", "Số neutron bằng số electron cộng proton", 0),
                item("Trong phản ứng hóa học, các nguyên tử thường được xem là như thế nào?", "Hiểu sự sắp xếp lại nguyên tử trong phản ứng hóa học.", "Được sắp xếp lại để tạo chất mới", "Bị biến mất hoàn toàn", "Biến thành năng lượng", "Luôn đổi thành nguyên tố khác", 0),
                item("Chất nào sau đây làm giấy quỳ tím hóa đỏ?", "Nhận biết dấu hiệu thường gặp của dung dịch axit.", "Dung dịch axit", "Dung dịch bazơ", "Nước cất", "Dung dịch muối trung tính", 0)
            ),
            upperSecondary = listOf(
                item("Một dung dịch có [H⁺] = 10⁻³ mol/L có pH bằng bao nhiêu?", "Tính pH từ nồng độ ion hiđro.", "1", "3", "7", "11", 1),
                item("Trong phản ứng oxi hóa - khử, chất khử là chất như thế nào?", "Xác định vai trò chất khử qua sự nhường electron.", "Nhận electron", "Nhường electron", "Không trao đổi electron", "Luôn chứa oxi", 1),
                item("Ở cùng nhiệt độ và áp suất, các thể tích khí bằng nhau chứa số phân tử như thế nào?", "Vận dụng định luật Avogadro.", "Bằng nhau", "Tỉ lệ với khối lượng mol", "Luôn gấp đôi nhau", "Không thể so sánh", 0),
                item("Trong phản ứng Zn + 2HCl → ZnCl₂ + H₂, chất nào bị oxi hóa?", "Nhận diện sự nhường electron trong phản ứng oxi hóa khử.", "Zn", "HCl", "Cl⁻", "H₂", 0),
                item("Khi pha loãng dung dịch axit đậm đặc, nên làm thế nào để an toàn?", "Áp dụng quy tắc an toàn khi pha loãng axit.", "Rót từ từ axit vào nước", "Rót nhanh nước vào axit", "Trộn bằng tay không", "Đun nóng trước khi rót", 0),
                item("Chất xúc tác trong phản ứng thường có tác dụng nào?", "Hiểu vai trò của chất xúc tác đối với tốc độ phản ứng.", "Làm thay đổi tốc độ phản ứng mà không bị tiêu hao sau phản ứng", "Làm tăng khối lượng sản phẩm", "Luôn là chất phản ứng chính", "Làm phản ứng không cần năng lượng", 0)
            )
        ),
        "physics" to SetOfItems(
            primary = listOf(
                item("Dụng cụ nào thường dùng để đo nhiệt độ?", "Chọn đúng dụng cụ đo nhiệt độ.", "Nhiệt kế", "Thước kẻ", "Cân", "Đồng hồ", 0),
                item("Lực ma sát thường xuất hiện khi nào?", "Nhận biết lực ma sát giữa các bề mặt tiếp xúc.", "Hai bề mặt tiếp xúc và cản trở chuyển động", "Vật đứng ngoài không khí", "Không có vật chuyển động", "Chỉ khi có ánh sáng", 0),
                item("Âm thanh truyền được trong môi trường nào?", "Nhận biết môi trường truyền âm.", "Chất rắn, lỏng và khí", "Chỉ chân không", "Chỉ ánh sáng", "Mọi nơi kể cả chân không", 0),
                item("Nguồn sáng nào sau đây tự phát ra ánh sáng?", "Phân biệt nguồn sáng với vật được chiếu sáng.", "Mặt Trời", "Mặt Trăng", "Tấm gương", "Trang giấy", 0),
                item("Khi kéo một chiếc xe đồ chơi, tác dụng nào làm xe chuyển động?", "Nhận biết lực có thể làm thay đổi chuyển động.", "Lực kéo", "Âm thanh", "Màu sắc", "Bóng tối", 0),
                item("Đơn vị đo độ dài thường dùng trong hệ SI là gì?", "Nhận biết đơn vị cơ bản đo độ dài.", "Mét", "Kilogram", "Giây", "Độ Celsius", 0),
                item("Hiện tượng nào xảy ra khi ánh sáng bị một vật cản chắn lại?", "Nhận biết sự tạo thành vùng bóng tối.", "Tạo bóng", "Tạo âm thanh", "Tăng khối lượng", "Đổi thành điện tích", 0),
                item("Vật nào thường là chất dẫn điện tốt?", "Phân biệt vật dẫn điện và vật cách điện quen thuộc.", "Dây đồng", "Thanh gỗ khô", "Thước nhựa", "Cốc thủy tinh", 0),
                item("Năng lượng Mặt Trời có thể giúp làm việc gì?", "Nhận biết một ứng dụng của năng lượng ánh sáng.", "Làm nóng nước", "Làm biến mất trọng lực", "Tạo ra đất", "Ngăn mọi âm thanh", 0),
                item("Khi thả một vật gần mặt đất, lực nào kéo vật xuống?", "Nhận biết tác dụng của trọng lực lên vật.", "Trọng lực", "Lực đàn hồi", "Lực đẩy Archimedes", "Lực điện", 0)
            ),
            secondary = listOf(
                item("Một vật đi được 120 mét trong 20 giây. Tốc độ trung bình là bao nhiêu?", "Tính tốc độ trung bình bằng quãng đường chia thời gian.", "4 m/s", "6 m/s", "20 m/s", "100 m/s", 1),
                item("Hai điện trở mắc nối tiếp có đại lượng nào bằng nhau?", "Nhận biết đặc điểm mạch điện nối tiếp.", "Cường độ dòng điện", "Hiệu điện thế trên từng điện trở", "Điện trở tương đương với từng điện trở", "Công suất từng điện trở", 0),
                item("Khối lượng riêng được tính bằng công thức nào?", "Liên hệ khối lượng riêng, khối lượng và thể tích.", "Khối lượng chia thể tích", "Thể tích chia khối lượng", "Khối lượng nhân thể tích", "Khối lượng cộng thể tích", 0),
                item("Một lực 10 N tác dụng lên vật có khối lượng 2 kg. Gia tốc theo định luật II Newton là bao nhiêu?", "Áp dụng định luật II Newton để tính gia tốc.", "5 m/s²", "8 m/s²", "12 m/s²", "20 m/s²", 0),
                item("Trong mạch điện song song, hiệu điện thế giữa hai đầu các nhánh như thế nào?", "Nhận biết đặc điểm hiệu điện thế trong mạch song song.", "Bằng nhau", "Luôn bằng không", "Cộng thành điện trở", "Chỉ có ở nhánh đầu", 0),
                item("Thấu kính hội tụ có tác dụng chính nào với chùm tia song song?", "Nhận biết tác dụng hội tụ của thấu kính lồi.", "Làm các tia hội tụ", "Làm các tia biến mất", "Đổi ánh sáng thành âm", "Luôn làm tia phân kỳ", 0)
            ),
            upperSecondary = listOf(
                item("Một điện trở 4 Ω có dòng điện 2 A chạy qua. Hiệu điện thế là bao nhiêu?", "Vận dụng định luật Ôm.", "2 V", "6 V", "8 V", "16 V", 2),
                item("Nếu hợp lực tác dụng lên vật bằng không, vật đang đứng yên sẽ như thế nào?", "Áp dụng định luật I Newton.", "Tiếp tục đứng yên", "Tăng tốc đều", "Chuyển động tròn", "Tự nóng lên", 0),
                item("Động năng của vật phụ thuộc vào đại lượng nào?", "Nhận biết các yếu tố ảnh hưởng đến động năng.", "Khối lượng và tốc độ", "Màu sắc và nhiệt độ", "Điện tích và thể tích", "Độ cao và áp suất", 0),
                item("Một sóng có tần số 5 Hz và bước sóng 2 m. Tốc độ truyền sóng là bao nhiêu?", "Tính tốc độ sóng từ tần số và bước sóng.", "2,5 m/s", "7 m/s", "10 m/s", "20 m/s", 2),
                item("Trong dao động điều hòa, khi vật đi qua vị trí cân bằng thì đại lượng nào thường đạt cực đại?", "Liên hệ vị trí cân bằng với tốc độ trong dao động điều hòa.", "Tốc độ", "Li độ", "Thế năng", "Khoảng cách đến vị trí cân bằng", 0),
                item("Nếu điện trở không đổi và hiệu điện thế tăng gấp đôi, cường độ dòng điện thay đổi thế nào?", "Vận dụng định luật Ôm khi điện trở không đổi.", "Tăng gấp đôi", "Giảm một nửa", "Không đổi", "Tăng gấp bốn", 0)
            )
        ),
        "giao_duc_gioi_tinh" to SetOfItems(
            primary = listOf(
                item("Nếu cảm thấy không thoải mái khi ai đó chạm vào mình, em nên làm gì?", "Biết cách thể hiện ranh giới cá nhân an toàn.", "Nói không và tìm người lớn đáng tin cậy", "Im lặng dù đang sợ", "Giữ bí mật với mọi người", "Tự trách mình", 0),
                item("Thông tin cá nhân nào không nên chia sẻ công khai trên mạng?", "Bảo vệ thông tin cá nhân khi sử dụng Internet.", "Địa chỉ nhà và mật khẩu", "Sở thích về màu sắc", "Tên một cuốn sách", "Môn học yêu thích", 0),
                item("Khi gặp tình huống khiến em lo lắng, người nào phù hợp để tìm giúp đỡ?", "Nhận diện người lớn đáng tin cậy để tìm hỗ trợ.", "Cha mẹ, giáo viên hoặc người chăm sóc đáng tin cậy", "Người lạ trên mạng", "Người đang đe dọa em", "Không ai cả", 0),
                item("Nếu một người lạ hỏi mật khẩu của em, em nên làm gì?", "Bảo vệ thông tin đăng nhập và tìm người lớn hỗ trợ.", "Không chia sẻ và báo người lớn đáng tin cậy", "Gửi mật khẩu để chứng minh tình bạn", "Đăng mật khẩu lên trang cá nhân", "Gửi cho nhiều người để hỏi ý kiến", 0),
                item("Cơ thể của mỗi người thuộc về ai?", "Hiểu quyền tự chủ và ranh giới cơ thể cá nhân.", "Chính người đó", "Bất kỳ người lớn nào", "Bạn bè của người đó", "Người lạ trên mạng", 0),
                item("Nếu bị lạc ở nơi công cộng, lựa chọn nào an toàn hơn?", "Chọn cách tìm hỗ trợ an toàn khi bị lạc.", "Đến quầy hỗ trợ hoặc nhân viên có nhận diện", "Đi theo người lạ ra khỏi nơi đó", "Giấu mình và không báo ai", "Đăng địa chỉ nhà lên mạng", 0),
                item("Một bí mật khiến em sợ hãi hoặc bị đe dọa có nên được giữ mãi không?", "Phân biệt bí mật gây nguy hiểm với điều bất ngờ vui vẻ.", "Không, hãy kể với người lớn đáng tin cậy", "Có, dù em đang gặp nguy hiểm", "Chỉ kể cho người lạ trên mạng", "Đăng công khai để mọi người tự đoán", 0),
                item("Khi bạn nói 'dừng lại' trong một trò chơi, em nên làm gì?", "Tôn trọng lời từ chối và ranh giới của bạn.", "Dừng lại và lắng nghe", "Tiếp tục vì đó chỉ là trò chơi", "Ép bạn tham gia", "Cười nhạo bạn", 0),
                item("Ai có quyền nói 'không' với một cái ôm?", "Nhận biết mọi người đều có quyền từ chối tiếp xúc cơ thể.", "Mỗi người", "Chỉ người lớn", "Chỉ người đang bị bệnh", "Không ai", 0),
                item("Nếu thấy bạn bị bắt nạt, em nên tìm ai để hỗ trợ?", "Biết cách tìm trợ giúp khi chứng kiến bắt nạt.", "Giáo viên hoặc người lớn đáng tin cậy", "Người đang bắt nạt", "Tài khoản lạ trên mạng", "Không ai vì đó không phải việc của em", 0)
            ),
            secondary = listOf(
                item("Dậy thì là giai đoạn cơ thể thường có thay đổi như thế nào?", "Hiểu dậy thì là quá trình phát triển tự nhiên.", "Cơ thể và cảm xúc có thể thay đổi", "Mọi người thay đổi giống hệt nhau", "Chỉ xảy ra trong một ngày", "Luôn là dấu hiệu bị bệnh", 0),
                item("Sự đồng thuận trong một tương tác có nghĩa là gì?", "Nhận biết sự đồng thuận tự nguyện và có thể rút lại.", "Tất cả cùng tự nguyện đồng ý", "Một người im lặng là đồng ý", "Đồng ý một lần có nghĩa là luôn đồng ý", "Bị ép buộc vẫn được xem là đồng ý", 0),
                item("Nếu bạn bè tiết lộ đang bị đe dọa hoặc xâm hại, em nên làm gì?", "Chọn phản ứng an toàn khi bạn cần trợ giúp.", "Lắng nghe và báo người lớn đáng tin cậy", "Hứa giữ bí mật bằng mọi giá", "Đăng câu chuyện lên mạng", "Tự đối đầu với người đe dọa", 0),
                item("Khi cơ thể có thay đổi trong tuổi dậy thì, lựa chọn phù hợp là gì?", "Tìm hiểu thông tin sức khỏe phù hợp và hỏi người đáng tin cậy.", "Hỏi nhân viên y tế hoặc người lớn đáng tin cậy", "Tự kết luận mình mắc bệnh", "Tin mọi lời đồn trên mạng", "So sánh cơ thể với bạn bè", 0),
                item("Một người có thể đồng ý thay mặt bạn về ranh giới cơ thể của bạn không?", "Nhận biết đồng thuận phải đến từ chính người liên quan.", "Không, sự đồng ý phải là của chính người đó", "Có, bất kỳ bạn nào cũng được", "Có, người lạ trên mạng được quyết định", "Có, nếu người kia im lặng", 0),
                item("Nếu nhận được tin nhắn yêu cầu giữ bí mật về việc bị chạm vào khiến em lo sợ, em nên làm gì?", "Phản ứng an toàn trước yêu cầu bí mật đáng lo ngại.", "Lưu tin nhắn và báo người lớn đáng tin cậy", "Xóa hết và không nói với ai", "Gửi ảnh riêng tư để đổi lấy im lặng", "Hẹn gặp người gửi một mình", 0)
            ),
            upperSecondary = listOf(
                item("Một người có thể thay đổi quyết định đồng thuận của mình vào lúc nào?", "Hiểu rằng đồng thuận có thể được rút lại bất cứ lúc nào.", "Bất cứ lúc nào", "Chỉ trước khi bắt đầu", "Chỉ khi có người khác cho phép", "Không thể thay đổi", 0),
                item("Khi nhận được yêu cầu gửi ảnh riêng tư trên mạng, lựa chọn an toàn nhất là gì?", "Ứng phó an toàn với yêu cầu hình ảnh riêng tư.", "Không gửi, lưu bằng chứng và báo người lớn đáng tin cậy", "Gửi nếu người yêu cầu hứa giữ kín", "Gửi ảnh khác để thử", "Chuyển tiếp cho bạn bè", 0),
                item("Vì sao mỗi người có thể trải qua dậy thì ở thời điểm khác nhau?", "Tôn trọng khác biệt cá nhân trong quá trình phát triển.", "Quá trình phát triển khác nhau giữa mỗi người", "Ai cũng phải thay đổi cùng ngày", "Chỉ chế độ ăn quyết định hoàn toàn", "Đó luôn là dấu hiệu bất thường", 0),
                item("Khi chia sẻ thông tin về sức khỏe sinh sản, nguồn nào đáng tin cậy hơn?", "Đánh giá độ tin cậy của thông tin sức khỏe.", "Nhân viên y tế hoặc nguồn giáo dục chính thống", "Bài đăng ẩn danh không dẫn nguồn", "Tin nhắn chuyển tiếp chưa kiểm chứng", "Quảng cáo cam kết chữa khỏi mọi vấn đề", 0),
                item("Nếu một người rút lại sự đồng thuận, người kia cần làm gì?", "Ứng xử tôn trọng khi đồng thuận bị rút lại.", "Dừng tương tác ngay", "Tiếp tục vì đã đồng ý trước đó", "Yêu cầu giải thích rồi mới dừng", "Nhờ bạn bè gây áp lực", 0),
                item("Khi lo ngại hình ảnh riêng tư bị phát tán, bước đầu phù hợp là gì?", "Tìm hỗ trợ an toàn thay vì tự xử lý một mình.", "Báo người lớn đáng tin cậy và nền tảng liên quan", "Chuyển ảnh cho nhiều người để xác minh", "Gặp người phát tán một mình", "Tự đổ lỗi và im lặng", 0)
            )
        )
    )

    fun supports(subject: String): Boolean = normalizeSubject(subject) in banks

    fun generate(
        grade: Int,
        subject: String,
        difficulty: AIService.Difficulty
    ): AIService.GeneratedAssignment {
        require(grade in 1..12)
        val normalized = normalizeSubject(subject)
        val bank = banks[normalized] ?: error("No local assignment bank for $subject")
        val questionCount = when (grade) {
            in 1..5 -> 10
            in 6..9 -> 16
            else -> 22
        }
        val items = when (grade) {
            in 1..5 -> bank.primary
            in 6..9 -> bank.primary + bank.secondary
            else -> bank.primary + bank.secondary + bank.upperSecondary
        }
        require(items.size == questionCount) {
            "$normalized question bank must contain $questionCount items for grade $grade; found ${items.size}"
        }
        val random = Random(System.nanoTime())
        val ordered = items.map { item ->
            val correctChoice = item.choices[item.answer]
            val choices = item.choices.shuffled(random)
            item.copy(choices = choices, answer = choices.indexOf(correctChoice))
        }
        val questions = ordered.mapIndexed { index, item ->
            val id = index + 1
            val isChoice = grade <= 5 || (grade in 6..9 && id <= 11) || (grade >= 10 && id <= 12)
            val isTrueFalse = grade >= 10 && id in 13..16
            val points = questionPoints(grade, id)
            val statements = if (isTrueFalse) {
                item.choices.mapIndexed { choiceIndex, choice ->
                    "Đáp án cho câu hỏi «${item.prompt}» là «$choice»."
                }
            } else {
                emptyList()
            }
            val correctAnswer = when {
                isTrueFalse -> item.choices.indices.joinToString(",") {
                    if (it == item.answer) "Đ" else "S"
                }
                isChoice -> ('A'.code + item.answer).toChar().toString()
                else -> item.choices[item.answer]
            }
            AIService.GeneratedQuestion(
                id = id,
                question = if (isTrueFalse) {
                    "Hãy đánh dấu Đúng hoặc Sai cho từng nhận định về câu hỏi: ${item.prompt}"
                } else if (isChoice) {
                    item.prompt
                } else {
                    "Hãy trả lời ngắn: ${item.prompt}"
                },
                learningObjective = SubjectAssessmentMatrix.classify(
                    normalized,
                    grade,
                    item.prompt,
                    item.objective,
                    index
                )?.let { (topic, level) ->
                    "Mạch nội dung: $topic. Mức độ: ${level.displayName()}. ${item.objective}"
                } ?: item.objective,
                points = points,
                answerType = AIService.AnswerType.TEXT,
                gradingMethod = AIService.GradingMethod.EXACT,
                sourceType = AIService.QuestionSourceType.SELF_CONTAINED,
                gradingSpec = AIService.GradingSpec(
                    method = AIService.RuleGradingMethod.EXACT,
                    correctAnswer = correctAnswer,
                    ignoreWhitespace = true,
                    mathAnswerSpec = if (isTrueFalse) {
                        AIService.MathAnswerSpec(kind = AIService.MathAnswerKind.TRUE_FALSE_SET)
                    } else {
                        null
                    }
                ),
                options = if (isChoice) item.choices else emptyList(),
                statements = statements
            )
        }
        val answers = questions.map { question ->
            AIService.GeneratedAnswer(question.id, question.gradingSpec.correctAnswer)
        }
        val subjectTitle = subjectDisplayName(normalized, grade)
        return AIService.GeneratedAssignment(
            title = "$subjectTitle lớp $grade • ${difficultyDisplayName(difficulty)}",
            questions = questions,
            answerKey = answers,
            gradingGuide = "Câu hỏi được gắn mạch nội dung và mức độ nhận thức theo ma trận chương trình. " +
                "Câu trắc nghiệm và trả lời ngắn được chấm theo đáp án; câu đúng sai được tính điểm theo từng nhận định. Tổng điểm tối đa là 10.",
            totalScore = 10.0
        )
    }

    private fun questionPoints(grade: Int, questionId: Int): Double = when (grade) {
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

    private fun item(
        prompt: String,
        objective: String,
        a: String,
        b: String,
        c: String,
        d: String,
        answer: Int
    ) = Item(prompt, objective, listOf(a, b, c, d), answer)

    private fun normalizeSubject(subject: String): String = when (subject.trim().lowercase(Locale.ROOT)) {
        "literature", "văn", "ngữ văn" -> "literature"
        "english", "tiếng anh" -> "english"
        "biology", "sinh", "sinh học" -> "biology"
        "chemistry", "hóa", "hóa học" -> "chemistry"
        "physics", "lý", "vật lý" -> "physics"
        "giao_duc_gioi_tinh", "giáo dục giới tính" -> "giao_duc_gioi_tinh"
        else -> subject.trim().lowercase(Locale.ROOT)
    }

    private fun subjectDisplayName(subject: String, grade: Int): String = when (subject) {
        "literature" -> if (grade <= 5) "Tiếng Việt" else "Ngữ Văn"
        "english" -> if (grade < 3) "Tiếng Anh • nội dung làm quen" else "Tiếng Anh"
        "biology", "chemistry", "physics" -> {
            val component = when (subject) {
                "biology" -> "Sinh học"
                "chemistry" -> "Hóa học"
                else -> "Vật lý"
            }
            when {
                grade <= 3 -> "Tự nhiên và Xã hội • $component"
                grade <= 5 -> "Khoa học • $component"
                grade <= 9 -> "Khoa học tự nhiên • $component"
                else -> component
            }
        }
        else -> "Chủ đề giáo dục sức khỏe giới tính"
    }

    private fun difficultyDisplayName(difficulty: AIService.Difficulty): String = when (difficulty) {
        AIService.Difficulty.EASY -> "Dễ"
        AIService.Difficulty.MEDIUM -> "Trung bình"
        AIService.Difficulty.HARD -> "Khó"
    }

    private fun SubjectAssessmentMatrix.Level.displayName(): String = when (this) {
        SubjectAssessmentMatrix.Level.RECOGNITION -> "Nhận biết"
        SubjectAssessmentMatrix.Level.UNDERSTANDING -> "Thông hiểu"
        SubjectAssessmentMatrix.Level.APPLICATION -> "Vận dụng"
        SubjectAssessmentMatrix.Level.ADVANCED_APPLICATION -> "Vận dụng cao"
    }
}
