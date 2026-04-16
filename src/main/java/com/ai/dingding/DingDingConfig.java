package com.ai.dingding;

import com.ai.dingding.handler.UserCommandHandler;
import com.ai.dingding.nlu.BaiLianClient;
import com.ai.dingding.nlu.CommandRouter;
import com.ai.dingding.nlu.NluService;
import com.ai.dingding.service.DingTalkMessageService;
import com.ai.dingding.service.RegistrationService;
import com.ai.dingding.service.SystemApiService;
import com.dingtalk.open.app.api.OpenDingTalkStreamClientBuilder;
import com.dingtalk.open.app.api.security.AuthClientCredential;
import lombok.extern.log4j.Log4j2;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import shade.com.alibaba.fastjson2.JSON;
import shade.com.alibaba.fastjson2.JSONObject;

/**
 * Spring 配置类：初始化钉钉机器人所需的各个服务 Bean。
 */
@Log4j2
@Configuration
public class DingDingConfig {

    @Bean
    public SystemApiService systemApiService() {
        return new SystemApiService();
    }

    @Bean
    public DingTalkMessageService dingTalkMessageService() {
        String appKey = System.getProperty("ding.appKey");
        return new DingTalkMessageService(appKey);
    }

    @Bean
    public UserCommandHandler userCommandHandler(
            SystemApiService systemApiService,
            DingTalkMessageService dingTalkMessageService,
            RegistrationService registrationService) {
        String baiLianApiKey = System.getProperty("bailian.apiKey");
        return buildCommandHandler(baiLianApiKey, systemApiService, dingTalkMessageService, registrationService);
    }

    /**
     * 启动钉钉 Stream 客户端监听（应用启动后立即执行）。
     */
    @Bean
    public CommandLineRunner dingTalkStreamRunner(
            UserCommandHandler commandHandler,
            DingTalkMessageService dingTalkMessageService) {
        return args -> {
            validateRequiredProperties();
            String appKey = System.getProperty("ding.appKey");
            String appSecret = System.getProperty("ding.appSecret");

            OpenDingTalkStreamClientBuilder
                    .custom()
                    .credential(new AuthClientCredential(appKey, appSecret))
                    .registerCallbackListener("/v1.0/im/bot/messages/get", robotMessage -> {
                        log.info("receive robotMessage: {}", robotMessage);
                        try {
                            String msgStr = JSON.toJSONString(robotMessage);
                            JSONObject msg = JSON.parseObject(msgStr);
                            if (msg == null) {
                                return new JSONObject();
                            }

                            String openConvId = msg.getString("openConversationId");
                            String conversationId = (openConvId == null || openConvId.isBlank())
                                    ? msg.getString("conversationId")
                                    : openConvId;

                            if (conversationId == null || conversationId.isBlank()) {
                                log.warn("消息中缺少 conversationId，无法回复，消息：{}", msgStr);
                                return new JSONObject();
                            }

                            String content = extractContent(msg);
                            if (content == null || content.isBlank()) {
                                log.info("消息内容为空，忽略");
                                return new JSONObject();
                            }

                            String conversationType = msg.getString("conversationType");
                            String senderStaffId = msg.getString("senderStaffId");

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

            log.info("钉钉 Stream 客户端已启动");
        };
    }

    private UserCommandHandler buildCommandHandler(String baiLianApiKey,
            SystemApiService systemApiService,
            DingTalkMessageService dingTalkMessageService,
            RegistrationService registrationService) {
        if (baiLianApiKey != null && !baiLianApiKey.isBlank()) {
            try {
                BaiLianClient baiLianClient = new BaiLianClient();
                NluService nluService = new NluService(baiLianClient);
                UserCommandHandler tempHandler = new UserCommandHandler(
                        systemApiService, dingTalkMessageService, registrationService);
                CommandRouter commandRouter = new CommandRouter(tempHandler, dingTalkMessageService);
                return new UserCommandHandler(
                        systemApiService, dingTalkMessageService,
                        registrationService, nluService, commandRouter);
            } catch (IllegalStateException e) {
                log.warn("NLU 初始化失败：{}，将使用无 NLU 模式", e.getMessage());
            }
        } else {
            log.warn("bailian.apiKey 未配置，NLU 功能未启用");
        }
        return new UserCommandHandler(systemApiService, dingTalkMessageService, registrationService);
    }

    private void validateRequiredProperties() {
        String[] required = {"ding.appKey", "ding.appSecret", "sys.username", "sys.password"};
        for (String key : required) {
            if (System.getProperty(key) == null || System.getProperty(key).isBlank()) {
                throw new IllegalStateException("缺少必要启动参数：-D" + key + "=xxx，请参考 README 启动说明");
            }
        }
    }

    private String extractContent(JSONObject msg) {
        JSONObject text = msg.getJSONObject("text");
        if (text != null) {
            String content = text.getString("content");
            if (content != null) return content;
        }
        return msg.getString("content");
    }
}
