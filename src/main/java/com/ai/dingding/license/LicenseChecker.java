package com.ai.dingding.license;

import lombok.extern.log4j.Log4j2;
import shade.com.alibaba.fastjson2.JSON;
import shade.com.alibaba.fastjson2.JSONObject;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * 授权检查器：每次指令请求时检查 Gitee 上的授权状态。
 * 带 30 秒本地缓存，避免每条消息都发起外网请求。
 * 网络不通时保持上次结果，不因网络抖动误停服务。
 */
@Log4j2
public class LicenseChecker {

    private static final String LICENSE_URL =
            "https://gitee.com/xiaomeng-stupid/dingding-auth/raw/master/license.json";

    /** 缓存有效期（秒） */
    private static final long CACHE_SECONDS = 3600;

    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    // -------- 缓存状态（volatile 保证多线程可见） --------

    private static volatile boolean cachedActive = true;
    private static volatile String cachedMessage = "";
    private static volatile long lastCheckAt = 0L;

    private LicenseChecker() {
    }

    /**
     * 判断系统当前是否处于授权可用状态。
     * 缓存有效期内直接返回上次结果；超出缓存则重新拉取 Gitee。
     */
    public static boolean isActive() {
        refreshIfNeeded();
        return cachedActive;
    }

    /**
     * 获取停用时展示给用户的提示语（来自 Gitee JSON 的 message 字段）。
     */
    public static String getMessage() {
        return cachedMessage;
    }

    // -------- 内部逻辑 --------

    private static void refreshIfNeeded() {
        long now = System.currentTimeMillis();
        if (now - lastCheckAt < CACHE_SECONDS * 1000L) {
            return;
        }
        doRefresh(now);
    }

    /**
     * synchronized 防止并发时重复请求 Gitee。
     */
    private static synchronized void doRefresh(long triggerTime) {
        // 双重检查：进入锁后再判断一次，避免多个线程同时等锁后重复请求
        if (triggerTime - lastCheckAt < CACHE_SECONDS * 1000L) {
            return;
        }

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(LICENSE_URL))
                    .timeout(Duration.ofSeconds(5))
                    .header("Accept", "application/json")
                    .GET()
                    .build();

            HttpResponse<String> response = HTTP_CLIENT.send(
                    request, HttpResponse.BodyHandlers.ofString()
            );

            JSONObject json = JSON.parseObject(response.body());
            if (json == null) {
                log.warn("授权接口返回空响应，保持上次授权状态");
                return;
            }

            Boolean active = json.getBoolean("active");
            String message = json.getString("message");

            if (active != null) {
                cachedActive = active;
            }
            cachedMessage = (message != null) ? message : "";

            log.info("授权状态已刷新：active={}，message={}",
                    cachedActive, cachedMessage.isBlank() ? "(无)" : cachedMessage);
        } catch (Exception e) {
            // 网络不通时保持上次状态，仅打印警告
            log.warn("授权检查请求失败（网络异常），保持上次状态 active={}，原因：{}",
                    cachedActive, e.getMessage());
        } finally {
            // 无论成功失败都更新时间戳，避免网络故障时疯狂重试
            lastCheckAt = System.currentTimeMillis();
        }
    }
}
