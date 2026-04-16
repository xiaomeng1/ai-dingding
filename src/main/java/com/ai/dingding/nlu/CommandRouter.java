package com.ai.dingding.nlu;

import com.ai.dingding.handler.UserCommandHandler;
import com.ai.dingding.service.DingTalkMessageService;
import lombok.extern.log4j.Log4j2;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 根据 NLU 解析结果（ParseResult）将意图路由到 UserCommandHandler 对应的处理方法。
 */
@Log4j2
public class CommandRouter {

    /** 合法意图集合 */
    public static final Set<String> VALID_INTENTS = Set.of(
            "CREATE_USER", "SEARCH_USER", "STOP_USER", "START_USER",
            "EXPORT_EXAM", "STUDENT_STATS", "REGISTER_SUCCESS", "HELP"
    );

    private final UserCommandHandler handler;
    private final DingTalkMessageService messageService;

    public CommandRouter(UserCommandHandler handler, DingTalkMessageService messageService) {
        this.handler = handler;
        this.messageService = messageService;
    }

    /**
     * 根据 ParseResult 路由到对应处理逻辑。
     *
     * @param result           NLU 解析结果
     * @param conversationType 会话类型（"1"=私聊，"2"=群聊）
     * @param conversationId   会话 ID
     * @param senderId         发送者 ID（私聊时使用）
     */
    public void route(ParseResult result,
                      String conversationType, String conversationId, String senderId) {
        String intent = result.getIntent();
        Map<String, Object> params = result.getParams();

        switch (intent) {
            case "CREATE_USER": {
                String users = params != null ? toUsersString(params.get("users")) : null;
                List<String[]> userList = handler.parseBatchCreateArgs("创建用户 " + users);
                handler.handleBatchCreate(userList, conversationType, conversationId, senderId);
                break;
            }
            case "SEARCH_USER": {
                String nickName = params != null ? (String) params.get("nickName") : null;
                handler.handleSearch(nickName, conversationType, conversationId, senderId);
                break;
            }
            case "STOP_USER": {
                List<String> names = toNameList(params != null ? params.get("names") : null);
                handler.handleStopUsers(names, conversationType, conversationId, senderId);
                break;
            }
            case "START_USER": {
                List<String> names = toNameList(params != null ? params.get("names") : null);
                handler.handleStartUsers(names, conversationType, conversationId, senderId);
                break;
            }
            case "EXPORT_EXAM": {
                String studentName = params != null ? (String) params.get("studentName") : null;
                String timeRange = params != null ? (String) params.get("timeRange") : null;
                String region = params != null ? (String) params.get("region") : null;
                String arg;
                if (studentName != null) {
                    arg = "学生 " + studentName + (timeRange != null ? " " + timeRange : "");
                } else {
                    arg = timeRange + (region != null ? " " + region : "");
                }
                handler.handleExportExam(arg, conversationType, conversationId, senderId);
                break;
            }
            case "STUDENT_STATS": {
                String timeRange = params != null ? (String) params.get("timeRange") : null;
                handler.handleStudentStats(timeRange, conversationType, conversationId, senderId);
                break;
            }
            case "REGISTER_SUCCESS": {
                String users = params != null ? toUsersString(params.get("users")) : null;
                List<String[]> userList = handler.parseBatchCreateArgs("报名 " + users);
                handler.handleRegisterSuccess(userList, conversationType, conversationId, senderId);
                break;
            }
            case "HELP": {
                String helpText = handler.buildHelpText();
                handler.reply(conversationType, conversationId, senderId, helpText);
                break;
            }
            default: {
                log.warn("未知意图：{}，回复帮助文本", intent);
                String helpText = handler.buildHelpText();
                handler.reply(conversationType, conversationId, senderId, helpText);
                break;
            }
        }
    }

    /**
     * 将 params.users 统一转为字符串，兼容模型返回字符串或数组两种格式。
     * 字符串："张三 138...,李四 139..."
     * 数组：["张三 138...", "李四 139..."] → "张三 138...,李四 139..."
     */
    @SuppressWarnings("unchecked")
    private String toUsersString(Object usersVal) {
        if (usersVal == null) return null;
        if (usersVal instanceof String) return (String) usersVal;
        if (usersVal instanceof List) {
            List<Object> list = (List<Object>) usersVal;
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) sb.append(',');
                sb.append(list.get(i));
            }
            return sb.toString();
        }
        return usersVal.toString();
    }

    /**
     * 将 params.names 转为姓名列表，兼容字符串（逗号分隔）和数组两种格式。
     */
    @SuppressWarnings("unchecked")
    private List<String> toNameList(Object namesVal) {
        if (namesVal == null) return List.of();
        List<String> result = new java.util.ArrayList<>();
        if (namesVal instanceof List) {
            for (Object o : (List<Object>) namesVal) {
                if (o != null) {
                    String s = o.toString().trim();
                    if (!s.isBlank()) result.add(s);
                }
            }
        } else {
            for (String part : namesVal.toString().split("[,，]+")) {
                String s = part.trim();
                if (!s.isBlank()) result.add(s);
            }
        }
        return result;
    }
}
