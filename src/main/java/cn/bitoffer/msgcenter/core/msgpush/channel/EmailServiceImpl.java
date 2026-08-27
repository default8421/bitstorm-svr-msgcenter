package cn.bitoffer.msgcenter.core.msgpush.channel;

import cn.bitoffer.msgcenter.core.config.SendMsgConf;
import cn.bitoffer.msgcenter.core.exception.BusinessException;
import cn.bitoffer.msgcenter.core.exception.ErrorCode;
import cn.bitoffer.msgcenter.core.msgpush.MsgPushService;
import cn.bitoffer.msgcenter.core.msgpush.base.ChannelMsgBase;
import java.util.Properties;
import javax.mail.Authenticator;
import javax.mail.Message;
import javax.mail.MessagingException;
import javax.mail.PasswordAuthentication;
import javax.mail.Session;
import javax.mail.Transport;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 邮件渠道：未开启时记日志并视为已发送；开启后走 SMTP。
 * 465 使用 SSL（163），587 使用 STARTTLS（QQ 等）。
 *
 * @author LQH
 */
@Service
@Slf4j
public class EmailServiceImpl implements MsgPushService {

    @Autowired
    SendMsgConf sendMsgConf;

    @Override
    public void pushMsg(ChannelMsgBase msgBase) {
        if (!sendMsgConf.isEmailEnabled()) {
            log.info("发送 Email(桩) to:{} content:{}", msgBase.getTo(), msgBase.getContent());
            return;
        }
        sendViaSmtp(msgBase);
    }

    private void sendViaSmtp(ChannelMsgBase msgBase) {
        String host = sendMsgConf.getEmailHost();
        String port = sendMsgConf.getEmailPort();
        String username = sendMsgConf.getEmailAccount();
        String password = sendMsgConf.getEmailAuthCode();
        String recipient = msgBase.getTo();

        Properties properties = new Properties();
        properties.put("mail.smtp.host", host);
        properties.put("mail.smtp.port", port);
        properties.put("mail.smtp.auth", "true");
        properties.put("mail.smtp.connectiontimeout", "8000");
        properties.put("mail.smtp.timeout", "8000");
        properties.put("mail.smtp.writetimeout", "8000");
        if ("465".equals(port) || "994".equals(port)) {
            properties.put("mail.smtp.ssl.enable", "true");
            properties.put("mail.smtp.socketFactory.class", "javax.net.ssl.SSLSocketFactory");
            properties.put("mail.smtp.socketFactory.port", port);
            properties.put("mail.smtp.socketFactory.fallback", "false");
        } else {
            properties.put("mail.smtp.starttls.enable", "true");
        }

        Session session = Session.getInstance(properties, new Authenticator() {
            @Override
            protected PasswordAuthentication getPasswordAuthentication() {
                return new PasswordAuthentication(username, password);
            }
        });

        try {
            MimeMessage message = new MimeMessage(session);
            message.setFrom(new InternetAddress(username));
            message.setRecipients(Message.RecipientType.TO, InternetAddress.parse(recipient));
            message.setSubject(msgBase.getSubject() == null ? "" : msgBase.getSubject(), "UTF-8");
            message.setText(msgBase.getContent() == null ? "" : msgBase.getContent(), "UTF-8");
            Transport.send(message);
            log.info("邮件发送成功 to={}", recipient);
        } catch (MessagingException e) {
            log.error("邮件发送失败 to={}", recipient, e);
            throw new BusinessException(ErrorCode.PUSH_MSG_ERROR, "email push msg error: " + e.getMessage());
        }
    }
}
