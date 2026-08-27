package cn.bitoffer.msgcenter;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 统一消息推送中台启动类。
 *
 * @author LQH
 */
@SpringBootApplication(scanBasePackages = {"cn.bitoffer"})
@EnableScheduling
public class MsgCennterApplication {

    public static void main(String[] args) {
        SpringApplication.run(MsgCennterApplication.class, args);
    }
}
