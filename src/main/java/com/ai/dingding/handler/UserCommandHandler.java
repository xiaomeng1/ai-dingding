package com.ai.dingding.handler;

import com.ai.dingding.license.LicenseChecker;
import com.ai.dingding.service.DingTalkMessageService;
import com.ai.dingding.service.SystemApiService;
import lombok.extern.log4j.Log4j2;
import shade.com.alibaba.fastjson2.JSON;
import shade.com.alibaba.fastjson2.JSONObject;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

/**
 * 解析钉钉机器人消息，路由到对应操作，并将结果回复到群。
 *
 * 支持指令格式：
 *   创建用户 <用户名> <手机号>
 *   搜索用户 <用户名>
 *   删除用户 <用户名>
 *   导出考试记录 近一周 | 近一个月 | <开始日期> <结束日期>
 *   统计新增学员 近一个月 | <开始日期> <结束日期>
 */
@Log4j2
public class UserCommandHandler {

    private static final String CMD_CREATE = "创建用户";
    private static final String CMD_SEARCH = "搜索用户";
    private static final String CMD_DELETE = "删除用户";
    private static final String CMD_EXPORT_EXAM = "导出考试记录";
    private static final String CMD_STUDENT_STATS = "统计新增学员";

    private static final DateTimeFormatter API_DATE_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter INPUT_DATE_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter FILE_DATE_FORMATTER =
            DateTimeFormatter.ofPattern("yyyyMMdd");

    /** 长耗时操作（导出/统计）在后台线程执行，避免阻塞钉钉回调 */
    private final ExecutorService asyncExecutor = Executors.newCachedThreadPool();

    private final SystemApiService systemApiService;
    private final DingTalkMessageService dingTalkMessageService;

    public UserCommandHandler(SystemApiService systemApiService,
                              DingTalkMessageService dingTalkMessageService) {
        this.systemApiService = systemApiService;
        this.dingTalkMessageService = dingTalkMessageService;
    }

    /**
     * 处理一条机器人消息回调。
     *
     * @param robotMessage 钉钉 Stream SDK 传入的原始消息体（JSON 字符串）
     */
    public void handle(String robotMessage) {
        try {
            JSONObject msg = JSON.parseObject(robotMessage);
            if (msg == null) {
                log.warn("robotMessage 解析为空，原始内容：{}", robotMessage);
                return;
            }

            String text = extractText(msg);
            String conversationId = msg.getString("conversationId");
            // "1"=私聊，"2"=群聊；私聊时用 senderId 发私信，群聊时用 conversationId
            String conversationType = msg.getString("conversationType");
            String senderId = msg.getString("senderId");

            if (text == null || text.isBlank()) {
                log.info("消息内容为空，忽略");
                return;
            }
            if (conversationId == null || conversationId.isBlank()) {
                log.warn("conversationId 缺失，无法回复，消息：{}", robotMessage);
                return;
            }
            // 私聊场景必须有 senderId，否则无法定位回复目标
            if ("1".equals(conversationType) && (senderId == null || senderId.isBlank())) {
                log.warn("私聊消息缺少 senderId，无法回复，消息：{}", robotMessage);
                return;
            }

            // 授权检查：每次指令请求时验证 Gitee 授权状态（30s 缓存）
            if (!LicenseChecker.isActive()) {
                String stopMsg = LicenseChecker.getMessage();
                reply(conversationType, conversationId, senderId,
                        stopMsg.isBlank() ? "系统维护中，暂停服务" : stopMsg);
                log.warn("系统未授权，拒绝指令：{}", text);
                return;
            }

            text = text.trim();
            log.info("收到指令：[{}]，conversationId：{}，conversationType：{}",
                    text, conversationId, conversationType);

            if (text.startsWith(CMD_CREATE)) {
                List<String[]> users = parseBatchCreateArgs(text);
                handleBatchCreate(users, conversationType, conversationId, senderId);
            } else if (text.startsWith(CMD_SEARCH)) {
                handleSearch(parseArg(text, CMD_SEARCH), conversationType, conversationId, senderId);
            } else if (text.startsWith(CMD_DELETE)) {
                handleDelete(parseArg(text, CMD_DELETE), conversationType, conversationId, senderId);
            } else if (text.startsWith(CMD_EXPORT_EXAM)) {
                String arg = parseArg(text, CMD_EXPORT_EXAM);
                handleExportExam(arg, conversationType, conversationId, senderId);
            } else if (text.startsWith(CMD_STUDENT_STATS)) {
                String arg = parseArg(text, CMD_STUDENT_STATS);
                handleStudentStats(arg, conversationType, conversationId, senderId);
            } else {
                reply(conversationType, conversationId, senderId, buildHelpText());
            }
        } catch (Exception e) {
            log.error("处理机器人消息异常，消息：{}", robotMessage, e);
        }
    }

