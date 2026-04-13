package com.ai.dingding.nlu;

import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.models.chat.completions.ChatCompletion;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import lombok.extern.log4j.Log4j2;

@Log4j2
public class BaiLianClient {

    private static final String BASE_URL = "https://dashscope.aliyuncs.com/compatible-mode/v1";
    private static final String MODEL = "qwen-plus";

    private final OpenAIClient openAIClient;

    public BaiLianClient() {
        String apiKey = System.getProperty("bailian.apiKey");
        if (apiKey == null || apiKey.isBlank()) {
            log.error("bailian.apiKey 未配置，请通过 -Dbailian.apiKey=xxx 启动参数提供");
            throw new IllegalStateException("bailian.apiKey 未配置，请通过 -Dbailian.apiKey=xxx 启动参数提供");
        }
        this.openAIClient = OpenAIOkHttpClient.builder()
                .apiKey(apiKey)
                .baseUrl(BASE_URL)
                .build();
    }

    /**
     * 发送单轮对话请求。
     *
     * @param systemPrompt 系统提示词
     * @param userMessage  用户消息
     * @return 模型返回的原始文本
     * @throws RuntimeException 网络异常或超时时抛出
     */
    public String chat(String systemPrompt, String userMessage) {
        try {
            ChatCompletionCreateParams params = ChatCompletionCreateParams.builder()
                    .addSystemMessage(systemPrompt)
                    .addUserMessage(userMessage)
                    .model(MODEL)
                    .build();
            ChatCompletion chatCompletion = openAIClient.chat().completions().create(params);
            return chatCompletion.choices().get(0).message().content().orElse("");
        } catch (Exception e) {
            log.error("调用百炼接口异常", e);
            throw new RuntimeException("调用百炼接口异常: " + e.getMessage(), e);
        }
    }
}
