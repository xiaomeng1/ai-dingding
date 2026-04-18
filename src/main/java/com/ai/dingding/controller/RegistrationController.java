package com.ai.dingding.controller;

import com.ai.dingding.service.RegistrationService;
import lombok.extern.log4j.Log4j2;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import shade.com.alibaba.fastjson2.JSONObject;

import java.util.List;
import java.util.Map;

/**
 * 报名信息管理控制器。
 * - GET  /registrations          → 报名管理页面
 * - GET  /api/registrations       → 全部报名记录（JSON）
 * - GET  /api/registrations/unapproved?field=ding&name=张三 → 未审批用户（JSON）
 * - POST /api/registrations/approve → 更新审批状态（JSON）
 */
@Log4j2
@Controller
@RequestMapping("/registrations")
public class RegistrationController {

    private final RegistrationService registrationService;

    public RegistrationController(
            RegistrationService registrationService,
            @Value("${registration.maxDingApprovalCount:3}") int maxDingApprovalCount,
            @Value("${registration.maxMeetingApprovalCount:3}") int maxMeetingApprovalCount) {
        this.registrationService = registrationService;
        this.registrationService.setMaxDingApprovalCount(maxDingApprovalCount);
        this.registrationService.setMaxMeetingApprovalCount(maxMeetingApprovalCount);
    }

    /** 报名管理页面 */
    @GetMapping
    public String page(Model model) {
        model.addAttribute("pageTitle", "报名管理");
        return "registrations";
    }

    // ---- REST API ----

    /** 查询全部报名记录 */
    @GetMapping("/api")
    @ResponseBody
    public ResponseEntity<ApiResult> getAll() {
        try {
            List<JSONObject> all = registrationService.findAll();
            return ResponseEntity.ok(ApiResult.ok(all));
        } catch (Exception e) {
            log.error("查询全部报名记录异常", e);
            return ResponseEntity.internalServerError()
                    .body(ApiResult.error(e.getMessage()));
        }
    }

    /**
     * 查询存在未审批项的用户（钉钉群或会议录播有任意一个未审批）。
     * 返回姓名列表。
     *
     * GET /api/pending-names
     */
    @GetMapping("/api/pending-names")
    @ResponseBody
    public ResponseEntity<ApiResult> getPendingNames() {
        try {
            List<String> names = registrationService.findPendingAny();
            return ResponseEntity.ok(ApiResult.ok(names));
        } catch (Exception e) {
            log.error("查询未审批用户姓名异常", e);
            return ResponseEntity.internalServerError()
                    .body(ApiResult.error(e.getMessage()));
        }
    }

    /** 查询未审批用户 */
    @GetMapping("/api/unapproved")
    @ResponseBody
    public ResponseEntity<ApiResult> getUnapproved(
            @RequestParam("field") String field,
            @RequestParam(value = "name", required = false) String name) {
        if (field == null || field.isBlank()) {
            return ResponseEntity.badRequest()
                    .body(ApiResult.error("field 参数不能为空，支持 ding 或 meeting"));
        }
        RegistrationService.ApprovalField af = RegistrationService.ApprovalField.fromParam(field);
        if (af == null) {
            return ResponseEntity.badRequest()
                    .body(ApiResult.error("field 仅支持 ding 或 meeting"));
        }
        try {
            List<JSONObject> results = registrationService.findUnapproved(af, name);
            return ResponseEntity.ok(ApiResult.ok(results));
        } catch (Exception e) {
            log.error("查询未审批用户异常", e);
            return ResponseEntity.internalServerError()
                    .body(ApiResult.error(e.getMessage()));
        }
    }

    /** 更新审批状态 */
    @PostMapping("/api/approve")
    @ResponseBody
    public ResponseEntity<ApiResult> updateApproval(@RequestBody Map<String, Object> body) {
        String name = (String) body.get("name");
        if (name == null || name.isBlank()) {
            return ResponseEntity.badRequest()
                    .body(ApiResult.error("name 参数不能为空"));
        }
        Object dingObj = body.get("dingApproved");
        Object meetingObj = body.get("meetingApproved");
        Boolean dingApproved = dingObj != null ? (Boolean) dingObj : null;
        Boolean meetingApproved = meetingObj != null ? (Boolean) meetingObj : null;

        if (dingApproved == null && meetingApproved == null) {
            return ResponseEntity.badRequest()
                    .body(ApiResult.error("dingApproved 或 meetingApproved 至少提供一个"));
        }
        try {
            int rows = registrationService.updateApprovalByName(name, dingApproved, meetingApproved);
            return ResponseEntity.ok(ApiResult.ok(Map.of("updated", rows)));
        } catch (Exception e) {
            log.error("更新审批状态异常", e);
            return ResponseEntity.internalServerError()
                    .body(ApiResult.error(e.getMessage()));
        }
    }

    /** REST API 统一响应结构 */
    public static class ApiResult {
        public int code;
        public Object data;
        public String error;

        public static ApiResult ok(Object data) {
            ApiResult r = new ApiResult();
            r.code = 200;
            r.data = data;
            return r;
        }

        public static ApiResult error(String msg) {
            ApiResult r = new ApiResult();
            r.code = 500;
            r.error = msg;
            return r;
        }
    }
}