    // -------- 指令处理 --------

    /**
     * 批量创建用户，支持一次传入多条（逗号分隔），逐条调用 API 并汇总结果。
     */
    private void handleBatchCreate(List<String[]> users,
                                   String conversationType, String conversationId, String senderId) {
        if (users.isEmpty()) {
            reply(conversationType, conversationId, senderId,
                    "参数不完整，格式：\n"
                            + "  单个：创建用户 张三 13800138000\n"
                            + "  批量：创建用户 张三 13800138000,李四 13900139000");
            return;
        }

        if (users.size() == 1) {
            String[] u = users.get(0);
            if (u[0].isBlank()) {
                reply(conversationType, conversationId, senderId,
                        "用户名不能为空，格式：创建用户 <用户名> <手机号>");
                return;
            }
            if (u[1].isBlank()) {
                reply(conversationType, conversationId, senderId,
                        "手机号不能为空，格式：创建用户 <用户名> <手机号>");
                return;
            }
            String result = systemApiService.createUser(u[0], u[1]);
            reply(conversationType, conversationId, senderId,
                    buildCreateReply(u[0], u[1], result));
            return;
        }

        // 批量模式：逐条创建，汇总结果
        StringBuilder sb = new StringBuilder();
        sb.append("批量创建结果（共 ").append(users.size()).append(" 人）：\n");
        int attemptCount = 0;
        int successCount = 0;
        for (String[] u : users) {
            if (u[0].isBlank() || u[1].isBlank()) {
                sb.append("[跳过] 格式错误：「").append(u[0]).append(" ").append(u[1]).append("」\n");
                continue;
            }
            attemptCount++;
            String result = systemApiService.createUser(u[0], u[1]);
            boolean ok = isCreateSuccess(result);
            if (ok) {
                successCount++;
            }
            sb.append(ok ? "[成功] " : "[失败] ")
                    .append(u[0]).append("（").append(u[1]).append("）");
            if (!ok) {
                sb.append(" - ").append(extractCreateError(result));
            }
            sb.append("\n");
        }
        sb.append("---\n共成功 ").append(successCount).append(" / ").append(attemptCount).append(" 人");
        reply(conversationType, conversationId, senderId, sb.toString());
    }

    private void handleSearch(String nickName,
                              String conversationType, String conversationId, String senderId) {
        if (nickName == null || nickName.isBlank()) {
            reply(conversationType, conversationId, senderId,
                    "用户名不能为空，格式：搜索用户 <用户名>");
            return;
        }

        List<JSONObject> users = systemApiService.searchUser(nickName);
        reply(conversationType, conversationId, senderId,
                buildSearchReply(nickName, users));
    }

    private void handleDelete(String nickName,
                              String conversationType, String conversationId, String senderId) {
        if (nickName == null || nickName.isBlank()) {
            reply(conversationType, conversationId, senderId,
                    "用户名不能为空，格式：删除用户 <用户名>");
            return;
        }

        List<JSONObject> users = systemApiService.searchUser(nickName);
        if (users.isEmpty()) {
            reply(conversationType, conversationId, senderId,
                    "未找到用户「" + nickName + "」，删除取消");
            return;
        }

        if (users.size() > 1) {
            reply(conversationType, conversationId, senderId,
                    "找到多个同名用户（" + users.size() + " 个），请提供更精确的用户名：\n"
                            + buildSearchReply(nickName, users));
            return;
        }

        JSONObject user = users.get(0);
        Long userId = user.getLong("userId");
        String actualName = user.getString("nickName");

        if (userId == null) {
            reply(conversationType, conversationId, senderId, "获取用户 ID 失败，删除取消");
            return;
        }

        String result = systemApiService.deleteUser(userId);
        reply(conversationType, conversationId, senderId,
                buildDeleteReply(actualName, userId, result));
    }

