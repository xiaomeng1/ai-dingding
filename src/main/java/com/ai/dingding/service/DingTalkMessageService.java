package com.ai.dingding.service;

import com.ai.dingding.holder.AuthTokenHolder;
import lombok.extern.log4j.Log4j2;
import shade.com.alibaba.fastjson2.JSON;
import shade.com.alibaba.fastjson2.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/**
 * 封装钉钉 OpenAPI 消息发送（文本消息 + 文件消息）。
 * accessToken 使用内存缓存，临近过期（5 分钟内）自动刷新。
 */
@Log4j2
public class DingTalkMessageService {

    private static final String APP_KEY = "ding3wlhmzygb3m67t3i";
    private static final String APP_SECRET =
            "cL-ivK-CwQJKsz7VZI2R7EEsk2pDwm0cnlSD4av_wbZbpdw1I4yh6aF1DSKtklFx";

    private static final String TOKEN_URL =
            "https://api.dingtalk.com/v1.0/oauth2/accessToken";
    private static final String SEND_URL =
            "https://api.dingtalk.com/v1.0/robot/groupMessages/send";
    /** 旧版媒体上传接口，新版 v1.0 /robot/messageFiles/upload 返回 404 */
    private static final String OLD_TOKEN_URL =
            "https://oapi.dingtalk.com/gettoken";
    private static final String MEDIA_UPLOAD_URL =
            "https://oapi.dingtalk.com/media/upload";

    private static final String LOCAL_EXPORT_DIR = System.getProperty("user.home") + "/dingding-exports/";
    private static final DateTimeFormatter TIMESTAMP_FMT =
            DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private final String robotCode;

    public DingTalkMessageService(String robotCode) {
        this.robotCode = robotCode;
    }

    // -------- 文本消息 --------

