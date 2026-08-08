package com.praveen.aicodingagent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling // required for SandboxManager's idle-container reaper
public class AiCodingAgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(AiCodingAgentApplication.class, args);
    }

}
