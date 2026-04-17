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
               - 智能地区合并规则：如果用户在姓名和手机号之间夹了一个地区名，需要将地区名合并进姓名，格式为"姓名（地区）"
                 * 判断依据：姓名后紧跟的词语不是纯数字手机号，且语义上是地名（城市、区县、地区等），则视为地区
                 * 已知地区参考（不限于此，模型应自动识别其他地名）：五家渠、奇台、北屯市、哈密、福海、乌鲁木齐、石河子、克拉玛依、伊宁、塔城、阿勒泰、博乐、库尔勒、阿克苏、喀什、和田、仙台市 等
                 * 合并示例："张三 五家渠 13800138000" → params.users = "张三（五家渠） 13800138000"
                 * 合并示例："董建玲 仙台市 18899980812" → params.users = "董建玲（仙台市） 18899980812"
                 * 合并示例："李四 13900139000"（无地区）→ params.users = "李四 13900139000"（不变）
                 * 批量示例："张三 五家渠 13800138000,李四 13900139000" → params.users = "张三（五家渠） 13800138000,李四 13900139000"

            2. SEARCH_USER（查询用户）
               - params.nickName：要查询的用户昵称、姓名或手机号
               - 用户可能输入姓名（如"张三"）或手机号（如"13800138000"），统一放入 nickName 字段

            3. STOP_USER（停用用户）
               - params.names：要停用的用户姓名或手机号列表，多个用英文逗号分隔，例如：张三,李四 或 13800138000,13900139000
               - 支持姓名和手机号混合输入，例如：张三,13900139000
               - 用户可能说"停用"、"禁用"、"封号"等

            4. START_USER（启用用户）
               - params.names：要启用的用户姓名或手机号列表，多个用英文逗号分隔，例如：张三,李四 或 13800138000,13900139000
               - 支持姓名和手机号混合输入，例如：张三,13900139000
               - 用户可能说"启用"、"解封"、"恢复"等

            5. EXPORT_EXAM（导出考试数据）
               - params.timeRange（必填）：时间范围，可以是"近一周"、"近一个月"，或自定义日期范围（格式：yyyy-MM-dd yyyy-MM-dd，起止日期用空格分隔）
               - 时间同义词映射（必须转换为标准格式）：
                 * "上个月"、"这个月"、"本月"、"上月"、"最近一个月"、"近一个月"、"上个月的" → "近一个月"
                 * "上周"、"这周"、"本周"、"最近一周"、"近一周"、"最近7天"、"上周的" → "近一周"
                 * 斜杠日期如"2026/04/01 2026/04/16"需转换为"2026-04-01 2026-04-16"
               - 示例："导出上个月的考试数据" → intent=EXPORT_EXAM, params.timeRange="近一个月"
               - 示例："导出上个月的考试记录" → intent=EXPORT_EXAM, params.timeRange="近一个月"
               - 示例："导出上周的考试记录" → intent=EXPORT_EXAM, params.timeRange="近一周"
               - 示例："帮我导出上周的考试记录" → intent=EXPORT_EXAM, params.timeRange="近一周"
               - params.region（可选）：地区筛选
               - params.studentName（可选）：学生姓名筛选，指定学生姓名时按学生导出
               - 注意：region 和 studentName 互斥，同一指令中不应同时出现

            6. STUDENT_STATS（学生统计）
               - params.timeRange（必填）：时间范围，可以是"近一周"、"近一个月"，或自定义日期范围（格式：yyyy-MM-dd yyyy-MM-dd）
               - 时间同义词映射（必须转换为标准格式）：
                 * "上个月"、"这个月"、"本月"、"上月"、"最近一个月"、"近一个月" → "近一个月"
                 * "上周"、"这周"、"本周"、"最近一周"、"近一周"、"最近7天" → "近一周"
                 * 斜杠日期如"2026/04/01 2026/04/16"需转换为"2026-04-01 2026-04-16"

            7. REGISTER_SUCCESS（报名成功）
               - params.users：要报名的用户列表，格式为"姓名 手机号"，多个用户用英文逗号分隔，例如：张三 13800138000,李四 13900139000

            8. HELP（帮助）
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

            if (obj == null) {
                log.warn("NLU 返回内容无法解析为 JSON，原始内容: {}", rawContent);
                return ParseResult.ofUnrecognized("无法解析指令，请重新输入");
            }

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
