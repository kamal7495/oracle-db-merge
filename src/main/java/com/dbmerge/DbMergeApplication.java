package com.dbmerge;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class DbMergeApplication {
    public static void main(String[] args) {
        SpringApplication.run(DbMergeApplication.class, args);
    }
}
