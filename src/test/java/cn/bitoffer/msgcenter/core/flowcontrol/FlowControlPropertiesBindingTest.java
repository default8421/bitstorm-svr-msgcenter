package cn.bitoffer.msgcenter.core.flowcontrol;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/**
 * 校验 application.yml 里的流控配置真的能绑上。
 *
 * <p>这些属性名一旦写错，应用照样能启动，只是所有限额悄悄退回代码里的默认值——那种故障在压测
 * 报告出来之前根本发现不了，所以用一个测试把真实配置文件钉住。
 *
 * @author LQH
 */
class FlowControlPropertiesBindingTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withUserConfiguration(EnableProperties.class);

    @Test
    void bindsEveryLayerOfTheQuotaConfigurationFromApplicationYml() {
        contextRunner.run(context -> {
            FlowControlProperties properties = context.getBean(FlowControlProperties.class);

            assertThat(properties.getPlatform().getQps()).isEqualTo(500);
            assertThat(properties.getChannels()).containsKeys(1, 2, 3);
            assertThat(properties.getChannels().get(2).getQps()).isEqualTo(200);
            assertThat(properties.getTenantDefault().getQps()).isEqualTo(100);
            assertThat(properties.getWeights().getHigh()).isEqualTo(6);
            assertThat(properties.getWeights().getLow()).isEqualTo(1);
            assertThat(properties.getScheduler().getCapacityPerPriority()).isEqualTo(500);
            assertThat(properties.getAdmission().getSoftWatermark()).isEqualTo(4_000L);
            assertThat(properties.getAdmission().getHardWatermark()).isEqualTo(12_000L);
            assertThat(properties.getKafka().getMaxWaitMillis()).isEqualTo(60_000L);
        });
    }

    @Test
    void keepsRecipientSuppressionOffUntilItIsDeliberatelyRolledOut() {
        contextRunner.run(context -> assertThat(context.getBean(FlowControlProperties.class)
                .getSuppression().isEnabled()).isFalse());
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(FlowControlProperties.class)
    static class EnableProperties {
    }
}
