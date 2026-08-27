package cn.bitoffer.msgcenter.core.web;

import cn.bitoffer.common.model.ResponseEntity;
import cn.bitoffer.msgcenter.core.model.MsgRecordModel;
import cn.bitoffer.msgcenter.core.model.TemplateModel;
import cn.bitoffer.msgcenter.core.model.dto.SendMsgReq;
import cn.bitoffer.msgcenter.core.service.MsgRecordService;
import cn.bitoffer.msgcenter.core.service.SendMsgService;
import cn.bitoffer.msgcenter.core.service.TemplateService;
import cn.bitoffer.msgcenter.core.tenant.TenantContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;
import javax.validation.Valid;
import java.util.List;

/**
 * 消息内核 HTTP 接口：模板管理与发送。
 *
 * @author LQH
 */

@RestController
@RequestMapping("/msg")
@Slf4j
public class MsgCenterController {

    @Resource
    private TemplateService templateService;

    @Resource
    private SendMsgService sendMsgService;

    @Resource
    private MsgRecordService msgRecordService;

    @PostMapping(value = "/create_template")
    public ResponseEntity<String> createTemplate(
            @RequestBody @Validated(TemplateModel.OnCreate.class) TemplateModel templateModel){
        String templateId = templateService.CreateTemplate(templateModel);
        return ResponseEntity.ok(templateId);
    }

    @GetMapping(value = "/get_template")
    public ResponseEntity<TemplateModel> getTemplate(@RequestParam(value = "templateId") String templateId){
        TemplateModel templateModel = templateService.getMine(templateId);
        return ResponseEntity.ok(templateModel);
    }

    @GetMapping(value = "/list_templates")
    public ResponseEntity<List<TemplateModel>> listTemplates(
            @RequestParam(value = "name", required = false) String name) {
        return ResponseEntity.ok(templateService.listMine(name));
    }

    @PostMapping(value = "/update_template")
    public ResponseEntity<Void> updateTemplate(
            @RequestBody @Validated(TemplateModel.OnUpdate.class) TemplateModel templateModel){
        templateService.UpdateTemplate(templateModel);
        return ResponseEntity.ok();
    }

    @PostMapping(value = "/del_template")
    public ResponseEntity<Void> delTemplate(@RequestParam(value = "templateId") String templateId){
        templateService.DeleteTemplate(templateId);
        return ResponseEntity.ok();
    }

    @PostMapping(value = "/send_msg")
    public ResponseEntity<String> send_msg(@RequestBody @Valid SendMsgReq sendMsgReq){
        String msgId = sendMsgService.SendMsg(sendMsgReq);
        return ResponseEntity.ok(msgId);
    }

    @GetMapping(value = "/get_msg_record")
    public ResponseEntity<MsgRecordModel> getMsgRecord(@RequestParam(value = "msgId") String msgId){
        MsgRecordModel rec = msgRecordService.GetMsgRecordWithCache(msgId);
        if (rec != null && rec.getTenantId() != null) {
            String tenant = TenantContext.require();
            if (!tenant.equals(rec.getTenantId())) {
                return ResponseEntity.ok(null);
            }
        }
        return ResponseEntity.ok(rec);
    }
}
