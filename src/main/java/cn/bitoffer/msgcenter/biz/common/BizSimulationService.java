package cn.bitoffer.msgcenter.biz.common;

import cn.bitoffer.msgcenter.core.model.dto.SendMsgReq;
import cn.bitoffer.msgcenter.core.service.SendMsgService;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 业务接入层：把事件组装成消息投入内核。
 *
 * @author LQH
 */
@Service
@Slf4j
public class BizSimulationService {

    private static final int MAX_COUNT = 2000;
    private static final int MAX_LARK_PER_RUN = 5;

    private final SendMsgService sendMsgService;
    private final BizProvisioner provisioner;
    private final BizEventGenerator generator;

    public BizSimulationService(SendMsgService sendMsgService, BizProvisioner provisioner,
            BizEventGenerator generator) {
        this.sendMsgService = sendMsgService;
        this.provisioner = provisioner;
        this.generator = generator;
    }

    public BizSimulationResult simulate(int count, boolean includeLark) {
        int total = Math.max(1, Math.min(count, MAX_COUNT));
        long start = System.currentTimeMillis();

        Map<BizSource, Integer> submitted = new EnumMap<>(BizSource.class);
        Map<BizSource, Integer> failed = new EnumMap<>(BizSource.class);

        int larkCount = includeLark ? Math.min(MAX_LARK_PER_RUN, Math.max(1, total / 20)) : 0;
        int normal = total - larkCount;

        for (int i = 0; i < normal; i++) {
            dispatch(pickNormalSource(), submitted, failed);
        }
        for (int i = 0; i < larkCount; i++) {
            dispatch(BizSource.SYSTEM, submitted, failed);
        }

        long elapsed = System.currentTimeMillis() - start;
        int okTotal = submitted.values().stream().mapToInt(Integer::intValue).sum();
        int failTotal = failed.values().stream().mapToInt(Integer::intValue).sum();

        List<BizSimulationResult.LineStat> lines = new ArrayList<>();
        for (BizSource s : BizSource.values()) {
            int ok = submitted.getOrDefault(s, 0);
            int bad = failed.getOrDefault(s, 0);
            if (ok == 0 && bad == 0) {
                continue;
            }
            lines.add(new BizSimulationResult.LineStat(s.sourceId(), s.displayName(),
                    s.channelName(), ok, bad));
        }
        log.info("业务模拟完成 total={} ok={} fail={} lark={} elapsedMs={}", total, okTotal, failTotal,
                larkCount, elapsed);
        return new BizSimulationResult(okTotal, failTotal, elapsed, lines);
    }

    public BizEvent sample(BizSource source) {
        return generator.next(source);
    }

    public BizEmitResult emit(BizSource source, String to, Map<String, String> data) {
        String recipient = (to == null || to.isBlank()) ? "ops" : to.trim();
        Map<String, String> vars = (data == null) ? Map.of() : data;
        String content = render(source.templateContent(), vars);
        try {
            SendMsgReq req = new SendMsgReq();
            req.setTemplateId(provisioner.templateId(source));
            req.setTo(recipient);
            req.setSubject(source.displayName());
            req.setPriority(source.priority());
            req.setTemplateData(vars);
            String msgId = sendMsgService.SendMsg(req);
            log.info("手动发送成功 source={} to={} msgId={}", source.sourceId(), recipient, msgId);
            return new BizEmitResult(source.sourceId(), source.displayName(), source.channelName(),
                    recipient, content, msgId, true, null);
        } catch (Exception e) {
            log.warn("手动发送失败 source={} to={}: {}", source.sourceId(), recipient, e.getMessage());
            return new BizEmitResult(source.sourceId(), source.displayName(), source.channelName(),
                    recipient, content, null, false, e.getMessage());
        }
    }

    private String render(String template, Map<String, String> data) {
        String out = template;
        for (Map.Entry<String, String> e : data.entrySet()) {
            out = out.replace("${" + e.getKey() + "}", e.getValue() == null ? "" : e.getValue());
        }
        return out;
    }

    private BizSource pickNormalSource() {
        int r = ThreadLocalRandom.current().nextInt(100);
        if (r < 45) {
            return BizSource.ACCOUNT;
        }
        if (r < 80) {
            return BizSource.TRADE;
        }
        return BizSource.MARKETING;
    }

    private void dispatch(BizSource source, Map<BizSource, Integer> submitted,
            Map<BizSource, Integer> failed) {
        try {
            BizEvent event = generator.next(source);
            SendMsgReq req = new SendMsgReq();
            req.setTemplateId(provisioner.templateId(source));
            req.setTo(event.to());
            req.setSubject(source.displayName());
            req.setPriority(source.priority());
            req.setTemplateData(event.data());
            sendMsgService.SendMsg(req);
            submitted.merge(source, 1, Integer::sum);
        } catch (Exception e) {
            failed.merge(source, 1, Integer::sum);
            log.warn("业务线[{}]事件提交失败: {}", source.sourceId(), e.getMessage());
        }
    }
}
