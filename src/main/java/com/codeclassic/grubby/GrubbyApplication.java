package com.codeclassic.grubby;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class GrubbyApplication {

    public static void main(String[] args) {
        SpringApplication.run(GrubbyApplication.class, args);
    }

}
