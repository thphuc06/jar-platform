package io.github.thphuc06.jars.advisor.internal.guard;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.bedrockruntime.model.ApplyGuardrailRequest;
import software.amazon.awssdk.services.bedrockruntime.model.ApplyGuardrailResponse;
import software.amazon.awssdk.services.bedrockruntime.model.GuardrailAction;
import software.amazon.awssdk.services.bedrockruntime.model.GuardrailAssessment;
import software.amazon.awssdk.services.bedrockruntime.model.GuardrailContentBlock;
import software.amazon.awssdk.services.bedrockruntime.model.GuardrailContentPolicyAction;
import software.amazon.awssdk.services.bedrockruntime.model.GuardrailContentSource;
import software.amazon.awssdk.services.bedrockruntime.model.GuardrailOutputContent;
import software.amazon.awssdk.services.bedrockruntime.model.GuardrailPiiEntityFilter;
import software.amazon.awssdk.services.bedrockruntime.model.GuardrailRegexFilter;
import software.amazon.awssdk.services.bedrockruntime.model.GuardrailSensitiveInformationPolicyAction;
import software.amazon.awssdk.services.bedrockruntime.model.GuardrailTextBlock;
import software.amazon.awssdk.services.bedrockruntime.model.GuardrailTopicPolicyAction;

/**
 * {@code ApplyGuardrail} with the guardrail of JarsApp. Spring AI 2.0 cannot attach a guardrail to the Converse request
 * (no {@code guardrailConfig} in its options), so the advisor calls this before and after the model (ADR-0023).
 *
 * <p>The guardrail answers with one of three things (ADR-0026): nothing found, sensitive data masked, or blocked. The
 * masked text is built here from the values the guardrail reports ({@code match} of each PII entity and regex), not
 * taken from {@code outputs}, because {@code outputs} holds the blocked message instead of the text when something was
 * blocked in the same call, and the caller must never store the raw text of a blocked message either.
 */
class BedrockGuardrail implements ContentGuard {

    /** Shown when the denied topic applied, instead of the one generic message the guardrail has per direction. */
    static final String TOPIC_REPLY = "Tôi không đưa ra khuyến nghị mua bán chứng khoán hay tài sản cụ thể. Bạn có thể"
            + " hỏi về ngân sách, chi tiêu và mục tiêu tiết kiệm.";

    private final BedrockRuntimeClient client;
    private final String guardrailId;
    private final String guardrailVersion;

    BedrockGuardrail(BedrockRuntimeClient client, String guardrailId, String guardrailVersion) {
        this.client = client;
        this.guardrailId = guardrailId;
        this.guardrailVersion = guardrailVersion;
    }

    @Override
    public Verdict checkInput(String text) {
        return apply(GuardrailContentSource.INPUT, text);
    }

    @Override
    public Verdict checkOutput(String text) {
        return apply(GuardrailContentSource.OUTPUT, text);
    }

    /**
     * The app's own jar codes read like stock tickers to the guardrail: "Số dư hũ NEC" was blocked as investment
     * advice (measured on AWS, Phase 2 WP6). The guardrail sees the jar names instead; the model and the user still see
     * the original text (except in a message where sensitive data was found: that text is masked from what the
     * guardrail saw, so its jar codes are the jar names too).
     */
    static final Map<Pattern, String> JAR_CODES = Map.of(
            Pattern.compile("\\bNEC\\b"), "Thiết yếu",
            Pattern.compile("\\bFFA\\b"), "Tự do tài chính",
            Pattern.compile("\\bLTSS\\b"), "Tiết kiệm dài hạn",
            Pattern.compile("\\bEDU\\b"), "Giáo dục",
            Pattern.compile("\\bPLAY\\b"), "Hưởng thụ",
            Pattern.compile("\\bGIVE\\b"), "Cho đi");

    /**
     * The same for the bank's index fund (ADR-0027): "Quỹ chỉ số VN30 có rủi ro thế nào?" was blocked as investment
     * advice in 3 of 3 tries because "VN30" reads like a ticker (measured on AWS). Only the name of the fund is
     * rewritten, after "quỹ" or "Lục Bình": "ETF VN30" and "VN30 tháng sau tăng bao nhiêu?" still reach the guardrail
     * as they are.
     */
    static final Pattern INDEX_FUND = Pattern.compile("(?iu)\\b((?:quỹ|lục\\s+bình)(?:\\s+chỉ\\s+số)?)\\s+VN30\\b");

