package cn.bitoffer.msgcenter.biz.web;

import cn.bitoffer.msgcenter.biz.common.BizEmitResult;
import cn.bitoffer.msgcenter.biz.common.BizEvent;
import cn.bitoffer.msgcenter.biz.common.BizSimulationResult;
import cn.bitoffer.msgcenter.biz.common.BizSimulationService;
import cn.bitoffer.msgcenter.biz.common.BizSource;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 业务发送接口，需登录。
 *
 * @author LQH
 */
@RestController
@RequestMapping("/api/hub")
public class HubSimulationController {

    private final BizSimulationService bizSimulationService;

    public HubSimulationController(BizSimulationService bizSimulationService) {
        this.bizSimulationService = bizSimulationService;
    }

    @PostMapping("/simulate")
    public BizSimulationResult simulate(
            @RequestParam(defaultValue = "60") int count,
            @RequestParam(defaultValue = "false") boolean includeLark) {
        return bizSimulationService.simulate(count, includeLark);
    }

    @GetMapping("/sample")
    public ResponseEntity<BizEvent> sample(@RequestParam String source) {
        BizSource biz = BizSource.fromSourceId(source);
        if (biz == null) {
            return ResponseEntity.badRequest().build();
        }
        return ResponseEntity.ok(bizSimulationService.sample(biz));
    }

    @PostMapping("/emit")
    public ResponseEntity<BizEmitResult> emit(@RequestBody EmitReq req) {
        BizSource biz = BizSource.fromSourceId(req == null ? null : req.source());
        if (biz == null) {
            return ResponseEntity.badRequest().build();
        }
        return ResponseEntity.ok(bizSimulationService.emit(biz, req.to(), req.data()));
    }

    /**
     * 手动发送请求。
     *
     * @author LQH
     */
    public record EmitReq(String source, String to, Map<String, String> data) {
    }
}