    /**
     * 向指定群会话发送纯文本消息。
     *
     * @param openConversationId 群 openConversationId（来自机器人回调）
     * @param content            消息内容
     */
    public void sendTextMessage(String openConversationId, String content) {
        try {
            String accessToken = getAccessToken();
            if (accessToken == null) {
                log.error("无法获取钉钉 accessToken，消息发送失败");
                return;
            }

            JSONObject msgParam = new JSONObject();
            msgParam.put("content", content);

            JSONObject body = new JSONObject();
            body.put("robotCode", robotCode);
            body.put("openConversationId", openConversationId);
            body.put("msgKey", "sampleText");
            body.put("msgParam", msgParam.toJSONString());

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(SEND_URL))
                    .timeout(Duration.ofSeconds(15))
                    .header("Content-Type", "application/json")
                    .header("x-acs-dingtalk-access-token", accessToken)
                    .POST(HttpRequest.BodyPublishers.ofString(body.toJSONString()))
                    .build();

            HttpResponse<String> response = HTTP_CLIENT.send(
                    request, HttpResponse.BodyHandlers.ofString()
            );
            log.info("发送钉钉文本消息 -> status={}, body={}", response.statusCode(), response.body());
        } catch (Exception e) {
            log.error("发送钉钉消息异常", e);
        }
    }

    // -------- 文件消息 --------

    /**
     * 将 Excel 文件保存到本地，并上传到钉钉群发送。
     * 本地文件保留，不自动删除，路径：~/dingding-exports/
     *
     * @param openConversationId 群 openConversationId
     * @param fileName           文件名（含扩展名）
     * @param fileData           文件字节数组
     */
    public void sendExamFile(String openConversationId, String fileName, byte[] fileData) {
        // 先落盘本地，方便验证文件内容
        String localPath = saveLocalFile(fileName, fileData);
        if (localPath != null) {
            log.info("导出文件已保存到本地：{}", localPath);
            sendTextMessage(openConversationId,
                    "文件已生成（" + (fileData.length / 1024) + " KB），正在上传到群...\n本地路径：" + localPath);
        }

        String mediaId = uploadFileViaOldApi(fileName, fileData);
        if (mediaId == null) {
            sendTextMessage(openConversationId,
                    "钉钉文件上传失败，请到服务器本地查看文件：" + localPath);
            return;
        }
        sendFileMessage(openConversationId, fileName, mediaId);
    }

    /**
     * 将文件保存到本地 ~/dingding-exports/ 目录。
     *
     * @return 本地文件绝对路径，失败返回 null
     */
    private String saveLocalFile(String fileName, byte[] fileData) {
        try {
            Path dir = Path.of(LOCAL_EXPORT_DIR);
            Files.createDirectories(dir);
            // 文件名加时间戳避免覆盖
            String ts = LocalDateTime.now().format(TIMESTAMP_FMT);
            String savedName = ts + "_" + fileName;
            Path target = dir.resolve(savedName);
            Files.write(target, fileData);
            return target.toAbsolutePath().toString();
        } catch (IOException e) {
            log.error("保存文件到本地失败", e);
            return null;
        }
    }

    /**
     * 通过旧版 oapi.dingtalk.com/media/upload 上传文件，返回 media_id。
     * 新版 /v1.0/robot/messageFiles/upload 接口返回 404，需使用旧版。
     *
     * @return media_id，失败返回 null
     */
    private String uploadFileViaOldApi(String fileName, byte[] fileData) {
        try {
            String corpToken = getCorpAccessToken();
            if (corpToken == null) {
                log.error("无法获取 corp access_token，文件上传失败");
                return null;
            }

            String boundary = "----DingBoundary" + UUID.randomUUID().toString().replace("-", "");
            byte[] body = buildOldApiMultipartBody(boundary, fileName, fileData);

            String uploadUrl = MEDIA_UPLOAD_URL + "?access_token=" + corpToken + "&type=file";
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(uploadUrl))
                    .timeout(Duration.ofSeconds(60))
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                    .build();

            HttpResponse<String> response = HTTP_CLIENT.send(
                    request, HttpResponse.BodyHandlers.ofString()
            );
            log.info("上传文件到钉钉(oapi) -> status={}, body={}", response.statusCode(), response.body());

            JSONObject resp = JSON.parseObject(response.body());
            if (resp == null) {
                return null;
            }
            // 旧版接口返回 media_id，errcode=0 表示成功
            Integer errcode = resp.getInteger("errcode");
            if (errcode != null && errcode == 0) {
                return resp.getString("media_id");
            }
            log.warn("钉钉文件上传失败，响应：{}", response.body());
            return null;
        } catch (Exception e) {
            log.error("上传文件到钉钉(oapi)异常", e);
            return null;
        }
    }

    /**
     * 获取旧版企业 access_token（用于 oapi.dingtalk.com 上传媒体文件）。
     */
    private String getCorpAccessToken() {
        try {
            String url = OLD_TOKEN_URL + "?appkey=" + APP_KEY + "&appsecret=" + APP_SECRET;
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(15))
                    .GET()
                    .build();

            HttpResponse<String> response = HTTP_CLIENT.send(
                    request, HttpResponse.BodyHandlers.ofString()
            );
            JSONObject resp = JSON.parseObject(response.body());
            if (resp == null) {
                log.error("获取 corp access_token 响应为空");
                return null;
            }
            Integer errcode = resp.getInteger("errcode");
            if (errcode != null && errcode == 0) {
                log.info("corp access_token 获取成功");
                return resp.getString("access_token");
            }
            log.error("获取 corp access_token 失败：{}", response.body());
            return null;
        } catch (Exception e) {
            log.error("获取 corp access_token 异常", e);
            return null;
        }
    }

    /**
     * 向群会话发送文件消息。
     */
    private void sendFileMessage(String openConversationId, String fileName, String mediaId) {
        try {
            String accessToken = getAccessToken();
            if (accessToken == null) {
                log.error("无法获取钉钉 accessToken，文件消息发送失败");
                return;
            }

            JSONObject msgParam = new JSONObject();
            msgParam.put("fileName", fileName);
            msgParam.put("mediaId", mediaId);

            JSONObject body = new JSONObject();
            body.put("robotCode", robotCode);
            body.put("openConversationId", openConversationId);
            body.put("msgKey", "sampleFile");
            body.put("msgParam", msgParam.toJSONString());

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(SEND_URL))
                    .timeout(Duration.ofSeconds(15))
                    .header("Content-Type", "application/json")
                    .header("x-acs-dingtalk-access-token", accessToken)
                    .POST(HttpRequest.BodyPublishers.ofString(body.toJSONString()))
                    .build();

            HttpResponse<String> response = HTTP_CLIENT.send(
                    request, HttpResponse.BodyHandlers.ofString()
            );
            log.info("发送钉钉文件消息 -> status={}, body={}", response.statusCode(), response.body());
        } catch (Exception e) {
            log.error("发送钉钉文件消息异常", e);
        }
    }

    /**
     * 构造旧版 oapi multipart 请求体，只需 media 字段（文件内容）。
     */
    private byte[] buildOldApiMultipartBody(String boundary, String fileName, byte[] fileData) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        String crlf = "\r\n";
        String dashes = "--";

        // media (file) part
        write(out, dashes + boundary + crlf);
        write(out, "Content-Disposition: form-data; name=\"media\"; filename=\""
                + fileName + "\"" + crlf);
        write(out, "Content-Type: application/vnd.openxmlformats-officedocument"
                + ".spreadsheetml.sheet" + crlf + crlf);
        writeBytes(out, fileData);
        write(out, crlf);

        // closing boundary
        write(out, dashes + boundary + dashes + crlf);

        return out.toByteArray();
    }

    private static void write(ByteArrayOutputStream out, String text) {
        try {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new RuntimeException("构造 multipart body 失败", e);
        }
    }

    private static void writeBytes(ByteArrayOutputStream out, byte[] data) {
        try {
            out.write(data);
        } catch (IOException e) {
            throw new RuntimeException("构造 multipart body 失败", e);
        }
    }

    // -------- Token 管理 --------

    /**
     * 获取有效的 accessToken，内存命中则直接返回，否则重新请求。
     */
    private synchronized String getAccessToken() {
        if (!AuthTokenHolder.isDingTokenExpired()) {
            return AuthTokenHolder.getDingAccessToken();
        }

        try {
            JSONObject body = new JSONObject();
            body.put("appKey", APP_KEY);
            body.put("appSecret", APP_SECRET);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(TOKEN_URL))
                    .timeout(Duration.ofSeconds(15))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body.toJSONString()))
                    .build();

            HttpResponse<String> response = HTTP_CLIENT.send(
                    request, HttpResponse.BodyHandlers.ofString()
            );

            JSONObject resp = JSON.parseObject(response.body());
            if (resp == null) {
                log.error("获取钉钉 accessToken 响应为空");
                return null;
            }

            String token = resp.getString("accessToken");
            Long expireIn = resp.getLong("expireIn");

            if (token == null || expireIn == null) {
                log.error("获取钉钉 accessToken 失败，响应：{}", response.body());
                return null;
            }

            AuthTokenHolder.setDingAccessToken(token, expireIn);
            log.info("钉钉 accessToken 已刷新，有效期 {}s", expireIn);
            return token;
        } catch (Exception e) {
            log.error("获取钉钉 accessToken 异常", e);
            return null;
        }
    }
}
