package com.ai.dingding;

import com.ai.dingding.handler.UserCommandHandler;
import com.ai.dingding.service.DingTalkMessageService;
import com.ai.dingding.service.SystemApiService;
import com.dingtalk.open.app.api.OpenDingTalkStreamClientBuilder;
import com.dingtalk.open.app.api.security.AuthClientCredential;
import lombok.extern.log4j.Log4j2;
import shade.com.alibaba.fastjson2.JSON;
import shade.com.alibaba.fastjson2.JSONObject;

@Log4j2
public class DingDingMain {

    private static final String APP_KEY =
            "ding3wlhmzygb3m67t3i";
    private static final String APP_SECRET =
            "cL-ivK-CwQJKsz7VZI2R7EEsk2pDwm0cnlSD4av_wbZbpdw1I4yh6aF1DSKtklFx";

    public static void main(String[] args) throws Exception {
        SystemApiService systemApiService = new SystemApiService();
        DingTalkMessageService dingTalkMessageService =
                new DingTalkMessageService(APP_KEY);
        UserCommandHandler commandHandler =
                new UserCommandHandler(systemApiService, dingTalkMessageService);

        OpenDingTalkStreamClientBuilder
                .custom()
                .credential(new AuthClientCredential(APP_KEY, APP_SECRET))
                .registerCallbackListener("/v1.0/im/bot/messages/get", robotMessage -> {
                    log.info("receive robotMessage: {}", robotMessage);
                    try {
                        // 将消息体序列化为 JSON 字符串，兼容 SDK 各版本的消息类型
                        String msgStr = JSON.toJSONString(robotMessage);
                        JSONObject msg = JSON.parseObject(msgStr);
                        if (msg == null) {
                            return new JSONObject();
                        }

                        // 优先取 openConversationId（发送群消息时使用），
                        // 不存在则降级使用 conversationId
                        String conversationId = msg.getString("openConversationId");
                        if (conversationId == null || conversationId.isBlank()) {
                            conversationId = msg.getString("conversationId");
                        }

                        if (conversationId == null || conversationId.isBlank()) {
                            log.warn("消息中缺少 conversationId，无法回复，消息：{}", msgStr);
                            return new JSONObject();
                        }

                        // 提取文本内容（钉钉机器人消息 text.content 字段）
                        String content = extractContent(msg);
                        if (content == null || content.isBlank()) {
                            log.info("消息内容为空，忽略");
                            return new JSONObject();
                        }

                        // 重新组装一个干净的消息对象传给 handler
                        JSONObject normalizedMsg = new JSONObject();
                        normalizedMsg.put("conversationId", conversationId);
                        JSONObject textObj = new JSONObject();
                        textObj.put("content", content.trim());
                        normalizedMsg.put("text", textObj);

                        commandHandler.handle(normalizedMsg.toJSONString());
                    } catch (Exception e) {
                        log.error("处理钉钉消息异常", e);
                    }
                    return new JSONObject();
                })
                .build().start();
    }

    /**
     * 从消息体中提取文本内容，兼容多种字段结构。
     */
    private static String extractContent(JSONObject msg) {
        // 标准格式：text.content
        JSONObject text = msg.getJSONObject("text");
        if (text != null) {
            String content = text.getString("content");
            if (content != null) {
                return content;
            }
        }
        // 部分版本直接有 content 字段
        return msg.getString("content");
    }
}
