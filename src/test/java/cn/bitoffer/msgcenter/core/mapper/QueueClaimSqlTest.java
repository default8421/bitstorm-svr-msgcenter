package cn.bitoffer.msgcenter.core.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Locale;
import org.junit.jupiter.api.Test;

/**
 * QueueClaimSqlTest。
 *
 * @author LQH
 */
class QueueClaimSqlTest {

    private String mapperXml(String resource) throws Exception {
        try (var input = getClass().getClassLoader().getResourceAsStream(resource)) {
            assertThat(input).as(resource).isNotNull();
            return new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
                    .toLowerCase(Locale.ROOT);
        }
    }

    @Test
    void mysqlQueueClaimUsesRowLockingSkipLockedToAvoidDuplicateConsumption() throws Exception {
        String xml = mapperXml("mapper/MsgQueueMapper.xml");
        assertThat(xml).contains("selectpendingforupdate");
        assertThat(xml).contains("for update").contains("skip locked");
    }

    @Test
    void mysqlQueueClaimGuardsTheStatusFlipToStayIdempotent() throws Exception {
        String xml = mapperXml("mapper/MsgQueueMapper.xml");
        // the flip must be guarded by the source status so a lost race cannot double-process
        assertThat(xml).contains("claimbymsgids");
        assertThat(xml).contains("status = #{fromstatus}");
    }

    @Test
    void mysqlQueueHasTimeBasedStaleProcessingRecovery() throws Exception {
        String xml = mapperXml("mapper/MsgQueueMapper.xml");
        assertThat(xml).contains("recoverstaleprocessing");
        assertThat(xml).contains("modify_time");
        assertThat(xml).contains("interval");
    }
}
