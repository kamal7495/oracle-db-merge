package com.dbmerge;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class DbMergeApplication {
    public static void main(String[] args) {
        if (args.length > 0 && args[0].equals("cli")) {
            System.exit(com.dbmerge.cli.Cli.main(java.util.Arrays.copyOfRange(args, 1, args.length)));
        }
        SpringApplication.run(DbMergeApplication.class, args);
    }
}