    /**
     * 导出考试记录，在后台线程执行（避免阻塞钉钉回调超时）。
     * 支持：近一周 / 近一个月 / yyyy-MM-dd yyyy-MM-dd
     */
    private void handleExportExam(String arg,
                                   String conversationType, String conversationId, String senderId) {
        String[] range = parseTimeRange(arg);
        if (range == null) {
            reply(conversationType, conversationId, senderId,
                    "时间格式不正确，支持：\n"
                            + "  导出考试记录 近一周\n"
                            + "  导出考试记录 近一个月\n"
                            + "  导出考试记录 2026-04-01 2026-04-11");
            return;
        }

        String beginStart = range[0];
        String beginEnd = range[1];
        reply(conversationType, conversationId, senderId,
                "正在导出考试记录（" + formatRangeLabel(beginStart, beginEnd) + "），请稍候...");

        asyncExecutor.submit(() -> {
            try {
                byte[] fileData = systemApiService.exportExamRecords(beginStart, beginEnd);
                if (fileData == null || fileData.length == 0) {
                    reply(conversationType, conversationId, senderId,
                            "导出失败：服务端未返回文件数据，请检查时间范围后重试");
                    return;
                }

                log.info("收到导出文件，大小 {} 字节，插入区域列后上传钉钉", fileData.length);
                fileData = com.ai.dingding.service.ExcelService.addRegionColumn(fileData);
                String fileName = buildExportFileName(beginStart, beginEnd);
                replyFile(conversationType, conversationId, senderId, fileName, fileData);
            } catch (Exception e) {
                log.error("导出考试记录异步任务异常", e);
                reply(conversationType, conversationId, senderId, "导出考试记录时发生异常，请联系管理员");
            }
        });
    }

    /**
     * 统计各地区新增学员，在后台线程执行。
     * 支持：近一个月 / yyyy-MM-dd yyyy-MM-dd
     */
    private void handleStudentStats(String arg,
                                     String conversationType, String conversationId, String senderId) {
        String[] range = parseTimeRange(arg);
        if (range == null) {
            reply(conversationType, conversationId, senderId,
                    "时间格式不正确，支持：\n"
                            + "  统计新增学员 近一个月\n"
                            + "  统计新增学员 2026-03-01 2026-04-11");
            return;
        }

        String beginStart = range[0];
        String beginEnd = range[1];
        reply(conversationType, conversationId, senderId,
                "正在统计新增学员（" + formatRangeLabel(beginStart, beginEnd) + "），请稍候...");

        asyncExecutor.submit(() -> {
            try {
                LocalDateTime begin = LocalDateTime.parse(beginStart, API_DATE_FORMATTER);
                LocalDateTime end = LocalDateTime.parse(beginEnd, API_DATE_FORMATTER);

                List<JSONObject> allStudents = systemApiService.fetchAllStudents();

                // 过滤出指定时间范围内创建的学员
                List<JSONObject> filtered = allStudents.stream()
                        .filter(u -> isInRange(u.getString("createTime"), begin, end))
                        .collect(Collectors.toList());

                // 从 nickName 末尾括号中提取区域，兼容中英文括号，无法提取时归入"未知区域"
                Map<String, Long> regionCount = filtered.stream()
                        .collect(Collectors.groupingBy(
                                u -> extractRegionFromNickName(u.getString("nickName")),
                                Collectors.counting()
                        ));

                Map<String, Long> sortedRegionCount = regionCount.entrySet().stream()
                        .sorted(Map.Entry.<String, Long>comparingByValue(Comparator.reverseOrder()))
                        .collect(Collectors.toMap(
                                Map.Entry::getKey,
                                Map.Entry::getValue,
                                (a, b) -> a,
                                LinkedHashMap::new
                        ));

                String statsMsg = buildStudentStatsReply(beginStart, beginEnd,
                        filtered.size(), sortedRegionCount);
                reply(conversationType, conversationId, senderId, statsMsg);
            } catch (Exception e) {
                log.error("统计新增学员异步任务异常", e);
                reply(conversationType, conversationId, senderId, "统计学员时发生异常，请联系管理员");
            }
        });
    }

    // -------- 响应消息格式化 --------

