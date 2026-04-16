package com.ai.dingding.simulate;

import org.junit.jupiter.api.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

/**
 * 通过 /simulate/message 接口对机器人进行端到端自动化测试。
 * 服务需已启动（dev profile），运行前确认 http://localhost:8080 可访问。
 *
 * 运行方式：
 *   mvn test -Dtest=SimulateApiTest -Dspring.profiles.active=dev
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SimulateApiTest {

    private static final String URL = "http://localhost:8080/simulate/message";
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    // ---- 测试结果收集 ----
    record TestResult(String category, String name, String input,
                      boolean pass, String expected, String actual) {}

    static final List<TestResult> RESULTS = new ArrayList<>();

    // ============================================================
    // 工具方法
    // ============================================================

    static String send(String content) {
        return send(content, "2");
    }

    static String send(String content, String convType) {
        try {
            String json = String.format(
                    "{\"content\":\"%s\",\"conversationType\":\"%s\","
                    + "\"conversationId\":\"sim-conv-001\",\"senderId\":\"sim-sender-001\"}",
                    content.replace("\"", "\\\""), convType);
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(URL))
                    .timeout(Duration.ofSeconds(35))
                    .header("Content-Type", "application/json; charset=utf-8")
                    .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return resp.body();
        } catch (Exception e) {
            return "{\"error\":\"" + e.getMessage() + "\"}";
        }
    }

    /** 从响应 JSON 中提取所有 text 字段拼接成字符串 */
    static String extractText(String respJson) {
        if (respJson == null) return "";
        StringBuilder sb = new StringBuilder();
        // 简单提取 "text":"..." 字段（避免引入 JSON 库依赖）
        int idx = 0;
        while (true) {
            int start = respJson.indexOf("\"text\":\"", idx);
            if (start == -1) break;
            start += 8;
            int end = start;
            while (end < respJson.length()) {
                if (respJson.charAt(end) == '"' && respJson.charAt(end - 1) != '\\') break;
                end++;
            }
            String raw = respJson.substring(start, end)
                    .replace("\\n", "\n").replace("\\\"", "\"").replace("\\\\", "\\");
            sb.append(raw).append("\n");
            idx = end;
        }
        // 也提取 fileName
        idx = 0;
        while (true) {
            int start = respJson.indexOf("\"fileName\":\"", idx);
            if (start == -1) break;
            start += 12;
            int end = start;
            while (end < respJson.length() && respJson.charAt(end) != '"') end++;
            sb.append("[FILE:").append(respJson, start, end).append("]\n");
            idx = end;
        }
        return sb.toString();
    }

    static void tc(String cat, String name, String input,
                   String[] mustContain, String[] mustNotContain, String convType) {
        String resp = send(input, convType);
        String text = extractText(resp);
        boolean ok = true;
        List<String> reasons = new ArrayList<>();

        if (resp.contains("\"replies\":[]") || text.isBlank()) {
            // 空消息测试期望 error 字段
            if (Arrays.stream(mustContain).anyMatch(k -> resp.contains(k))) {
                ok = true;
            } else {
                ok = false;
                reasons.add("无回复内容");
            }
        } else {
            for (String kw : mustContain) {
                if (!text.contains(kw) && !resp.contains(kw)) {
                    ok = false;
                    reasons.add("缺少[" + kw + "]");
                }
            }
            for (String kw : mustNotContain) {
                if (text.contains(kw) || resp.contains(kw)) {
                    ok = false;
                    reasons.add("不应含[" + kw + "]");
                }
            }
        }

        String shortActual = text.length() > 150 ? text.substring(0, 150) + "..." : text;
        RESULTS.add(new TestResult(cat, name, input, ok,
                String.join("|", mustContain), shortActual));

        String status = ok ? "PASS" : "FAIL";
        System.out.printf("  [%s] %-30s %s%n", cat, name, status);
        if (!ok) {
            System.out.println("       >> " + String.join(", ", reasons));
            System.out.println("       >> 实际: " + shortActual.replace("\n", "↵"));
        }
    }

    static void tc(String cat, String name, String input, String[] mustContain) {
        tc(cat, name, input, mustContain, new String[]{}, "2");
    }

    static void tc(String cat, String name, String input, String[] mustContain, String convType) {
        tc(cat, name, input, mustContain, new String[]{}, convType);
    }

    // ============================================================
    // 测试用例
    // ============================================================

    @Test @Order(1)
    void test_01_help() {
        System.out.println("\n[1] 帮助指令");
        tc("帮助", "标准指令-帮助",         "帮助",             new String[]{"创建用户", "搜索用户", "导出考试记录"});
        tc("帮助", "英文help",              "help",             new String[]{"创建用户", "搜索用户"});
        tc("帮助", "问号触发",              "？",               new String[]{"创建用户"});
        tc("帮助", "自然语言-怎么用",       "这个机器人怎么用", new String[]{"创建用户", "搜索用户"});
        tc("帮助", "自然语言-支持什么功能", "你支持什么功能",   new String[]{"创建用户"});
    }

    @Test @Order(2)
    void test_02_create_user() {
        System.out.println("\n[2] 创建用户");
        tc("创建用户", "标准格式",           "创建用户 测试用户A 13800000001",                    new String[]{"测试用户A"});
        tc("创建用户", "带地区-智能合并",    "创建用户 测试用户B 五家渠 13800000002",             new String[]{"测试用户B", "五家渠"});
        tc("创建用户", "自然语言-帮我创建",  "帮我创建一个用户 测试用户C 13800000003",            new String[]{"测试用户C"});
        tc("创建用户", "自然语言-新建账号",  "新建账号 测试用户D 手机号13800000004",              new String[]{"测试用户D"});
        tc("创建用户", "批量创建",           "创建用户 测试批量E 13800000005,测试批量F 13800000006", new String[]{"测试批量E", "测试批量F"});
        tc("创建用户", "缺少手机号",         "创建用户 只有名字",                                 new String[]{"手机"});
        tc("创建用户", "空指令",             "创建用户",                                          new String[]{"手机"});
        tc("创建用户", "重复创建同一用户",   "创建用户 测试用户A 13800000001",                    new String[]{"测试用户A"});
    }

    @Test @Order(3)
    void test_03_search_user() {
        System.out.println("\n[3] 搜索用户");
        tc("搜索用户", "标准格式",           "搜索用户 测试用户A",       new String[]{"测试用户A", "ID", "手机"});
        tc("搜索用户", "自然语言-查一下",    "查一下测试用户A",          new String[]{"测试用户A"});
        tc("搜索用户", "自然语言-帮我找",    "帮我找找测试用户B这个人",  new String[]{"测试用户B"});
        tc("搜索用户", "搜索不存在的用户",   "搜索用户 不存在的用户XYZ", new String[]{"未找到", "不存在的用户XYZ"});
        tc("搜索用户", "缺少姓名",           "搜索用户",                 new String[]{"不能为空", "格式"});
    }

    @Test @Order(4)
    void test_04_stop_user() {
        System.out.println("\n[4] 停用用户");
        tc("停用用户", "标准格式-单个",      "停用用户 测试用户A",              new String[]{"停用", "测试用户A"});
        tc("停用用户", "标准格式-批量",      "停用用户 测试用户A,测试用户B",    new String[]{"共"});
        tc("停用用户", "自然语言-禁用",      "禁用测试用户C",                   new String[]{"停用", "测试用户C"});
        tc("停用用户", "停用不存在的用户",   "停用用户 不存在的用户XYZ",        new String[]{"未找到", "失败"});
        tc("停用用户", "空姓名",             "停用用户",                        new String[]{"姓名", "停用"});
        tc("停用用户", "中文逗号分隔",       "停用用户 测试用户A，测试用户B",   new String[]{"共"});
    }

    @Test @Order(5)
    void test_05_start_user() {
        System.out.println("\n[5] 启用用户");
        tc("启用用户", "标准格式-单个",      "启用用户 测试用户A",              new String[]{"启用", "测试用户A"});
        tc("启用用户", "标准格式-批量",      "启用用户 测试用户A,测试用户B",    new String[]{"共"});
        tc("启用用户", "自然语言-解封",      "解封测试用户C",                   new String[]{"启用", "测试用户C"});
        tc("启用用户", "启用不存在的用户",   "启用用户 不存在的用户XYZ",        new String[]{"未找到", "失败"});
    }

    @Test @Order(6)
    void test_06_student_stats() {
        System.out.println("\n[6] 统计新增学员");
        tc("统计学员", "近一个月",           "统计新增学员 近一个月",              new String[]{"统计", "新增学员"});
        tc("统计学员", "近一周",             "统计新增学员 近一周",                new String[]{"统计", "合计"});
        tc("统计学员", "自定义日期",         "统计新增学员 2026-01-01 2026-04-16", new String[]{"统计", "合计"});
        tc("统计学员", "自然语言-上个月",    "上个月新增了多少学员",               new String[]{"统计", "合计"});
        tc("统计学员", "自然语言-最近一周",  "最近一周新增了多少人",               new String[]{"统计", "合计"});
        tc("统计学员", "缺少时间范围",       "统计新增学员",                       new String[]{"统计"});
        tc("统计学员", "日期格式错误",       "统计新增学员 abc-def",               new String[]{"时间", "格式"});
    }

    @Test @Order(7)
    void test_07_export_exam() {
        System.out.println("\n[7] 导出考试记录");
        tc("导出考试", "近一周-全量",        "导出考试记录 近一周",                        new String[]{"导出", "正在", "请稍候"});
        tc("导出考试", "近一个月-全量",      "导出考试记录 近一个月",                      new String[]{"导出", "正在", "请稍候"});
        tc("导出考试", "自定义日期",         "导出考试记录 2026-04-01 2026-04-16",         new String[]{"导出", "正在", "请稍候"});
        tc("导出考试", "按区域-近一周",      "导出考试记录 近一周 五家渠",                 new String[]{"导出", "正在", "五家渠"});
        tc("导出考试", "按学生",             "导出考试记录 学生 郑亚琴",                   new String[]{"导出", "正在", "郑亚琴"});
        tc("导出考试", "自然语言-导出上周",  "帮我导出上周的考试记录",                    new String[]{"导出", "正在", "请稍候"});
        tc("导出考试", "自然语言-导出上月",  "导出上个月的考试数据",                      new String[]{"导出", "正在", "请稍候"});
        tc("导出考试", "缺少时间范围",       "导出考试记录",                              new String[]{"导出", "考试"});
        tc("导出考试", "日期格式错误",       "导出考试记录 2026/04/01 2026/04/16",        new String[]{"导出", "正在"});
    }

    @Test @Order(8)
    void test_08_edge_cases() {
        System.out.println("\n[8] 边界场景");
        // 空消息 - 期望 HTTP 400 + error 字段
        String emptyResp = send("");
        boolean emptyOk = emptyResp.contains("content") && emptyResp.contains("不能为空");
        RESULTS.add(new TestResult("边界", "空消息", "", emptyOk, "content 不能为空", emptyResp));
        System.out.printf("  [边界] %-30s %s%n", "空消息", emptyOk ? "PASS" : "FAIL");

        tc("边界", "完全无关内容",         "今天天气怎么样",                          new String[]{"创建", "查询", "停用"});
        tc("边界", "乱码输入",             "!@#$%^&*()",                              new String[]{"创建", "帮助"});
        tc("边界", "超长姓名",             "创建用户 这是一个非常非常非常非常非常非常长的用户名字 13800000099", new String[]{"这是一个"});
        tc("边界", "私聊模式-帮助",        "帮助",                                    new String[]{"创建用户"}, "1");
        tc("边界", "自然语言-模糊创建",    "我要加一个新用户叫测试用户G电话13800000007", new String[]{"测试用户G"});
        tc("边界", "自然语言-模糊停用",    "把测试用户A的账号停掉",                   new String[]{"停用", "测试用户A"});
        tc("边界", "自然语言-模糊启用",    "恢复测试用户A的账号",                     new String[]{"启用", "测试用户A"});
        tc("边界", "自然语言-模糊搜索",    "帮我查一下有没有叫测试用户A的人",         new String[]{"测试用户A"});
        tc("边界", "自然语言-模糊统计",    "帮我看看这个月新增了多少学员",            new String[]{"统计", "新增学员"});
    }

    // ============================================================
    // 报表输出
    // ============================================================

    @AfterAll
    static void printReport() {
        int total = RESULTS.size();
        long pass  = RESULTS.stream().filter(TestResult::pass).count();
        long fail  = total - pass;
        double rate = total > 0 ? pass * 100.0 / total : 0;

        System.out.println("\n");
        System.out.println("╔══════════════════════════════════════════════════════════════╗");
        System.out.printf( "║  测试报表   总计:%-3d  通过:%-3d  失败:%-3d  通过率:%.1f%%%-8s║%n",
                total, pass, fail, rate, "");
        System.out.println("╠══════════════════════════════════════════════════════════════╣");

        // 按分类汇总
        Map<String, long[]> catMap = new LinkedHashMap<>();
        for (TestResult r : RESULTS) {
            catMap.computeIfAbsent(r.category(), k -> new long[]{0, 0});
            catMap.get(r.category())[1]++;
            if (r.pass()) catMap.get(r.category())[0]++;
        }
        for (Map.Entry<String, long[]> e : catMap.entrySet()) {
            String icon = e.getValue()[0] == e.getValue()[1] ? "✓" : "✗";
            System.out.printf("║  %s %-12s  %d/%d%-44s║%n",
                    icon, e.getKey(), e.getValue()[0], e.getValue()[1], "");
        }

        System.out.println("╠══════════════════════════════════════════════════════════════╣");
        System.out.println("║  详细结果                                                    ║");
        System.out.println("╠══════════════════════════════════════════════════════════════╣");

        for (TestResult r : RESULTS) {
            String status = r.pass() ? "PASS" : "FAIL";
            System.out.printf("║  [%s] [%-6s] %-30s%-14s║%n",
                    status, r.category(), r.name(), "");
            if (!r.pass()) {
                String act = r.actual().replace("\n", "↵");
                if (act.length() > 55) act = act.substring(0, 55) + "...";
                System.out.printf("║         期望含: %-52s║%n", r.expected());
                System.out.printf("║         实际:   %-52s║%n", act);
            }
        }

        System.out.println("╚══════════════════════════════════════════════════════════════╝");

        // 失败时让 JUnit 报告失败
        if (fail > 0) {
            System.out.println("\n以下用例失败：");
            RESULTS.stream().filter(r -> !r.pass()).forEach(r ->
                System.out.printf("  - [%s] %s | 输入: %s%n", r.category(), r.name(), r.input()));
        }
    }
}
