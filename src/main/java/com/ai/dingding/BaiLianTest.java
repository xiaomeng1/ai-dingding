package com.ai.dingding;
// 该代码 OpenAI SDK 版本为 2.6.0
import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.models.chat.completions.ChatCompletion;
import com.openai.models.chat.completions.ChatCompletionCreateParams;

public class BaiLianTest {
    public static void main(String[] args) {
        OpenAIClient client = OpenAIOkHttpClient.builder()
                .apiKey("sk-9727bbdd53e1409085a265ebbfe8a9be")
                .baseUrl("https://dashscope.aliyuncs.com/compatible-mode/v1")
                .build();

        ChatCompletionCreateParams params = ChatCompletionCreateParams.builder()
                .addUserMessage("你是谁")
                .model("qwen-plus")
                .build();

        try {
            ChatCompletion chatCompletion = client.chat().completions().create(params);
            //ChatCompletion{id=chatcmpl-f18a83fd-4e55-9408-b3fb-7783f54e17dd, choices=[Choice{finishReason=stop, index=0, logprobs=, message=ChatCompletionMessage{content=你好！我是通义千问（Qwen），阿里巴巴集团旗下的超大规模语言模型。我能够回答问题、创作文字，比如写故事、写公文、写邮件、写剧本、逻辑推理、编程等等，还能表达观点，玩游戏等。如果你有任何问题或需要帮助，欢迎随时告诉我！😊, refusal=, role=assistant, annotations=, audio=, functionCall=, toolCalls=, additionalProperties={}}, additionalProperties={}}], created=1776057519, model=qwen-plus, object_=chat.completion, serviceTier=, systemFingerprint=, usage=CompletionUsage{completionTokens=66, promptTokens=10, totalTokens=76, completionTokensDetails=, promptTokensDetails=PromptTokensDetails{audioTokens=, cachedTokens=0, additionalProperties={}}, additionalProperties={}}, additionalProperties={}}
            System.out.println(chatCompletion);
        } catch (Exception e) {
            System.err.println("Error occurred: " + e.getMessage());
            e.printStackTrace();
        }
    }
}



