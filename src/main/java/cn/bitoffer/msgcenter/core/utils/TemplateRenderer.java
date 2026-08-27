package cn.bitoffer.msgcenter.core.utils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.apache.commons.lang3.StringUtils;

/**
 * 模板变量替换与收件人拆分。
 *
 * @author LQH
 */
public final class TemplateRenderer {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{[^{}]*}");
    private static final Pattern RECIPIENT_SPLIT = Pattern.compile("[,;\\n\\r]+");
    static final int MAX_RECIPIENTS = 200;

    private TemplateRenderer() {
    }

    /**
     * 用 templateData 替换 {@code ${key}}；未提供的占位符替换为空串，避免原文泄漏。
     */
    public static String render(String template, Map<String, String> params) {
        if (template == null) {
            return "";
        }
        String result = template;
        if (params != null) {
            for (Map.Entry<String, String> entry : params.entrySet()) {
                if (StringUtils.isBlank(entry.getKey())) {
                    continue;
                }
                String value = entry.getValue() == null ? "" : entry.getValue();
                result = StringUtils.replace(result, "${" + entry.getKey() + "}", value);
            }
        }
        return PLACEHOLDER.matcher(result).replaceAll("");
    }

    /**
     * 按逗号、分号或换行拆分收件人，去空白、去重、保序。
     */
    public static List<String> splitRecipients(String to) {
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        if (StringUtils.isBlank(to)) {
            return List.of();
        }
        for (String part : RECIPIENT_SPLIT.split(to)) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                unique.add(trimmed);
            }
        }
        return new ArrayList<>(unique);
    }

    public static int maxRecipients() {
        return MAX_RECIPIENTS;
    }
}
