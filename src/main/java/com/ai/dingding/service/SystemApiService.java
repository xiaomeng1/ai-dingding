package com.ai.dingding.service;

import com.ai.dingding.holder.AuthTokenHolder;
import lombok.extern.log4j.Log4j2;
import shade.com.alibaba.fastjson2.JSON;
import shade.com.alibaba.fastjson2.JSONArray;
import shade.com.alibaba.fastjson2.JSONObject;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 封装对 os.zhida-keji.com.cn 系统的所有 API 调用。
 * Token 策略：先用内存中的 token 发请求，code != 200 时视为失效，
 * 自动重新登录获取新 token，再重试一次。
 */
@Log4j2
public class SystemApiService {

    private static final String BASE_URL = "https://os.zhida-keji.com.cn/api";
    /** 从 JVM 启动参数读取：-Dsys.username=xxx */
    private static final String LOGIN_USERNAME = System.getProperty("sys.username");
    /** 从 JVM 启动参数读取：-Dsys.password=xxx */
    private static final String LOGIN_PASSWORD = System.getProperty("sys.password");

    /** 创建用户时使用的固定默认参数 */
    private static final String ACCOUNT_TYPE = "[\"1\"]";
    private static final String PROVINCE = "新疆省";
    private static final String STATION = "乌鲁木齐站";
    private static final String DEFAULT_PASSWORD = "123456";
    private static final String LEVEL = "中级监控";
    private static final int DURATION = 31;
    private static final List<String> PROVINCE_LIST = List.of("新疆省", "乌鲁木齐站");

    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    // -------- 登录 --------

    /**
     * 登录并更新内存中的系统 Token，线程安全。
     *
     * @return 成功返回 true
     */
    public synchronized boolean login() {
        try {
            JSONObject body = new JSONObject();
            body.put("username", LOGIN_USERNAME);
            body.put("password", LOGIN_PASSWORD);
            body.put("remember", true);
            body.put("phone", "");
            body.put("phoneCode", "");
            body.put("code", "");
            body.put("uuid", "");

            String responseBody = post(BASE_URL + "/login", body.toJSONString(), null);
            JSONObject resp = JSON.parseObject(responseBody);

            if (resp != null && resp.getInteger("code") == 200) {
                String token = resp.getString("data");
                AuthTokenHolder.setSystemToken(token);
                log.info("系统登录成功，token 已更新");
                return true;
            }

            log.warn("系统登录失败，响应：{}", responseBody);
            return false;
        } catch (Exception e) {
            log.error("系统登录异常", e);
            return false;
        }
    }

    // -------- 创建用户 --------

    /**
     * 创建用户，需提供 nickName 和 phone，其余参数使用默认值。
     *
     * @param nickName 用户名
     * @param phone    手机号
     * @return 操作结果描述（成功/失败信息）
     */
    public String createUser(String nickName, String phone) {
        JSONObject body = buildCreateUserBody(nickName, phone);
        return executeWithTokenRetry(
                () -> post(BASE_URL + "/simple/user", body.toJSONString(),
                        AuthTokenHolder.getSystemToken())
        );
    }

    /** 构造创建用户请求体 */
    private JSONObject buildCreateUserBody(String nickName, String phone) {
        JSONObject body = new JSONObject();
        body.put("accountPriceType", 0);
        body.put("accountPrice", 1);
        body.put("nickName", nickName);
        body.put("phone", phone);
        body.put("password", DEFAULT_PASSWORD);
        body.put("accountType", ACCOUNT_TYPE);
        body.put("provinceList", PROVINCE_LIST);
        body.put("level", LEVEL);
        body.put("duration", DURATION);
        body.put("trial", 0);
        body.put("useContent", 0);
        body.put("monthPrice", 0);
        body.put("province", PROVINCE);
        body.put("station", STATION);
        return body;
    }

    // -------- 搜索用户 --------

    /**
     * 按 nickName 搜索用户。
     *
     * @return 匹配的用户列表，空列表表示未找到
     */
    public List<JSONObject> searchUser(String nickName) {
        JSONObject body = new JSONObject();
        body.put("deptId", "");
        body.put("pageSize", 15);
        body.put("pageNum", 1);
        body.put("search", nickName);
        body.put("intoTypeValue", "0");
        body.put("intoTypes", List.of(0, 1));

        String result = executeWithTokenRetry(
                () -> post(BASE_URL + "/simple/user/page", body.toJSONString(),
                        AuthTokenHolder.getSystemToken())
        );

        return parseRecords(result);
    }

    // -------- 删除用户 --------

    /**
     * 按用户 ID 删除用户。
     *
     * @return 操作结果描述
     */
    public String deleteUser(long userId) {
        String requestBody = "[" + userId + "]";
        return executeWithTokenRetry(
                () -> delete(BASE_URL + "/simple/user", requestBody,
                        AuthTokenHolder.getSystemToken())
        );
    }

