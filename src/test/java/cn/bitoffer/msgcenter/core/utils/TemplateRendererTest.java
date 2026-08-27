package cn.bitoffer.msgcenter.core.utils;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * TemplateRendererTest。
 *
 * @author LQH
 */
class TemplateRendererTest {

    @Test
    void replacesKnownKeysAndClearsMissingPlaceholders() {
        String template = "欢迎使用${event}\n现在给你推送活动${detail}\n现在给你推送活动2${hhh}";
        String rendered = TemplateRenderer.render(template, Map.of(
                "event", "云擎推送",
                "detail", "哈哈哈"));
        assertThat(rendered).isEqualTo("欢迎使用云擎推送\n现在给你推送活动哈哈哈\n现在给你推送活动2");
        assertThat(rendered).doesNotContain("${");
    }

    @Test
    void treatsNullParamsAsEmptyAndStripsPlaceholders() {
        assertThat(TemplateRenderer.render("hi ${name}", null)).isEqualTo("hi ");
        assertThat(TemplateRenderer.render(null, Map.of("a", "b"))).isEmpty();
    }

    @Test
    void splitsRecipientsByCommaSemicolonAndNewline() {
        assertThat(TemplateRenderer.splitRecipients("a@x.com, b@x.com;\n13800138000,a@x.com"))
                .containsExactly("a@x.com", "b@x.com", "13800138000");
        assertThat(TemplateRenderer.splitRecipients("  , ; \n ")).isEmpty();
    }
}