    /** 判断创建用户 API 响应是否成功 */
    private boolean isCreateSuccess(String rawResult) {
        if (rawResult == null) {
            return false;
        }
        try {
            JSONObject resp = JSON.parseObject(rawResult);
            return resp != null && resp.getInteger("code") == 200;
        } catch (Exception e) {
            return false;
        }
    }

    /** 从创建用户失败响应中提取错误描述 */
    private String extractCreateError(String rawResult) {
        if (rawResult == null) {
            return "服务异常";
        }
        try {
            JSONObject resp = JSON.parseObject(rawResult);
            String msg = resp != null ? resp.getString("msg") : null;
            return msg != null ? msg : rawResult;
        } catch (Exception e) {
            return rawResult;
        }
    }

    private String buildCreateReply(String nickName, String phone, String rawResult) {
        if (rawResult == null) {
            return "创建用户「" + nickName + "」失败：服务异常，请稍后重试";
        }
        try {
            JSONObject resp = JSON.parseObject(rawResult);
            if (resp != null && resp.getInteger("code") == 200) {
                return "用户创建成功\n"
                        + "用户名：" + nickName + "\n"
                        + "手机号：" + phone + "\n"
                        + "初始密码：123456\n"
                        + "省份：新疆省\n"
                        + "站点：乌鲁木齐站\n"
                        + "级别：中级监控";
            }
            String msg = resp != null ? resp.getString("msg") : rawResult;
            return "创建用户「" + nickName + "」失败：" + msg;
        } catch (Exception e) {
            return "创建用户「" + nickName + "」失败：" + rawResult;
        }
    }

