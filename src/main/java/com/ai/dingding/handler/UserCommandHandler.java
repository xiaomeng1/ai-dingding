package com.ai.dingding.handler;

import com.ai.dingding.license.LicenseChecker;
import com.ai.dingding.nlu.CommandRouter;
import com.ai.dingding.nlu.NluService;
import com.ai.dingding.nlu.ParseResult;
import com.ai.dingding.service.DingTalkMessageService;
import com.ai.dingding.service.RegistrationService;
import com.ai.dingding.service.SystemApiService;
import jakarta.annotation.PreDestroy;
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
    private static final String CMD_STOP = "停用用户";
    private static final String CMD_START = "启用用户";
    private static final String CMD_EXPORT_EXAM = "导出考试记录";
    private static final String CMD_STUDENT_STATS = "统计新增学员";

    private static final DateTimeFormatter API_DATE_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter INPUT_DATE_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter FILE_DATE_FORMATTER =
            DateTimeFormatter.ofPattern("yyyyMMdd");

    private enum ExportMode { ALL, REGION, STUDENT }

    /** 导出指令解析结果 */
    private static class ExportParams {
        /** nickName 筛选值：区域名或学生姓名，null 表示不筛选 */
        String nickName;
        /** 开始时间，格式 yyyy-MM-dd HH:mm:ss；null 表示不限时间 */
        String beginStart;
        /** 结束时间，格式 yyyy-MM-dd HH:mm:ss；null 表示不限时间 */
        String beginEnd;
        /** 用于文件名的可读标签（区域名或学生姓名） */
        String label;
        /** 筛选模式：REGION（按区域）、STUDENT（按学生）、ALL（全量，兼容旧逻辑） */
        ExportMode mode;
    }

    /** 长耗时操作（导出/统计）在后台线程执行，避免阻塞钉钉回调 */
    private final ExecutorService asyncExecutor = Executors.newCachedThreadPool();

    private final SystemApiService systemApiService;
    private final DingTalkMessageService dingTalkMessageService;
    private final RegistrationService registrationService;
    private final NluService nluService;
    private final CommandRouter commandRouter;

    public UserCommandHandler(SystemApiService systemApiService,
                              DingTalkMessageService dingTalkMessageService,
                              RegistrationService registrationService) {
        this(systemApiService, dingTalkMessageService, registrationService, null, null);
    }

    /** 测试便利构造函数（无 RegistrationService / NLU） */
    public UserCommandHandler(SystemApiService systemApiService,
                              DingTalkMessageService dingTalkMessageService) {
        this(systemApiService, dingTalkMessageService, null, null, null);
    }

    public UserCommandHandler(SystemApiService systemApiService,
                              DingTalkMessageService dingTalkMessageService,
                              NluService nluService,
                              CommandRouter commandRouter) {
        this(systemApiService, dingTalkMessageService, null, nluService, commandRouter);
    }

    public UserCommandHandler(SystemApiService systemApiService,
                              DingTalkMessageService dingTalkMessageService,
                              RegistrationService registrationService,
                              NluService nluService,
                              CommandRouter commandRouter) {
        this.systemApiService = systemApiService;
        this.dingTalkMessageService = dingTalkMessageService;
        this.registrationService = registrationService;
        this.nluService = nluService;
        this.commandRouter = commandRouter;
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

            if (nluService != null) {
                try {
                    ParseResult parseResult = nluService.parse(text);
                    if (parseResult.isUnrecognized()) {
                        reply(conversationType, conversationId, senderId, parseResult.getHint());
                        return;
                    }
                    commandRouter.route(parseResult, conversationType, conversationId, senderId);
                } catch (Exception e) {
                    log.error("NLU 解析异常", e);
                    reply(conversationType, conversationId, senderId,
                            "自然语言解析服务暂时不可用，请使用标准指令格式\n" + buildHelpText());
                }
            } else {
                reply(conversationType, conversationId, senderId,
                        "NLU 功能未启用，请使用标准指令格式\n" + buildHelpText());
            }
        } catch (Exception e) {
            log.error("处理机器人消息异常，消息：{}", robotMessage, e);
        }
    }

    // -------- 指令处理 --------

    /**
     * 批量创建用户，支持一次传入多条（逗号分隔），逐条调用 API 并汇总结果。
     */
    public void handleBatchCreate(List<String[]> users,
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
                sb.append(" - ").append(extractApiError(result));
            }
            sb.append("\n");
        }
        sb.append("---\n共成功 ").append(successCount).append(" / ").append(attemptCount).append(" 人");
        reply(conversationType, conversationId, senderId, sb.toString());
    }

    /**
     * 批量写入报名记录到 SQLite 内存数据库，支持一次传入多条（逗号分隔）。
     */
    public void handleRegisterSuccess(List<String[]> users,
                                       String conversationType, String conversationId, String senderId) {
        if (registrationService == null) {
            reply(conversationType, conversationId, senderId,
                    "报名功能暂不可用，请联系管理员");
            return;
        }
        if (users.isEmpty()) {
            reply(conversationType, conversationId, senderId,
                    "参数不完整，格式：\n"
                            + "  单个：报名成功 张三 13800138000\n"
                            + "  批量：报名成功 张三 13800138000,李四 13900139000");
            return;
        }

        // 过滤掉用户名为空或手机号为空
        List<String[]> validUsers = users.stream()
                .filter(u -> u[0] != null && !u[0].isBlank() && u[1] != null && !u[1].isBlank())
                .collect(Collectors.toList());
        if (validUsers.isEmpty()) {
            reply(conversationType, conversationId, senderId,
                    "用户名和手机号均不能为空，格式：报名成功 <姓名> <手机号>");
            return;
        }

        try {
            int inserted = registrationService.batchInsert(validUsers);
            String names = validUsers.stream()
                    .map(u -> u[0])
                    .collect(Collectors.joining("、"));
            if (inserted > 0) {
                reply(conversationType, conversationId, senderId,
                        "报名成功（新增 " + inserted + " 条）：" + names);
            } else {
                reply(conversationType, conversationId, senderId,
                        "报名记录已存在（" + names + "），手机号已更新");
            }
        } catch (Exception e) {
            log.error("写入报名记录异常", e);
            reply(conversationType, conversationId, senderId,
                    "报名成功写入失败：" + e.getMessage());
        }
    }

    public void handleSearch(String nickName,
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
     * 批量停用用户，支持逗号分隔多个姓名。
     * 先按姓名搜索拿到 userId，再调用停用接口。
     */
    public void handleStopUsers(List<String> names,
                                 String conversationType, String conversationId, String senderId) {
        handleToggleUsers(names, false, conversationType, conversationId, senderId);
    }

    /**
     * 批量启用用户，支持逗号分隔多个姓名。
     */
    public void handleStartUsers(List<String> names,
                                  String conversationType, String conversationId, String senderId) {
        handleToggleUsers(names, true, conversationType, conversationId, senderId);
    }

    private void handleToggleUsers(List<String> names, boolean enable,
                                    String conversationType, String conversationId, String senderId) {
        String action = enable ? "启用" : "停用";
        if (names == null || names.isEmpty()) {
            reply(conversationType, conversationId, senderId,
                    "用户名不能为空，格式：" + action + "用户 姓名1,姓名2");
            return;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("批量").append(action).append("结果（共 ").append(names.size()).append(" 人）：\n");
        int successCount = 0;

        for (String name : names) {
            if (name == null || name.isBlank()) continue;
            List<JSONObject> users = systemApiService.searchUser(name);
            if (users.isEmpty()) {
                sb.append("[失败] ").append(name).append(" - 未找到该用户\n");
                continue;
            }
            if (users.size() > 1) {
                sb.append("[失败] ").append(name).append(" - 找到 ").append(users.size())
                        .append(" 个同名用户，请提供更精确的姓名\n");
                continue;
            }
            JSONObject user = users.get(0);
            Long userId = user.getLong("userId");
            String actualName = user.getString("nickName");
            if (userId == null) {
                sb.append("[失败] ").append(name).append(" - 获取用户 ID 失败\n");
                continue;
            }
            String result = enable
                    ? systemApiService.startUser(userId)
                    : systemApiService.stopUser(userId);
            boolean ok = isApiSuccess(result);
            if (ok) successCount++;
            sb.append(ok ? "[成功] " : "[失败] ").append(actualName);
            if (!ok) {
                sb.append(" - ").append(extractApiError(result));
            }
            sb.append("\n");
        }
        sb.append("---\n共成功 ").append(successCount).append(" / ").append(names.size()).append(" 人");
        reply(conversationType, conversationId, senderId, sb.toString());
    }

    /**
     * 解析逗号分隔的姓名列表（停用/启用指令用）。
     */
    public List<String> parseBatchNames(String text, String cmd) {
        String args = text.substring(cmd.length()).trim();
        if (args.startsWith("@")) {
            int spaceIdx = args.indexOf(' ');
            args = spaceIdx > 0 ? args.substring(spaceIdx).trim() : "";
        }
        List<String> names = new java.util.ArrayList<>();
        for (String part : args.split("[,，\n\r]+")) {
            String name = part.trim();
            if (!name.isBlank()) names.add(name);
        }
        return names;
    }

    /**
     * 导出考试记录，在后台线程执行（避免阻塞钉钉回调超时）。
     * 支持：近一周 / 近一个月 / yyyy-MM-dd yyyy-MM-dd [区域名] / 学生 <姓名>
     */
    public void handleExportExam(String arg,
                                   String conversationType, String conversationId, String senderId) {
        ExportParams params = parseExportArg(arg);
        if (params == null) {
            reply(conversationType, conversationId, senderId, buildExportErrorHint());
            return;
        }

        String progressLabel = buildProgressLabel(params);
        reply(conversationType, conversationId, senderId,
                "正在导出考试记录（" + progressLabel + "），请稍候...");

        asyncExecutor.submit(() -> {
            try {
                String nickName = params.nickName != null ? params.nickName : "";
                String beginStart = params.beginStart != null ? params.beginStart : "";
                String beginEnd = params.beginEnd != null ? params.beginEnd : "";

                // 导出前预检：查询是否有考试记录
                int recordCount = systemApiService.searchExamRecords(nickName, beginStart, beginEnd);
                if (recordCount == 0) {
                    reply(conversationType, conversationId, senderId,
                            buildNoRecordReply(params));
                    return;
                }

                byte[] fileData = systemApiService.exportExamRecords(nickName, beginStart, beginEnd);
                if (fileData == null || fileData.length == 0) {
                    reply(conversationType, conversationId, senderId,
                            "导出失败：服务端未返回文件数据，请检查参数后重试");
                    return;
                }

                log.info("收到导出文件，大小 {} 字节，插入区域列后上传钉钉", fileData.length);
                boolean filterNoRegion = params.mode == ExportMode.REGION;
                fileData = com.ai.dingding.service.ExcelService.addRegionColumn(fileData, filterNoRegion);
                String fileName = buildExportFileName(params);
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
    public void handleStudentStats(String arg,
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
        return isApiSuccess(rawResult);
    }

    /** 判断任意 API 响应是否成功（code == 200） */
    private boolean isApiSuccess(String rawResult) {
        if (rawResult == null) return false;
        try {
            JSONObject resp = JSON.parseObject(rawResult);
            return resp != null && resp.getInteger("code") == 200;
        } catch (Exception e) {
            return false;
        }
    }

    /** 从失败响应中提取错误描述 */
    private String extractApiError(String rawResult) {
        if (rawResult == null) return "服务异常";
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

    private String buildExportFileName(ExportParams params) {
        switch (params.mode) {
            case STUDENT: {
                StringBuilder sb = new StringBuilder("考试记录_" + params.label);
                if (params.beginStart != null && params.beginEnd != null) {
                    String begin = LocalDate.parse(params.beginStart.substring(0, 10), INPUT_DATE_FORMATTER)
                            .format(FILE_DATE_FORMATTER);
                    String end = LocalDate.parse(params.beginEnd.substring(0, 10), INPUT_DATE_FORMATTER)
                            .format(FILE_DATE_FORMATTER);
                    sb.append("_").append(begin).append("_").append(end);
                }
                return sb.append(".xlsx").toString();
            }
            case REGION: {
                String begin = LocalDate.parse(params.beginStart.substring(0, 10), INPUT_DATE_FORMATTER)
                        .format(FILE_DATE_FORMATTER);
                String end = LocalDate.parse(params.beginEnd.substring(0, 10), INPUT_DATE_FORMATTER)
                        .format(FILE_DATE_FORMATTER);
                return "考试记录_" + params.label + "_" + begin + "_" + end + ".xlsx";
            }
            default: { // ALL — 兼容旧逻辑
                String begin = LocalDate.parse(params.beginStart.substring(0, 10), INPUT_DATE_FORMATTER)
                        .format(FILE_DATE_FORMATTER);
                String end = LocalDate.parse(params.beginEnd.substring(0, 10), INPUT_DATE_FORMATTER)
                        .format(FILE_DATE_FORMATTER);
                return "考试记录_" + begin + "_" + end + ".xlsx";
            }
        }
    }

    public String buildHelpText() {
        return "📖 功能说明 & 指令格式\n"
                + "━━━━━━━━━━━━━━━━━━━━━━━━\n"
                + "💡 支持自然语言，直接描述你想做的事即可，例如：\n"
                + "   「帮我创建用户 张三 五家渠 13800138000」\n"
                + "   「查一下李四这个人」\n"
                + "   「导出上周的考试记录」\n"
                + "   「停用 张三,李四」\n"
                + "\n"
                + "━━━━━━━━━━━━━━━━━━━━━━━━\n"
                + "👤 创建用户\n"
                + "  单个：创建用户 <姓名> <手机号>\n"
                + "  示例：创建用户 张三 13800138000\n"
                + "  带地区：创建用户 张三 五家渠 13800138000  →  用户名自动合并为「张三（五家渠）」\n"
                + "  批量：创建用户 <姓名1> <手机号1>,<姓名2> <手机号2>\n"
                + "  示例：创建用户 张三 13800138000,李四 13900139000\n"
                + "\n"
                + "🔴 停用用户\n"
                + "  格式：停用用户 <姓名1>,<姓名2>\n"
                + "  示例：停用用户 张三,李四\n"
                + "\n"
                + "🟢 启用用户\n"
                + "  格式：启用用户 <姓名1>,<姓名2>\n"
                + "  示例：启用用户 张三,李四\n"
                + "\n"
                + "📝 报名成功\n"
                + "  单个：报名成功 <姓名> <手机号>\n"
                + "  示例：报名成功 张三 13800138000\n"
                + "  批量：报名成功 <姓名1> <手机号1>,<姓名2> <手机号2>\n"
                + "\n"
                + "🔍 搜索用户\n"
                + "  格式：搜索用户 <姓名>\n"
                + "  示例：搜索用户 张三\n"
                + "\n"
                + "📊 统计新增学员\n"
                + "  格式：统计新增学员 <时间范围>\n"
                + "  示例：统计新增学员 近一个月\n"
                + "        统计新增学员 2026-03-01 2026-04-11\n"
                + "\n"
                + "📁 导出考试记录\n"
                + "  全量导出：\n"
                + "    导出考试记录 近一周\n"
                + "    导出考试记录 近一个月\n"
                + "    导出考试记录 2026-04-01 2026-04-11\n"
                + "  按区域导出：\n"
                + "    导出考试记录 近一周 <区域名>\n"
                + "  按学生导出：\n"
                + "    导出考试记录 学生 <学生姓名>\n"
                + "    导出考试记录 学生 <学生姓名> 近一周\n"
                + "\n"
                + "━━━━━━━━━━━━━━━━━━━━━━━━\n"
                + "❓ 发送「帮助」或「help」可随时查看本说明";
    }

    // -------- 消息路由 --------

    /**
     * 统一回复入口：根据 conversationType 路由到群消息或私聊消息接口。
     * "1" = 私聊，其余视为群聊。
     */
    public void reply(String conversationType, String conversationId,
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
    public void replyFile(String conversationType, String conversationId,
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
     * 解析批量创建用户参数，支持逗号分隔多个用户。
     * 格式：姓名 手机号，多个用逗号分隔。
     * 地区合并由 NLU 模型在上游完成，此处只做简单的"姓名 手机号"两段解析。
     *
     * @return 每个元素为 [nickName, phone] 的列表
     */
    public List<String[]> parseBatchCreateArgs(String text) {
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
            if (entry.isBlank()) continue;
            // 按第一个空白分割：左边是完整用户名（含括号地区），右边是手机号
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

    /**
     * 解析导出考试记录指令参数，返回 ExportParams。
     * 解析失败（格式不合法）返回 null。
     */
    private ExportParams parseExportArg(String arg) {
        if (arg == null || arg.isBlank()) {
            return null;
        }
        arg = arg.trim();
        LocalDate today = LocalDate.now();

        // 1. 学生模式
        if (arg.startsWith("学生 ") || arg.equals("学生")) {
            String rest = arg.substring("学生".length()).trim();
            if (rest.isBlank()) {
                return null;
            }

            ExportParams p = new ExportParams();
            p.mode = ExportMode.STUDENT;

            // 解析：学生 <姓名> [时间范围]
            // 检查末尾是否有 "近一周" 或 "近一个月"
            String name = rest;
            if (rest.endsWith("近一周")) {
                name = rest.substring(0, rest.length() - 3).trim();
                String[] range = buildRange(today.minusDays(7), today);
                p.beginStart = range[0];
                p.beginEnd = range[1];
            } else if (rest.endsWith("近一个月")) {
                name = rest.substring(0, rest.length() - 4).trim();
                String[] range = buildRange(today.minusMonths(1), today);
                p.beginStart = range[0];
                p.beginEnd = range[1];
            } else {
                // 尝试匹配末尾的自定义日期范围：yyyy-MM-dd yyyy-MM-dd
                String[] parts = rest.split("\\s+");
                if (parts.length >= 3) {
                    try {
                        LocalDate endDate = LocalDate.parse(parts[parts.length - 1], INPUT_DATE_FORMATTER);
                        LocalDate beginDate = LocalDate.parse(parts[parts.length - 2], INPUT_DATE_FORMATTER);
                        String[] range = buildRange(beginDate, endDate);
                        p.beginStart = range[0];
                        p.beginEnd = range[1];
                        // 姓名是去掉最后两个日期部分
                        StringBuilder sb = new StringBuilder();
                        for (int i = 0; i < parts.length - 2; i++) {
                            if (i > 0) sb.append(' ');
                            sb.append(parts[i]);
                        }
                        name = sb.toString().trim();
                    } catch (DateTimeParseException e) {
                        // 末尾不是日期，当作纯姓名
                        p.beginStart = null;
                        p.beginEnd = null;
                    }
                } else {
                    p.beginStart = null;
                    p.beginEnd = null;
                }
            }

            if (name.isBlank()) {
                return null;
            }
            p.nickName = name;
            p.label = name;
            return p;
        }

        // 2. 近一周
        if (arg.contains("近一周")) {
            String region = arg.replace("近一周", "").trim();
            String[] range = buildRange(today.minusDays(7), today);
            ExportParams p = new ExportParams();
            p.beginStart = range[0];
            p.beginEnd = range[1];
            if (region.isBlank()) {
                p.mode = ExportMode.ALL;
                p.nickName = null;
                p.label = null;
            } else {
                p.mode = ExportMode.REGION;
                p.nickName = region;
                p.label = region;
            }
            return p;
        }

        // 3. 近一个月
        if (arg.contains("近一个月")) {
            String region = arg.replace("近一个月", "").trim();
            String[] range = buildRange(today.minusMonths(1), today);
            ExportParams p = new ExportParams();
            p.beginStart = range[0];
            p.beginEnd = range[1];
            if (region.isBlank()) {
                p.mode = ExportMode.ALL;
                p.nickName = null;
                p.label = null;
            } else {
                p.mode = ExportMode.REGION;
                p.nickName = region;
                p.label = region;
            }
            return p;
        }

        // 4. 自定义日期（yyyy-MM-dd yyyy-MM-dd [区域名]）
        String[] parts = arg.split("\\s+", 3);
        if (parts.length >= 2) {
            try {
                LocalDate begin = LocalDate.parse(parts[0], INPUT_DATE_FORMATTER);
                LocalDate end = LocalDate.parse(parts[1], INPUT_DATE_FORMATTER);
                String region = parts.length >= 3 ? parts[2].trim() : "";
                String[] range = buildRange(begin, end);
                ExportParams p = new ExportParams();
                p.beginStart = range[0];
                p.beginEnd = range[1];
                if (region.isBlank()) {
                    p.mode = ExportMode.ALL;
                    p.nickName = null;
                    p.label = null;
                } else {
                    p.mode = ExportMode.REGION;
                    p.nickName = region;
                    p.label = region;
                }
                return p;
            } catch (DateTimeParseException e) {
                log.warn("导出参数日期解析失败，原始参数：{}", arg);
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

    /** 构建导出进度提示标签 */
    private String buildProgressLabel(ExportParams params) {
        switch (params.mode) {
            case STUDENT:
                if (params.beginStart != null && params.beginEnd != null) {
                    return "学生：" + params.label + "，" + formatRangeLabel(params.beginStart, params.beginEnd);
                }
                return "学生：" + params.label;
            case REGION:
                return params.label + "，" + formatRangeLabel(params.beginStart, params.beginEnd);
            default:
                return formatRangeLabel(params.beginStart, params.beginEnd);
        }
    }

    /** 构建无考试记录时的回复消息，拼装已知条件 */
    private String buildNoRecordReply(ExportParams params) {
        StringBuilder sb = new StringBuilder("未找到符合条件的考试记录\n");
        sb.append("查询条件：");
        switch (params.mode) {
            case STUDENT:
                sb.append("学生：").append(params.label);
                if (params.beginStart != null && params.beginEnd != null) {
                    sb.append("，时间：").append(formatRangeLabel(params.beginStart, params.beginEnd));
                }
                break;
            case REGION:
                sb.append("区域：").append(params.label);
                sb.append("，时间：").append(formatRangeLabel(params.beginStart, params.beginEnd));
                break;
            default:
                sb.append("时间：").append(formatRangeLabel(params.beginStart, params.beginEnd));
                break;
        }
        return sb.toString();
    }

    /** 构建导出参数格式错误提示 */
    private String buildExportErrorHint() {
        return "参数格式不正确，支持：\n"
                + "  导出考试记录 近一周\n"
                + "  导出考试记录 近一个月\n"
                + "  导出考试记录 2026-04-01 2026-04-11\n"
                + "  导出考试记录 近一周 <区域名>\n"
                + "  导出考试记录 近一个月 <区域名>\n"
                + "  导出考试记录 2026-04-01 2026-04-11 <区域名>\n"
                + "  导出考试记录 学生 <学生姓名>\n"
                + "  导出考试记录 学生 <学生姓名> 近一周\n"
                + "  导出考试记录 学生 <学生姓名> 近一个月\n"
                + "  导出考试记录 学生 <学生姓名> 2026-04-01 2026-04-11";
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

    /** 应用关闭时优雅关闭后台线程池，避免线程泄漏 */
    @PreDestroy
    public void shutdown() {
        asyncExecutor.shutdown();
        log.info("asyncExecutor 已关闭");
    }
}