    // -------- 导出考试记录 --------

    /**
     * 下载考试记录 Excel，支持按 nickName 筛选。
     *
     * @param nickName   筛选值：区域前缀（"（<区域名>"）或学生姓名；空字符串表示不筛选
     * @param beginStart 开始时间 yyyy-MM-dd HH:mm:ss；空字符串表示不限制
     * @param beginEnd   结束时间 yyyy-MM-dd HH:mm:ss；空字符串表示不限制
     */
    public byte[] exportExamRecords(String nickName, String beginStart, String beginEnd) {
        ensureToken();
        try {
            String url = buildExportUrl(nickName, beginStart, beginEnd);
            byte[] result = postBinary(url, "{}", AuthTokenHolder.getSystemToken());

            if (isJsonErrorResponse(result)) {
                String preview = new String(result, StandardCharsets.UTF_8);
                if (isTokenExpiredJson(preview)) {
                    log.info("导出接口返回 JSON 错误（可能 token 失效），响应：{}，尝试重新登录", preview);
                    if (login()) {
                        result = postBinary(url, "{}", AuthTokenHolder.getSystemToken());
                    } else {
                        return null;
                    }
                }
            }

            if (result == null || result.length == 0) {
                log.warn("导出接口返回空响应");
                return null;
            }

            // 最终检查：如果仍然是 JSON 响应（如空结果 code:200），说明没有导出数据
            if (isJsonErrorResponse(result)) {
                String body = new String(result, StandardCharsets.UTF_8);
                log.warn("导出接口未返回文件流，响应：{}", body);
                return null;
            }

            log.info("导出接口返回 {} 字节，文件头：{} {}",
                    result.length,
                    String.format("%02X", result[0]),
                    result.length > 1 ? String.format("%02X", result[1]) : "");
            return result;
        } catch (Exception e) {
            log.error("导出考试记录异常", e);
            return null;
        }
    }

    /**
     * 下载指定时间范围的考试记录 Excel 文件（兼容旧调用，委托给三参数重载）。
     *
     * @param beginStart 开始时间，格式：yyyy-MM-dd HH:mm:ss
     * @param beginEnd   结束时间，格式：yyyy-MM-dd HH:mm:ss
     * @return Excel 文件字节数组，失败返回 null
     */
    public byte[] exportExamRecords(String beginStart, String beginEnd) {
        return exportExamRecords("", beginStart, beginEnd);
    }

    private String buildExportUrl(String nickName, String beginStart, String beginEnd) {
        String encodedNickName = (nickName == null || nickName.isBlank())
                ? ""
                : URLEncoder.encode(nickName, StandardCharsets.UTF_8);
        String encodedBegin = (beginStart == null || beginStart.isBlank())
                ? ""
                : URLEncoder.encode(beginStart, StandardCharsets.UTF_8);
        String encodedEnd = (beginEnd == null || beginEnd.isBlank())
                ? ""
                : URLEncoder.encode(beginEnd, StandardCharsets.UTF_8);
        return BASE_URL + "/test/record/export"
                + "?nickName=" + encodedNickName
                + "&phone=&pageSize=10000&pageNum=1"
                + "&beginStart=" + encodedBegin
                + "&beginEnd=" + encodedEnd;
    }

    /**
     * 判断字节数组是否为 JSON 响应（以 '{' 开头）。
     * 导出接口正常应返回 Excel 二进制流，如果返回 JSON 则说明不是文件数据。
     */
    private boolean isJsonErrorResponse(byte[] data) {
        return data != null && data.length > 0 && data[0] == (byte) '{';
    }

    /**
     * 判断 JSON 响应是否为 token 失效（code != 200）。
     * code:200 的 JSON 表示业务成功但无文件数据（如查询结果为空），不应触发重新登录。
     */
    private boolean isTokenExpiredJson(String json) {
        try {
            JSONObject resp = JSON.parseObject(json);
            if (resp == null) return true;
            Integer code = resp.getInteger("code");
            return code == null || code != 200;
        } catch (Exception e) {
            return true;
        }
    }

    // -------- 全量学员列表 --------

