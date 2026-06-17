package com.iptv.saas;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class IptvSaasApplication {
    public static void main(String[] args) {
        SpringApplication.run(IptvSaasApplication.class, args);
    }
}
