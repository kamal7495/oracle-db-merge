package com.dbmerge.web;

import com.dbmerge.config.DbMergeProperties;
import com.dbmerge.conn.ConnectionRegistry;
import com.dbmerge.conn.ConnectionRequest;
import com.dbmerge.ddl.SchemaExtractor;
import com.dbmerge.ddl.compare.SchemaComparator;
import com.dbmerge.ddl.report.ExcelReportWriter;
import com.dbmerge.ddl.report.HtmlReportWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AppConfig {

    private static final Logger log = LoggerFactory.getLogger(AppConfig.class);

    @Bean
    SchemaExtractor schemaExtractor(ConnectionRegistry registry) {
        return new SchemaExtractor(registry);
    }

    @Bean
    com.dbmerge.data.DataCompareService dataCompareService(ConnectionRegistry registry, DbMergeProperties props) {
        return new com.dbmerge.data.DataCompareService(registry, props.getMaxRowsPerTable());
    }

    @Bean
    com.dbmerge.data.DataHtmlReportWriter dataHtmlReportWriter() {
        return new com.dbmerge.data.DataHtmlReportWriter();
    }

    @Bean
    com.dbmerge.data.DataExcelReportWriter dataExcelReportWriter() {
        return new com.dbmerge.data.DataExcelReportWriter();
    }

    @Bean
    com.dbmerge.merge.MergeService mergeService(ConnectionRegistry registry, DbMergeProperties props) {
        java.nio.file.Path dir = java.nio.file.Path.of(props.getReportsDir()).toAbsolutePath().normalize();
        return new com.dbmerge.merge.MergeService(registry, props.getMaxRowsPerTable(), dir,
                new com.dbmerge.merge.RuleStore(dir.resolve("merge-rules.json")));
    }

    @Bean
    SchemaComparator schemaComparator() {
        return new SchemaComparator();
    }

    @Bean
    HtmlReportWriter htmlReportWriter() {
        return new HtmlReportWriter();
    }

    @Bean
    ExcelReportWriter excelReportWriter() {
        return new ExcelReportWriter();
    }

    /** Registers connections declared in config; passwords must come from environment variables. */
    @Bean
    ApplicationRunner preloadConnections(DbMergeProperties props, ConnectionRegistry registry) {
        return (ApplicationArguments args) -> {
            for (DbMergeProperties.Preload p : props.getPreload()) {
                if (p.getPassword() == null || p.getPassword().isBlank()) {
                    log.info("Skipping preloaded connection '{}': no password supplied (set it via environment variable).",
                            p.getNickname());
                    continue;
                }
                try {
                    registry.add(new ConnectionRequest(p.getNickname(), p.getHost(), p.getPort(), p.getServiceName(),
                            p.getUsername(), p.getPassword().toCharArray(), p.getSchema()));
                    log.info("Registered preloaded connection '{}'.", p.getNickname());
                } catch (IllegalArgumentException e) {
                    log.warn("Preloaded connection '{}' rejected: {}", p.getNickname(), e.getMessage());
                }
            }
        };
    }
}
