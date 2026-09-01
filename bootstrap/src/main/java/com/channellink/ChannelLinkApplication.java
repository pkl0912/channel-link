package com.channellink;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = "com.channellink")
public class ChannelLinkApplication {

    public static void main(String[] args) {
        SpringApplication.run(ChannelLinkApplication.class, args);
    }
}
