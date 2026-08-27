package cn.bitoffer.msgcenter.core.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Locale;
import org.junit.jupiter.api.Test;

/**
 * TimerQueueClaimSqlTest。
 *
 * @author LQH
 */
class TimerQueueClaimSqlTest {

    private String mapperXml() throws Exception {
        try (var input = getClass().getClassLoader()
                .getResourceAsStream("mapper/MsgQueueTimerMapper.xml")) {
            assertThat(input).isNotNull();
            return new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
                    .toLowerCase(Locale.ROOT);
        }
    }

    @Test
    void timerQueueClaimUsesRowLockingSkipLocked() throws Exception {
        String xml = mapperXml();
        assertThat(xml).contains("selectontimeforupdate");
        assertThat(xml).contains("for update").contains("skip locked");
    }

    @Test
    void timerQueueGuardsTheStatusFlipToStayIdempotent() throws Exception {
        String xml = mapperXml();
        assertThat(xml).contains("claimbymsgids");
        assertThat(xml).contains("status = #{fromstatus}");
    }

    @Test
    void timerQueueHasTimeBasedStaleProcessingRecovery() throws Exception {
        String xml = mapperXml();
        assertThat(xml).contains("recoverstaleprocessing");
        assertThat(xml).contains("modify_time");
        assertThat(xml).contains("interval");
    }
}
