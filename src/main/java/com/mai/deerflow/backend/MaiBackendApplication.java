package com.mai.deerflow.backend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
/**
 * Java DeerFlow backend 的 Spring Boot 启动入口。
 *
 * 当前职责很轻，只负责启动自动配置和属性扫描；
 * 后续 runtime / platform 模块都通过 Spring Bean 方式接入。
 */
public class MaiBackendApplication {

    public static void main(String[] args) {
        SpringApplication.run(MaiBackendApplication.class, args);
    }
}
