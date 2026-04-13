package com.ai.dingding.nlu;

import lombok.extern.log4j.Log4j2;
import shade.com.alibaba.fastjson2.JSON;
import shade.com.alibaba.fastjson2.JSONObject;

import java.util.Map;

/**
 * 自然语言理解服务，负责调用百炼大模型将用户自然语言消息解析为结构化 {@link ParseResult}。
 */
@Log4j2
public class NluService {

    static final String SYSTEM_PROMPT = """
            你是一个钉钉机器人指令解析助手，负责将用户的自然语言消息转换为结构化 JSON 指令。
            
            支持的指令如下：
            
            1. CREATE_USER（创建用户）
               - params.users：要创建的用户列表，格式为"姓名 手机号"，多个用户用英文逗号分隔，例如：张三 13800138000,李四 13900139000
            
            2. SEARCH_USER（查询用户）
               - params.nickName：要查询的用户昵称或姓名
            
            3. EXPORT_EXAM（导出考试数据）
               - params.timeRange（必填）：时间范围，可以是"近一周"、"近一个月"，或自定义日期范围（格式：yyyy-MM-dd yyyy-MM-dd，起止日期用空格分隔）
               - params.region（可选）：地区筛选
               - params.studentName（可选）：学生姓名筛选
            
            4. STUDENT_STATS（学生统计）
               - params.timeRange（必填）：时间范围，可以是"近一周"、"近一个月"，或自定义日期范围（格式：yyyy-MM-dd yyyy-MM-dd）
            
            5. HELP（帮助）
               - 无需参数，用户询问使用方法、帮助、怎么用、支持什么功能，或发送"帮助"、"help"、"？"、"?"时使用
            
            输出格式要求：
            - 仅输出纯 JSON，不包含任何 Markdown 代码块标记（禁止使用 ```json 或 ``` 包裹）
            - 识别成功时输出：{"intent": "<指令名>", "params": {<参数键值对>}}
            - 无法识别或缺少必要参数时输出：{"unrecognized": true, "hint": "<给用户的中文提示>"}
            - 当用户消息缺少必要参数时，在 hint 中明确告知缺少哪些信息，并给出示例
            """;

    private final BaiLianClient baiLianClient;

    public NluService(BaiLianClient baiLianClient) {
        this.baiLianClient = baiLianClient;
    }

    /**
     * 去除模型返回中可能存在的 Markdown 代码块标记。
     * 例如：```json\n{...}\n``` → {...}
     */
    static String stripMarkdownCodeBlock(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        // Remove ```json or ``` prefix
        if (trimmed.startsWith("```")) {
            int newlineIdx = trimmed.indexOf('\n');
            if (newlineIdx != -1) {
                trimmed = trimmed.substring(newlineIdx + 1);
            } else {
                trimmed = trimmed.substring(3);
            }
        }
        // Remove ``` suffix
        if (trimmed.endsWith("```")) {
            trimmed = trimmed.substring(0, trimmed.length() - 3);
        }
        return trimmed.trim();
    }

    /**
     * 解析用户自然语言消息为结构化指令。
     *
     * @param userText 用户消息文本
     * @return ParseResult，永不返回 null；解析失败时返回 unrecognized=true 的结果
     */
    public ParseResult parse(String userText) {
        String rawContent = null;
        try {
            rawContent = baiLianClient.chat(SYSTEM_PROMPT, userText);
            log.info("百炼原始返回: {}", rawContent);

            String cleanedJson = stripMarkdownCodeBlock(rawContent);
            JSONObject obj = JSON.parseObject(cleanedJson);

            if (obj.getBooleanValue("unrecognized")) {
                String hint = obj.getString("hint");
                return ParseResult.ofUnrecognized(hint);
            }

            String intent = obj.getString("intent");
            Map<String, Object> params = obj.getObject("params", Map.class);

            ParseResult result = new ParseResult();
            result.setIntent(intent);
            result.setParams(params);
            return result;

        } catch (Exception e) {
            log.warn("NLU 解析失败，原始内容: {}", rawContent, e);
            return ParseResult.ofUnrecognized(rawContent);
        }
    }
}
