package com.ai.dingding.holder;

/**
 * 内存中持有两套 Token：
 *   1. 系统 JWT（os.zhida-keji.com.cn）— 无过期时间戳，按需失效重登录
 *   2. 钉钉 accessToken — 带过期时间戳，临近过期主动刷新
 */
public class AuthTokenHolder {

    private static volatile String systemToken = null;

    private static volatile String dingAccessToken = null;

    /** 钉钉 accessToken 的过期时间（毫秒时间戳） */
    private static volatile long dingTokenExpireAt = 0L;

    /** 过期前多少毫秒视为需要刷新（5 分钟） */
    private static final long REFRESH_BUFFER_MS = 5 * 60 * 1000L;

    private AuthTokenHolder() {
    }

    // -------- 系统 JWT --------

    public static String getSystemToken() {
        return systemToken;
    }

    public static synchronized void setSystemToken(String token) {
        systemToken = token;
    }

    public static boolean hasSystemToken() {
        return systemToken != null && !systemToken.isBlank();
    }

    // -------- 钉钉 accessToken --------

    public static String getDingAccessToken() {
        return dingAccessToken;
    }

    /**
     * @param token     accessToken 值
     * @param expiresIn 有效期秒数（钉钉返回的 expireIn 字段）
     */
    public static synchronized void setDingAccessToken(String token, long expiresIn) {
        dingAccessToken = token;
        dingTokenExpireAt = System.currentTimeMillis() + expiresIn * 1000L;
    }

    /** 是否需要刷新钉钉 Token（不存在或临近过期） */
    public static boolean isDingTokenExpired() {
        if (dingAccessToken == null || dingAccessToken.isBlank()) {
            return true;
        }
        return System.currentTimeMillis() >= (dingTokenExpireAt - REFRESH_BUFFER_MS);
    }
}
