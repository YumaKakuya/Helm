package dev.sorted.mcphub;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;

/**
 * Loads MCPHUB runtime configuration from YAML file.
 * Default config path: $MCPHUB_DATA_DIR/config.yaml or ~/.local/share/mcphub/config.yaml
 * Spec: REQ-8.5.5 (thresholds configurable)
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class McpHubConfig {
    private static final Logger log = LoggerFactory.getLogger(McpHubConfig.class);

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class BodyBudgetConfig {
        @JsonProperty("warning_tool_count") public Integer warningToolCount;
        @JsonProperty("critical_tool_count") public Integer criticalToolCount;
        @JsonProperty("warning_byte_size") public Integer warningByteSize;
        @JsonProperty("critical_byte_size") public Integer criticalByteSize;
        @JsonProperty("total_registered_hatch_tools") public Integer totalRegisteredHatchTools;
        @JsonProperty("baseline_schema_bytes_per_tool") public Integer baselineSchemaKbPerTool;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class SessionConfig {
        @JsonProperty("idle_timeout_seconds") public Long idleTimeoutSeconds;
        @JsonProperty("armed_timeout_seconds") public Long armedTimeoutSeconds;
    }

    @JsonProperty("body_budget") public BodyBudgetConfig bodyBudget;
    @JsonProperty("session") public SessionConfig session;
    @JsonProperty("server_name") public String serverName;

    /**
     * Load config from the default config file path.
     * Returns a default (all-null) config if file not found.
     */
    public static McpHubConfig load() {
        String configPath = configFilePath();
        File configFile = new File(configPath);
        if (!configFile.exists()) {
            log.info("No config file found at {}; using defaults", configPath);
            return new McpHubConfig();
        }
        try {
            ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
            McpHubConfig cfg = mapper.readValue(configFile, McpHubConfig.class);
            log.info("Loaded config from {}", configPath);
            return cfg;
        } catch (IOException e) {
            log.warn("Failed to load config from {}: {}", configPath, e.getMessage());
            return new McpHubConfig();
        }
    }

    public static String configFilePath() {
        String dataDir = System.getenv("MCPHUB_DATA_DIR");
        if (dataDir == null || dataDir.isBlank()) {
            dataDir = System.getProperty("user.home") + "/.local/share/mcphub";
        }
        return dataDir + "/config.yaml";
    }

    /** Apply body-budget thresholds from config to the service, if set. REQ-8.5.5 */
    public void applyTo(BodyBudgetService service) {
        if (bodyBudget == null) return;
        if (bodyBudget.warningToolCount != null) service.setWarningToolCount(bodyBudget.warningToolCount);
        if (bodyBudget.criticalToolCount != null) service.setCriticalToolCount(bodyBudget.criticalToolCount);
        if (bodyBudget.warningByteSize != null) service.setWarningByteSize(bodyBudget.warningByteSize);
        if (bodyBudget.criticalByteSize != null) service.setCriticalByteSize(bodyBudget.criticalByteSize);
        if (bodyBudget.totalRegisteredHatchTools != null) service.setTotalRegisteredHatchTools(bodyBudget.totalRegisteredHatchTools);
        if (bodyBudget.baselineSchemaKbPerTool != null) service.setBaselineSchemaKbPerTool(bodyBudget.baselineSchemaKbPerTool);
    }
}
