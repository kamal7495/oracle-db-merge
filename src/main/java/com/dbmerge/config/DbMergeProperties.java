package com.dbmerge.config;

import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Optional startup configuration. Connection entries may be pre-declared (see config/sample-config.yml);
 * passwords are never stored in the file - reference an environment variable instead
 * (password: ${DBMERGE_A_PASSWORD:}). Entries with a blank password are skipped.
 */
@ConfigurationProperties(prefix = "dbmerge")
public class DbMergeProperties {

    private String reportsDir = "reports";
    private int maxRowsPerTable = 200_000;   // data compare is in-memory; larger tables are counted but not compared
    private int sampleLimit = 1_000;          // detail rows kept per category in data reports (counts stay exact)
    private List<Preload> preload = new ArrayList<>();
    private String storeDir = System.getProperty("user.home") + "/.dbmerge"; // saved connections; blank = saving off

    public String getStoreDir() { return storeDir; }
    public void setStoreDir(String storeDir) { this.storeDir = storeDir; }

    public int getMaxRowsPerTable() { return maxRowsPerTable; }
    public void setMaxRowsPerTable(int maxRowsPerTable) { this.maxRowsPerTable = maxRowsPerTable; }
    public int getSampleLimit() { return sampleLimit; }
    public void setSampleLimit(int sampleLimit) { this.sampleLimit = sampleLimit; }

    public String getReportsDir() { return reportsDir; }
    public void setReportsDir(String reportsDir) { this.reportsDir = reportsDir; }
    public List<Preload> getPreload() { return preload; }
    public void setPreload(List<Preload> preload) { this.preload = preload; }

    public static class Preload {
        private String nickname;
        private String host = "localhost";
        private Integer port = 1521;
        private String serviceName;
        private String username;
        private String password;
        private String schema;

        public String getNickname() { return nickname; }
        public void setNickname(String nickname) { this.nickname = nickname; }
        public String getHost() { return host; }
        public void setHost(String host) { this.host = host; }
        public Integer getPort() { return port; }
        public void setPort(Integer port) { this.port = port; }
        public String getServiceName() { return serviceName; }
        public void setServiceName(String serviceName) { this.serviceName = serviceName; }
        public String getUsername() { return username; }
        public void setUsername(String username) { this.username = username; }
        public String getPassword() { return password; }
        public void setPassword(String password) { this.password = password; }
        public String getSchema() { return schema; }
        public void setSchema(String schema) { this.schema = schema; }

        @Override
        public String toString() {
            return "Preload[" + nickname + "]";
        }
    }
}
