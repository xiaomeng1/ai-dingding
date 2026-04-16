package com.ai.dingding;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;

/**
 * Spring Boot 应用入口。
 * 注意：排除了 DataSourceAutoConfiguration，因为本项目使用 SQLite 内存数据库
 *（由 RegistrationService 直接管理 Connection），不需要 Spring 的数据源自动配置。
 */
@SpringBootApplication(exclude = {DataSourceAutoConfiguration.class})
public class Application {

    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
}
