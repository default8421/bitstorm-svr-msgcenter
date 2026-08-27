package cn.bitoffer.msgcenter.core.msgpush.channel;

import cn.bitoffer.msgcenter.core.config.SendMsgConf;
import cn.bitoffer.msgcenter.core.exception.BusinessException;
import cn.bitoffer.msgcenter.core.exception.ErrorCode;
import cn.bitoffer.msgcenter.core.msgpush.MsgPushService;
import cn.bitoffer.msgcenter.core.msgpush.base.ChannelMsgBase;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;

/**
 * 飞书渠道：未开启时记日志并视为已发送，开启后走群机器人 webhook。
 *
 * @author LQH
 */
@Service
@Slf4j
public class LarkServiceImpl implements MsgPushService {

    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Autowired
    SendMsgConf sendMsgConf;

    @Override
    public void pushMsg(ChannelMsgBase msgBase) {
        if (!sendMsgConf.isLarkWebhookEnabled()) {
            log.info("发送 Lark!!!!! content:" + msgBase.getContent());
            return;
        }
        sendViaWebhook(msgBase);
    }

    private void sendViaWebhook(ChannelMsgBase msgBase) {
        try {
            String text = msgBase.getSubject() != null
                    ? msgBase.getSubject() + "\n" + msgBase.getContent()
                    : msgBase.getContent();

            ObjectNode root = OBJECT_MAPPER.createObjectNode();
            root.put("msg_type", "text");
            root.putObject("content").put("text", text);

            String secret = sendMsgConf.getLarkWebhookSecret();
            if (secret != null && !secret.isBlank()) {
                long timestamp = System.currentTimeMillis() / 1000;
                root.put("timestamp", String.valueOf(timestamp));
                root.put("sign", sign(timestamp, secret));
            }

            String body = OBJECT_MAPPER.writeValueAsString(root);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(sendMsgConf.getLarkWebhookUrl()))
                    .timeout(Duration.ofSeconds(5))
                    .header("Content-Type", "application/json; charset=utf-8")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
            handleResponse(response);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("飞书机器人发送失败, to={}", msgBase.getTo(), e);
            throw new BusinessException(ErrorCode.PUSH_MSG_ERROR, "lark push msg error: " + e.getMessage());
        }
    }

    private void handleResponse(HttpResponse<String> response) throws Exception {
        if (response.statusCode() != 200) {
            throw new BusinessException(ErrorCode.PUSH_MSG_ERROR, "lark webhook http " + response.statusCode());
        }
        JsonNode json = OBJECT_MAPPER.readTree(response.body());
        int code = json.path("code").asInt(0);
        if (code != 0) {
            String msg = json.path("msg").asText("unknown error");
            log.warn("飞书机器人发送失败: code={} msg={}", code, msg);
            throw new BusinessException(ErrorCode.PUSH_MSG_ERROR, "lark webhook rejected: " + msg);
        }
        log.info("飞书机器人发送成功");
    }

    /**
     * Feishu's custom-bot signature: HMAC-SHA256 keyed by "{timestamp}\n{secret}", signing an
     * empty message, then Base64-encoded. See 群机器人 -> 签名校验 in the Feishu open platform docs.
     */
    private String sign(long timestamp, String secret) throws Exception {
        String stringToSign = timestamp + "\n" + secret;
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(stringToSign.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] signData = mac.doFinal(new byte[0]);
        return Base64.getEncoder().encodeToString(signData);
    }
}
