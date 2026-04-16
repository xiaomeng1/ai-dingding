package com.ai.dingding.holder;

import com.ai.dingding.service.RegistrationService;

/**
 * 全局 RegistrationService 实例 holder，
 * 供 HTTP API Server 在独立线程中查询和更新。
 */
public class RegistrationServiceHolder {

    private static RegistrationService instance;

    public static void set(RegistrationService service) {
        instance = service;
    }

    public static RegistrationService get() {
        return instance;
    }
}