    private String buildSearchReply(String nickName, List<JSONObject> users) {
        if (users.isEmpty()) {
            return "未找到用户「" + nickName + "」";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("搜索「").append(nickName).append("」找到 ")
                .append(users.size()).append(" 条记录：\n");
        sb.append("----------------------------\n");

        for (int i = 0; i < users.size(); i++) {
            JSONObject u = users.get(i);
            sb.append(i + 1).append(". ").append(u.getString("nickName")).append("\n");
            sb.append("   ID：").append(u.getLong("userId")).append("\n");
            sb.append("   手机：").append(nullToEmpty(u.getString("phone"))).append("\n");
            sb.append("   站点：").append(nullToEmpty(u.getString("region"))).append("\n");
            sb.append("   级别：").append(nullToEmpty(u.getString("level"))).append("\n");
            sb.append("   到期：").append(nullToEmpty(u.getString("useTime"))).append("\n");
            sb.append("   状态：").append(
                    "0".equals(u.getString("accountStatus")) ? "正常" : "停用").append("\n");
            if (i < users.size() - 1) {
                sb.append("----------------------------\n");
            }
        }
        return sb.toString();
    }

    private String buildDeleteReply(String nickName, long userId, String rawResult) {
        if (rawResult == null) {
            return "删除用户「" + nickName + "」(ID: " + userId + ") 失败：服务异常";
        }
        try {
            JSONObject resp = JSON.parseObject(rawResult);
            if (resp != null && resp.getInteger("code") == 200) {
                return "用户删除成功\n"
                        + "用户名：" + nickName + "\n"
                        + "ID：" + userId;
            }
            String msg = resp != null ? resp.getString("msg") : rawResult;
            return "删除用户「" + nickName + "」失败：" + msg;
        } catch (Exception e) {
            return "删除用户「" + nickName + "」失败：" + rawResult;
        }
    }

    private String buildStudentStatsReply(String beginStart, String beginEnd,
                                          int total, Map<String, Long> regionCount) {
        String begin = beginStart.substring(0, 10);
        String end = beginEnd.substring(0, 10);

        StringBuilder sb = new StringBuilder();
        sb.append("📊 新增学员统计\n");
        sb.append("时间：").append(begin).append(" ~ ").append(end).append("\n");
        sb.append("合计：").append(total).append(" 人\n");
        sb.append("────────────────────\n");

        if (regionCount.isEmpty()) {
            sb.append("（该时间段内无新增学员）");
        } else {
            // 区域名最大显示宽度（中文算2字符）
            int maxRegionWidth = regionCount.keySet().stream()
                    .mapToInt(k -> displayWidth(k.isBlank() ? "未知区域" : k))
                    .max().orElse(4);
            // 数量最大位数（右对齐用）
            int maxCountWidth = regionCount.values().stream()
                    .mapToInt(v -> String.valueOf(v).length())
                    .max().orElse(1);

            for (Map.Entry<String, Long> entry : regionCount.entrySet()) {
                String region = entry.getKey().isBlank() ? "未知区域" : entry.getKey();
                String count = String.valueOf(entry.getValue());
                int regionPadding = maxRegionWidth - displayWidth(region);
                int countPadding = maxCountWidth - count.length();
                sb.append(region)
                        .append(" ".repeat(Math.max(0, regionPadding)))
                        .append("  ")
                        .append(" ".repeat(Math.max(0, countPadding)))
                        .append(count)
                        .append(" 人\n");
            }
        }
        return sb.toString().stripTrailing();
    }

    /**
     * 计算字符串的显示宽度：ASCII 字符算 1，中文及全角字符算 2。
     */
    private int displayWidth(String s) {
        int width = 0;
        for (char c : s.toCharArray()) {
            width += (c > 0xFF) ? 2 : 1;
        }
        return width;
    }

    private String buildExportFileName(String beginStart, String beginEnd) {
        String begin = LocalDate.parse(beginStart.substring(0, 10), INPUT_DATE_FORMATTER)
                .format(FILE_DATE_FORMATTER);
        String end = LocalDate.parse(beginEnd.substring(0, 10), INPUT_DATE_FORMATTER)
                .format(FILE_DATE_FORMATTER);
        return "考试记录_" + begin + "_" + end + ".xlsx";
    }

    private String buildHelpText() {
        return "暂不支持该指令，支持格式：\n"
                + "  创建用户 <用户名> <手机号>\n"
                + "  创建用户 <用户名1> <手机号1>,<用户名2> <手机号2>\n"
                + "  搜索用户 <用户名>\n"
                + "  删除用户 <用户名>\n"
                + "  导出考试记录 近一周\n"
                + "  导出考试记录 近一个月\n"
                + "  导出考试记录 2026-04-01 2026-04-11\n"
                + "  统计新增学员 近一个月\n"
                + "  统计新增学员 2026-03-01 2026-04-11";
    }

    // -------- 消息路由 --------

    /**
     * 统一回复入口：根据 conversationType 路由到群消息或私聊消息接口。
     * "1" = 私聊，其余视为群聊。
     */
    private void reply(String conversationType, String conversationId,
                       String senderId, String content) {
        if ("1".equals(conversationType)) {
            dingTalkMessageService.sendPrivateTextMessage(senderId, content);
        } else {
            dingTalkMessageService.sendTextMessage(conversationId, content);
        }
    }

    /**
     * 统一文件回复入口：根据 conversationType 路由到群文件或私聊文件接口。
     */
    private void replyFile(String conversationType, String conversationId,
                           String senderId, String fileName, byte[] fileData) {
        if ("1".equals(conversationType)) {
            dingTalkMessageService.sendPrivateExamFile(senderId, fileName, fileData);
        } else {
            dingTalkMessageService.sendExamFile(conversationId, fileName, fileData);
        }
    }

    // -------- 工具方法 --------

    /** 从机器人消息中提取文本内容 */
    private String extractText(JSONObject msg) {
        JSONObject text = msg.getJSONObject("text");
        if (text != null) {
            return text.getString("content");
        }
        return msg.getString("content");
    }

    /**
     * 解析单参数指令（搜索/删除/导出/统计），去除 @机器人 提及部分。
     *
     * @return 参数字符串，可能为空字符串
     */
    private String parseArg(String text, String cmd) {
        String arg = text.substring(cmd.length()).trim();
        if (arg.startsWith("@")) {
            int spaceIdx = arg.indexOf(' ');
            arg = spaceIdx > 0 ? arg.substring(spaceIdx).trim() : "";
        }
        return arg;
    }

    /**
     * 解析创建用户指令，返回 [nickName, phone]。
     * 指令格式：创建用户 <用户名> <手机号>
     */
    /**
     * 解析批量创建用户参数，支持逗号分隔多个用户。
     * 格式：张三 13800138000,李四 13900139000
     * 每条格式：<用户名> <手机号>（空格分隔）
     *
     * @return 每个元素为 [nickName, phone] 的列表
     */
    private List<String[]> parseBatchCreateArgs(String text) {
        String args = text.substring(CMD_CREATE.length()).trim();
        if (args.startsWith("@")) {
            int spaceIdx = args.indexOf(' ');
            args = spaceIdx > 0 ? args.substring(spaceIdx).trim() : "";
        }

        List<String[]> result = new java.util.ArrayList<>();
        // 支持逗号或换行分隔多条用户
        String[] entries = args.split("[,，\n\r]+");
        for (String entry : entries) {
            entry = entry.trim();
            if (entry.isBlank()) {
                continue;
            }
            String[] parts = entry.split("\\s+", 2);
            String nickName = parts[0].trim();
            String phone = parts.length > 1 ? parts[1].trim() : "";
            result.add(new String[]{nickName, phone});
        }
        return result;
    }

    /**
     * 解析时间范围，返回 [beginStart, beginEnd]（格式 yyyy-MM-dd HH:mm:ss）。
     * 支持：近一周 / 近一个月 / yyyy-MM-dd yyyy-MM-dd
     *
     * @return 两元素数组，解析失败返回 null
     */
    private String[] parseTimeRange(String arg) {
        if (arg == null || arg.isBlank()) {
            return null;
        }
        arg = arg.trim();
        LocalDate today = LocalDate.now();

        if (arg.contains("近一周")) {
            return buildRange(today.minusDays(7), today);
        }
        if (arg.contains("近一个月")) {
            return buildRange(today.minusMonths(1), today);
        }

        // 尝试解析 "yyyy-MM-dd yyyy-MM-dd"
        String[] parts = arg.split("\\s+");
        if (parts.length >= 2) {
            try {
                LocalDate begin = LocalDate.parse(parts[0], INPUT_DATE_FORMATTER);
                LocalDate end = LocalDate.parse(parts[1], INPUT_DATE_FORMATTER);
                return buildRange(begin, end);
            } catch (DateTimeParseException e) {
                log.warn("时间范围解析失败，原始参数：{}", arg);
                return null;
            }
        }
        return null;
    }

    private String[] buildRange(LocalDate begin, LocalDate end) {
        String beginStart = begin.atStartOfDay().format(API_DATE_FORMATTER);
        String beginEnd = end.atTime(23, 59, 59).format(API_DATE_FORMATTER);
        return new String[]{beginStart, beginEnd};
    }

    /** 格式化时间范围标签（用于提示消息） */
    private String formatRangeLabel(String beginStart, String beginEnd) {
        return beginStart.substring(0, 10) + " 至 " + beginEnd.substring(0, 10);
    }

    /** 判断 createTime 字符串是否在 [begin, end] 范围内 */
    private boolean isInRange(String createTime, LocalDateTime begin, LocalDateTime end) {
        if (createTime == null || createTime.isBlank()) {
            return false;
        }
        try {
            LocalDateTime dt = LocalDateTime.parse(createTime, API_DATE_FORMATTER);
            return !dt.isBefore(begin) && !dt.isAfter(end);
        } catch (DateTimeParseException e) {
            return false;
        }
    }

    /**
     * 从 nickName 末尾的括号中提取区域信息。
     * 支持中文括号（）和英文括号()，优先匹配最后一对括号。
     * 示例："赵寅康（五家渠）" → "五家渠"，"张三(Urumqi)" → "Urumqi"
     * 无括号或括号内容为空时返回"未知区域"。
     */
    private String extractRegionFromNickName(String nickName) {
        if (nickName == null || nickName.isBlank()) {
            return "未知区域";
        }
        // 同时匹配中文括号（）和英文括号()，取最后一对括号内的内容
        int lastOpen = -1;
        int lastClose = -1;
        for (int i = nickName.length() - 1; i >= 0; i--) {
            char c = nickName.charAt(i);
            if ((c == '）' || c == ')') && lastClose == -1) {
                lastClose = i;
            } else if ((c == '（' || c == '(') && lastClose != -1) {
                lastOpen = i;
                break;
            }
        }
        if (lastOpen >= 0 && lastClose > lastOpen) {
            String region = nickName.substring(lastOpen + 1, lastClose).trim();
            return region.isBlank() ? "未知区域" : region;
        }
        return "未知区域";
    }

    private String nullToEmpty(String val) {
        return val == null ? "" : val;
    }
}