    static String forGuardrail(String text) {
        String result = INDEX_FUND.matcher(text).replaceAll("$1 30 doanh nghiệp lớn");
        for (Map.Entry<Pattern, String> code : JAR_CODES.entrySet()) {
            result = code.getKey().matcher(result).replaceAll(code.getValue());
        }
        return result;
    }

    /** One value the guardrail found: a PII entity type or regex name, the matched text, and what it did with it. */
    record Finding(String label, String match, GuardrailSensitiveInformationPolicyAction action) {}

    private Verdict apply(GuardrailContentSource source, String text) {
        String sent = forGuardrail(text);
        ApplyGuardrailResponse response = client.applyGuardrail(ApplyGuardrailRequest.builder()
                .guardrailIdentifier(guardrailId)
                .guardrailVersion(guardrailVersion)
                .source(source)
                .content(GuardrailContentBlock.builder()
                        .text(GuardrailTextBlock.builder().text(sent).build())
                        .build())
                .build());
        if (response.action() != GuardrailAction.GUARDRAIL_INTERVENED) {
            return Verdict.pass(text);
        }
        List<GuardrailAssessment> assessments = response.assessments();
        List<Finding> findings = findings(assessments);
        // detected values are masked even when something else blocked the message (the guardrail reports them with
        // action NONE then): a blocked message is stored too
        String masked = findings.isEmpty() ? text : mask(sent, findings);

        boolean topic = assessments.stream()
                .map(GuardrailAssessment::topicPolicy)
                .filter(Objects::nonNull)
                .flatMap(policy -> policy.topics().stream())
                .anyMatch(t -> t.action() == GuardrailTopicPolicyAction.BLOCKED);
        boolean contentFilter = assessments.stream()
                .map(GuardrailAssessment::contentPolicy)
                .filter(Objects::nonNull)
                .flatMap(policy -> policy.filters().stream())
                .anyMatch(f -> f.action() == GuardrailContentPolicyAction.BLOCKED);
        boolean piiBlocked =
                findings.stream().anyMatch(f -> f.action() == GuardrailSensitiveInformationPolicyAction.BLOCKED);
        boolean piiMasked =
                findings.stream().anyMatch(f -> f.action() == GuardrailSensitiveInformationPolicyAction.ANONYMIZED);
        if (piiMasked && !topic && !contentFilter && !piiBlocked) {
            return Verdict.masked(masked);
        }
        // anything else that intervened, including a reason this code does not know, is a block
        String reply = topic
                ? TOPIC_REPLY
                : response.outputs().stream().map(GuardrailOutputContent::text).collect(Collectors.joining("\n"));
        return Verdict.blocked(reply, masked);
    }

    private static List<Finding> findings(List<GuardrailAssessment> assessments) {
        List<Finding> findings = new ArrayList<>();
        for (GuardrailAssessment assessment : assessments) {
            if (assessment.sensitiveInformationPolicy() == null) {
                continue;
            }
            for (GuardrailPiiEntityFilter pii :
                    assessment.sensitiveInformationPolicy().piiEntities()) {
                add(findings, pii.typeAsString(), pii.match(), pii.action());
            }
            for (GuardrailRegexFilter regex :
                    assessment.sensitiveInformationPolicy().regexes()) {
                add(findings, regex.name(), regex.match(), regex.action());
            }
        }
        return findings;
    }

    private static void add(
            List<Finding> findings, String label, String match, GuardrailSensitiveInformationPolicyAction action) {
        if (match != null && !match.isBlank()) {
            findings.add(new Finding(label, match, action));
        }
    }

    /** Replaces each reported value with {@code {LABEL}}, the form Bedrock itself uses; longest first, so overlaps work. */
    static String mask(String text, List<Finding> findings) {
        String masked = text;
        for (Finding finding : findings.stream()
                .sorted(Comparator.comparingInt((Finding f) -> f.match().length())
                        .reversed())
                .toList()) {
            masked = masked.replace(finding.match(), "{" + finding.label() + "}");
        }
        return masked;
    }
}