    /**
     * 分页拉取所有学员数据（每页 200 条），直到最后一页。
     * 调用方负责按 createTime 进行过滤。
     *
     * @return 所有学员记录列表
     */
    public List<JSONObject> fetchAllStudents() {
        List<JSONObject> allRecords = new ArrayList<>();
        int pageNum = 1;
        int totalPages = 1;

        do {
            final int currentPage = pageNum;
            JSONObject body = buildStudentPageBody(currentPage);

            String result = executeWithTokenRetry(
                    () -> post(BASE_URL + "/simple/user/page", body.toJSONString(),
                            AuthTokenHolder.getSystemToken())
            );

            if (result == null) {
                log.warn("拉取第 {} 页学员数据失败，提前终止", currentPage);
                break;
            }

            JSONObject resp = JSON.parseObject(result);
            if (resp == null || resp.getInteger("code") != 200) {
                log.warn("第 {} 页响应异常：{}", currentPage, result);
                break;
            }

            JSONObject data = resp.getJSONObject("data");
            if (data == null) {
                break;
            }

            if (pageNum == 1) {
                totalPages = data.getIntValue("pages");
                log.info("学员总页数：{}，共 {} 条", totalPages, data.getLongValue("total"));
            }

            List<JSONObject> records = parseRecords(result);
            allRecords.addAll(records);

            if (records.isEmpty()) {
                break;
            }
            pageNum++;
        } while (pageNum <= totalPages);

        return allRecords;
    }

    private JSONObject buildStudentPageBody(int pageNum) {
        JSONObject body = new JSONObject();
        body.put("deptId", "");
        body.put("pageSize", 200);
        body.put("pageNum", pageNum);
        body.put("search", "");
        body.put("intoTypeValue", 0);
        body.put("intoTypes", List.of(0, 1));
        return body;
    }

    // -------- 通用解析 --------

    private List<JSONObject> parseRecords(String result) {
        if (result == null) {
            return List.of();
        }
        try {
            JSONObject resp = JSON.parseObject(result);
            if (resp == null || resp.getInteger("code") != 200) {
                return List.of();
            }
            JSONObject data = resp.getJSONObject("data");
            if (data == null) {
                return List.of();
            }
            JSONArray records = data.getJSONArray("records");
            if (records == null || records.isEmpty()) {
                return List.of();
            }
            return records.toList(JSONObject.class);
        } catch (Exception e) {
            log.error("解析列表响应异常，原始响应：{}", result, e);
            return List.of();
        }
    }

    // -------- Token 失效重试逻辑 --------

    /**
     * 执行 API 调用，若 token 失效则重新登录后重试一次。
     */
    private String executeWithTokenRetry(ApiCall call) {
        ensureToken();
        try {
            String result = call.execute();
            if (isTokenExpired(result)) {
                log.info("Token 失效，重新登录后重试");
                if (login()) {
                    return call.execute();
                }
                return null;
            }
            return result;
        } catch (Exception e) {
            log.error("API 调用异常", e);
            return null;
        }
    }

    /** 确保内存中有 token，没有则立即登录 */
    private void ensureToken() {
        if (!AuthTokenHolder.hasSystemToken()) {
            login();
        }
    }

    /**
     * 判断响应是否表示 token 失效。
     * 系统返回 code=401 或 code=500（部分接口未授权时返回 500）时视为失效。
     */
    private boolean isTokenExpired(String responseBody) {
        if (responseBody == null) {
            return true;
        }
        try {
            JSONObject resp = JSON.parseObject(responseBody);
            if (resp == null) {
                return false;
            }
            Integer code = resp.getInteger("code");
            return code != null && (code == 401 || code == 500);
        } catch (Exception e) {
            return false;
        }
    }

    // -------- HTTP 工具方法 --------

    private String post(String url, String body, String token) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/plain, */*")
                .POST(HttpRequest.BodyPublishers.ofString(body));

        if (token != null && !token.isBlank()) {
            builder.header("Authorization", token);
        }

        HttpResponse<String> response = HTTP_CLIENT.send(
                builder.build(), HttpResponse.BodyHandlers.ofString()
        );
        log.debug("POST {} -> status={}", url, response.statusCode());
        return response.body();
    }

    private String delete(String url, String body, String token) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/plain, */*")
                .method("DELETE", HttpRequest.BodyPublishers.ofString(body));

        if (token != null && !token.isBlank()) {
            builder.header("Authorization", token);
        }

        HttpResponse<String> response = HTTP_CLIENT.send(
                builder.build(), HttpResponse.BodyHandlers.ofString()
        );
        log.debug("DELETE {} -> status={}", url, response.statusCode());
        return response.body();
    }

    /**
     * POST 请求下载二进制响应（导出 Excel 接口为 POST + 空 body，参数在 query string 中）。
     */
    private byte[] postBinary(String url, String body, String token) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(60))
                .header("Content-Type", "application/json")
                .header("Accept", "*/*")
                .POST(HttpRequest.BodyPublishers.ofString(body));

        if (token != null && !token.isBlank()) {
            builder.header("Authorization", token);
        }

        HttpResponse<byte[]> response = HTTP_CLIENT.send(
                builder.build(), HttpResponse.BodyHandlers.ofByteArray()
        );
        log.info("POST(binary) {} -> status={}, bytes={}",
                url, response.statusCode(), response.body().length);
        return response.body();
    }

    @FunctionalInterface
    private interface ApiCall {
        String execute() throws Exception;
    }
}
