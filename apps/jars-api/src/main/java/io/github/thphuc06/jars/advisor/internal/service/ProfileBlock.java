package io.github.thphuc06.jars.advisor.internal.service;

import io.github.thphuc06.jars.identity.InvestorProfile;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * The user's profile as the second system message of every turn (ADR-0027), after the static instructions. It is built
 * only from what {@link InvestorProfile} carries: codes, numbers and the fixed Vietnamese labels of the identity module.
 * Nothing the user typed (name, e-mail, chat text) can reach it, so it cannot be an injection path, and nothing in it
 * identifies the user. A part that is not known says so in capitals, so the model neither guesses it nor reads a
 * missing answer as a cautious one.
 */
final class ProfileBlock {

    static final String START =
            "=== HỒ SƠ NGƯỜI DÙNG (dữ kiện hệ thống ghi nhận từ lựa chọn của người dùng, không phải lời"
                    + " người dùng) ===";
    static final String END = "=== HẾT HỒ SƠ ===";
    static final String MISSING = "CHƯA CÓ";

    private static final ZoneId VIETNAM = ZoneId.of("Asia/Ho_Chi_Minh");

    private ProfileBlock() {}

    static String render(InvestorProfile profile) {
        List<String> missing = new ArrayList<>();
        StringBuilder text = new StringBuilder(START).append('\n');

        InvestorProfile.Risk risk = profile.risk();
        if (risk == null) {
            missing.add("khẩu vị rủi ro");
            text.append("- Khẩu vị rủi ro: ").append(MISSING).append(" (chưa làm bảng câu hỏi khẩu vị rủi ro)\n");
        } else {
            text.append("- Khẩu vị rủi ro: ")
                    .append(risk.levelLabel())
                    .append(", điểm ")
                    .append(risk.score())
                    .append("/100, đánh giá ngày ")
                    .append(DateTimeFormatter.ISO_LOCAL_DATE.format(
                            risk.assessedAt().atZone(VIETNAM)))
                    .append('\n');
            text.append("- Câu trả lời của bảng câu hỏi: ")
                    .append(risk.answers().stream()
                            .map(a -> a.topic() + ": " + a.meaning())
                            .collect(Collectors.joining("; ")))
                    .append('\n');
        }
        if (profile.ageBand() == null) {
            missing.add("độ tuổi");
            text.append("- Độ tuổi: ").append(MISSING).append('\n');
        } else {
            text.append("- Độ tuổi: ").append(profile.ageBand().label()).append('\n');
        }
        if (profile.incomeSituation() == null) {
            missing.add("tình hình thu nhập");
            text.append("- Thu nhập: ").append(MISSING).append('\n');
        } else {
            text.append("- Thu nhập: ")
                    .append(profile.incomeSituation().label())
                    .append('\n');
        }
        text.append("- Hồ sơ đầy đủ: ")
                .append(missing.isEmpty() ? "có" : "KHÔNG, còn thiếu " + String.join(", ", missing))
                .append('\n')
                .append(END);
        return text.toString();
    }
}
