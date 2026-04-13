package com.ai.dingding;

import com.ai.dingding.handler.UserCommandHandler;
import com.ai.dingding.nlu.BaiLianClient;
import com.ai.dingding.nlu.CommandRouter;
import com.ai.dingding.nlu.NluService;
import com.ai.dingding.service.DingTalkMessageService;
import com.ai.dingding.service.SystemApiService;
import com.dingtalk.open.app.api.OpenDingTalkStreamClientBuilder;
import com.dingtalk.open.app.api.security.AuthClientCredential;
import lombok.extern.log4j.Log4j2;
import shade.com.alibaba.fastjson2.JSON;
import shade.com.alibaba.fastjson2.JSONObject;

@Log4j2
public class DingDingMain {

    /** 从 JVM 启动参数读取：-Dding.appKey=xxx */
    private static final String APP_KEY = System.getProperty("ding.appKey");
    /** 从 JVM 启动参数读取：-Dding.appSecret=xxx */
    private static final String APP_SECRET = System.getProperty("ding.appSecret");

    private static final String STARTUP_HELP =
            "启动命令示例：\n"
            + "java -Dding.appKey=xxx \\\n"
            + "     -Dding.appSecret=xxx \\\n"
            + "     -Dsys.username=xxx \\\n"
            + "     -Dsys.password=xxx \\\n"
            + "     -jar ai-dingding-0.0.1-SNAPSHOT.jar";

    public static void main(String[] args) throws Exception {
        // 启动前校验必要参数，缺少时打印清晰错误并退出
        validateRequiredProperties();

        SystemApiService systemApiService = new SystemApiService();
        DingTalkMessageService dingTalkMessageService =
                new DingTalkMessageService(APP_KEY);

        // NLU 初始化
        String baiLianApiKey = System.getProperty("bailian.apiKey");
        final UserCommandHandler commandHandler = buildCommandHandler(baiLianApiKey, systemApiService, dingTalkMessageService);

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
                        String openConvId = msg.getString("openConversationId");
                        final String conversationId = (openConvId == null || openConvId.isBlank())
                                ? msg.getString("conversationId")
                                : openConvId;

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

                        // conversationType: "1"=私聊，"2"=群聊
                        String conversationType = msg.getString("conversationType");
                        // 私聊回复需要发送人 staffId，群聊用 openConversationId
                        String senderStaffId = msg.getString("senderStaffId");

                        // 重新组装一个干净的消息对象传给 handler
                        JSONObject normalizedMsg = new JSONObject();
                        normalizedMsg.put("conversationId", conversationId);
                        normalizedMsg.put("conversationType", conversationType);
                        normalizedMsg.put("senderId", senderStaffId);
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
     * 构建 UserCommandHandler，根据是否配置了百炼 API Key 决定是否启用 NLU。
     */
    private static UserCommandHandler buildCommandHandler(String baiLianApiKey,
            SystemApiService systemApiService, DingTalkMessageService dingTalkMessageService) {
        if (baiLianApiKey != null && !baiLianApiKey.isBlank()) {
            try {
                BaiLianClient baiLianClient = new BaiLianClient();
                NluService nluService = new NluService(baiLianClient);
                UserCommandHandler tempHandler = new UserCommandHandler(systemApiService, dingTalkMessageService);
                CommandRouter commandRouter = new CommandRouter(tempHandler, dingTalkMessageService);
                return new UserCommandHandler(systemApiService, dingTalkMessageService, nluService, commandRouter);
            } catch (IllegalStateException e) {
                log.warn("NLU 初始化失败：{}，将使用无 NLU 模式", e.getMessage());
            }
        } else {
            log.warn("bailian.apiKey 未配置，NLU 功能未启用");
        }
        return new UserCommandHandler(systemApiService, dingTalkMessageService);
    }

    /**
     * 校验必要的 JVM 启动参数，任意一个缺失则打印提示并退出。
     */
    private static void validateRequiredProperties() {
        String[] required = {"ding.appKey", "ding.appSecret", "sys.username", "sys.password"};
        boolean hasError = false;
        for (String key : required) {
            if (System.getProperty(key) == null || System.getProperty(key).isBlank()) {
                log.error("缺少必要启动参数：-D{}=xxx", key);
                hasError = true;
            }
        }
        if (hasError) {
            log.error("启动失败，请按以下格式启动：\n{}", STARTUP_HELP);
            System.exit(1);
        }
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
