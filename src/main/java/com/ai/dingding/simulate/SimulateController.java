package com.ai.dingding.simulate;

import com.ai.dingding.handler.UserCommandHandler;
import lombok.extern.log4j.Log4j2;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import shade.com.alibaba.fastjson2.JSON;
import shade.com.alibaba.fastjson2.JSONArray;
import shade.com.alibaba.fastjson2.JSONObject;

import java.util.List;

/**
 * 模拟钉钉消息发送的测试接口，仅在 dev profile 下激活。
 *
 * POST /simulate/message
 * {
 *   "content": "创建用户 张三 13800138000",
 *   "conversationType": "2",          // 可选，默认 "2"（群聊）
 *   "conversationId": "test-conv-001", // 可选
 *   "senderId": "test-sender-001"      // 可选
 * }
 *
 * 响应：
 * {
 *   "replies": [
 *     { "type": "text", "target": "test-conv-001", "text": "用户创建成功..." },
 *     { "type": "file", "target": "test-conv-001", "fileName": "考试记录_xxx.xlsx" }
 *   ]
 * }
 */
@Log4j2
@Profile("dev")
@RestController
@RequestMapping("/simulate")
public class SimulateController {

    private final UserCommandHandler commandHandler;
    private final ReplyCapture replyCapture;

    public SimulateController(UserCommandHandler commandHandler, ReplyCapture replyCapture) {
        this.commandHandler = commandHandler;
        this.replyCapture = replyCapture;
    }

    @PostMapping(value = "/message", consumes = "application/json", produces = "application/json")
    public ResponseEntity<String> simulate(@RequestBody String rawBody)
            throws InterruptedException {

        JSONObject body = JSON.parseObject(rawBody);
        if (body == null) {
            return ResponseEntity.badRequest()
                    .contentType(MediaType.APPLICATION_JSON)
                    .body("{\"error\":\"请求体不是合法的 JSON\"}");
        }

        String content = body.getString("content");
        if (content == null || content.isBlank()) {
            return ResponseEntity.badRequest()
                    .contentType(MediaType.APPLICATION_JSON)
                    .body("{\"error\":\"content 不能为空\"}");
        }

        String conversationType = body.getString("conversationType");
        if (conversationType == null || conversationType.isBlank()) conversationType = "2";
        String conversationId = body.getString("conversationId");
        if (conversationId == null || conversationId.isBlank()) conversationId = "sim-conv-001";
        String senderId = body.getString("senderId");
        if (senderId == null || senderId.isBlank()) senderId = "sim-sender-001";

        // 构造与真实钉钉回调相同格式的消息体
        JSONObject textObj = new JSONObject();
        textObj.put("content", content.trim());

        JSONObject msg = new JSONObject();
        msg.put("conversationId", conversationId);
        msg.put("conversationType", conversationType);
        msg.put("senderId", senderId);
        msg.put("text", textObj);

        log.info("[模拟] 发送消息: {}", content);

        // 开启回复捕获，然后触发处理逻辑
        replyCapture.begin();
        commandHandler.handle(msg.toJSONString());

        // 等待回复（最多 30 秒，导出等异步操作需要时间）
        List<ReplyCapture.ReplyItem> replies = replyCapture.drain(30);

        log.info("[模拟] 收到 {} 条回复", replies.size());

        // 用 fastjson2 序列化响应，避免依赖 Jackson
        JSONArray repliesArr = new JSONArray();
        for (ReplyCapture.ReplyItem item : replies) {
            JSONObject r = new JSONObject();
            r.put("type", item.type());
            r.put("target", item.target());
            if (item.text() != null) r.put("text", item.text());
            if (item.fileName() != null) r.put("fileName", item.fileName());
            repliesArr.add(r);
        }
        JSONObject resp = new JSONObject();
        resp.put("replies", repliesArr);

        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body(resp.toJSONString());
    }
}
