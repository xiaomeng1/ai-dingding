package com.ai.dingding;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class VaccineSystemApplication {

    public static void main(String[] args) {
        SpringApplication.run(VaccineSystemApplication.class, args);
    }

}
