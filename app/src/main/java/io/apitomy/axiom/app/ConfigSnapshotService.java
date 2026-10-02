package io.apitomy.axiom.app;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.apitomy.axiom.api.beans.ConfigurationField;
import io.apitomy.axiom.api.beans.ConfigurationSnapshot;
import io.apitomy.axiom.core.entities.ReportDefinitionEntity;
import io.apitomy.axiom.core.entities.ReportDefinitionVersionEntity;
import io.apitomy.axiom.core.entities.ScheduledJobEntity;
import io.apitomy.axiom.core.entities.ScheduledJobVersionEntity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * Records the execution configuration of scheduled jobs and report definitions (#426).
 *
 * <p>Each distinct configuration of a definition is stored once as a version row, keyed by the
 * SHA-256 hash of its canonical JSON snapshot. Runs and reports point at the version that was
 * current when they were created, so later edits to the definition do not change what an older
 * run or report shows.</p>
 *
 * <p>Only fields that affect execution are snapshotted. Names, descriptions, schedules, enabled
 * flags and labels are excluded. Environment values are redacted unless they are exactly a
 * {@code ${secret:NAME}} reference, so no secret or literal credential is ever stored.</p>
 */
@ApplicationScoped
public class ConfigSnapshotService {

    /** Replacement for environment values that are not plain secret references. */
    public static final String REDACTED = "[redacted]";

    private static final Pattern SECRET_REF = Pattern.compile("\\$\\{secret:[^}]+}");

    @Inject
    ObjectMapper objectMapper;

    /**
     * Returns the version row for the job's current configuration, creating it if needed.
     * Must be called inside a transaction.
     *
     * @param job the scheduled job
     * @return the version ID
     */
    @Transactional(Transactional.TxType.MANDATORY)
    public Long recordJobVersion(ScheduledJobEntity job) {
        String json = toJson(jobConfig(job));
        String hash = sha256(json);
        ScheduledJobVersionEntity existing = ScheduledJobVersionEntity
                .<ScheduledJobVersionEntity>find("jobId = ?1 and configHash = ?2", job.id, hash)
                .firstResult();
        if (existing != null) {
            return existing.id;
        }
        ScheduledJobVersionEntity version = new ScheduledJobVersionEntity();
        version.jobId = job.id;
        version.configHash = hash;
        version.configSnapshot = json;
        version.createdOn = Instant.now();
        version.persist();
        return version.id;
    }

    /**
     * Returns the version row for the report definition's current configuration, creating it
     * if needed. Must be called inside a transaction.
     *
     * @param definition the report definition
     * @return the version ID
     */
    @Transactional(Transactional.TxType.MANDATORY)
    public Long recordReportVersion(ReportDefinitionEntity definition) {
        String json = toJson(reportConfig(definition));
        String hash = sha256(json);
        ReportDefinitionVersionEntity existing = ReportDefinitionVersionEntity
                .<ReportDefinitionVersionEntity>find("definitionId = ?1 and configHash = ?2",
                        definition.id, hash)
                .firstResult();
        if (existing != null) {
            return existing.id;
        }
        ReportDefinitionVersionEntity version = new ReportDefinitionVersionEntity();
        version.definitionId = definition.id;
        version.configHash = hash;
        version.configSnapshot = json;
        version.createdOn = Instant.now();
        version.persist();
        return version.id;
    }

    /**
     * Builds the API view of a job version compared with the job's current configuration.
     *
     * @param version the stored version
     * @param current the job's current state, or null if it no longer exists
     * @return the snapshot bean
     */
    public ConfigurationSnapshot toBean(ScheduledJobVersionEntity version, ScheduledJobEntity current) {
        return compare(version.id, version.configHash, version.configSnapshot, version.createdOn,
                current == null ? null : jobConfig(current));
    }

    /**
     * Builds the API view of a report definition version compared with the current definition.
     *
     * @param version the stored version
     * @param current the definition's current state, or null if it no longer exists
     * @return the snapshot bean
     */
    public ConfigurationSnapshot toBean(ReportDefinitionVersionEntity version,
                                        ReportDefinitionEntity current) {
        return compare(version.id, version.configHash, version.configSnapshot, version.createdOn,
                current == null ? null : reportConfig(current));
    }

    /**
     * Execution-relevant configuration of a scheduled job, with secrets redacted.
     *
     * @param job the job
     * @return a sorted map of field name to value (unset fields omitted)
     */
    public Map<String, Object> jobConfig(ScheduledJobEntity job) {
        Map<String, Object> config = new TreeMap<>();
        put(config, "executionMode", job.executionMode);
        put(config, "promptTemplate", job.promptTemplate);
        put(config, "scriptTemplate", job.scriptTemplate);
        put(config, "engine", job.engine);
        put(config, "model", job.model);
        put(config, "allowedTools", job.allowedTools);
        put(config, "maxSteps", job.maxSteps);
        put(config, "maxBudgetUsd", job.maxBudgetUsd);
        put(config, "timeoutSeconds", job.timeoutSeconds);
        put(config, "environment", redactEnvironment(job.environment));
        return config;
    }

    /**
     * Execution-relevant configuration of a report definition, with secrets redacted.
     *
     * @param definition the report definition
     * @return a sorted map of field name to value (unset fields omitted)
     */
    public Map<String, Object> reportConfig(ReportDefinitionEntity definition) {
        Map<String, Object> config = new TreeMap<>();
        put(config, "promptTemplate", definition.promptTemplate);
        put(config, "titleTemplate", definition.titleTemplate);
        put(config, "timeWindow", definition.timeWindow);
        put(config, "engine", definition.engine);
        put(config, "model", definition.model);
        put(config, "allowedTools", definition.allowedTools);
        put(config, "maxSteps", definition.maxSteps);
        put(config, "maxBudgetUsd", definition.maxBudgetUsd);
        put(config, "timeoutSeconds", definition.timeoutSeconds);
        put(config, "environment", redactEnvironment(definition.environment));
        return config;
    }

    private static void put(Map<String, Object> config, String key, Object value) {
        if (value != null && !(value instanceof String s && s.isEmpty())) {
            config.put(key, value);
        }
    }

    /**
     * Parses an environment JSON object and keeps only values that are exactly a secret
     * reference; every other value is replaced by {@link #REDACTED}. Keys are kept.
     */
    private Map<String, String> redactEnvironment(String environmentJson) {
        if (environmentJson == null || environmentJson.isBlank()) {
            return null;
        }
        Map<String, Object> raw;
        try {
            raw = objectMapper.readValue(environmentJson, new TypeReference<Map<String, Object>>() { });
        } catch (JsonProcessingException e) {
            Map<String, String> unreadable = new TreeMap<>();
            unreadable.put("(unparseable)", REDACTED);
            return unreadable;
        }
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        Map<String, String> redacted = new TreeMap<>();
        raw.forEach((key, value) -> redacted.put(key,
                value instanceof String s && SECRET_REF.matcher(s.trim()).matches()
                        ? s.trim() : REDACTED));
        return redacted;
    }

    private ConfigurationSnapshot compare(Long versionId, String hash, String snapshotJson,
                                          Instant capturedOn, Map<String, Object> current) {
        Map<String, Object> used = fromJson(snapshotJson);
        String currentHash = current == null ? null : sha256(toJson(current));

        ConfigurationSnapshot bean = new ConfigurationSnapshot();
        bean.setVersionId(versionId);
        bean.setConfigHash(hash);
        bean.setCurrentConfigHash(currentHash);
        bean.setCapturedOn(capturedOn == null ? null : Date.from(capturedOn));
        bean.setChanged(!hash.equals(currentHash));

        Set<String> names = new TreeSet<>(used.keySet());
        if (current != null) {
            names.addAll(current.keySet());
        }
        List<ConfigurationField> fields = new ArrayList<>();
        names.forEach(name -> {
            String value = render(used.get(name));
            String currentValue = current == null ? null : render(current.get(name));
            ConfigurationField field = new ConfigurationField();
            field.setName(name);
            field.setValue(value);
            field.setCurrentValue(currentValue);
            field.setChanged(current != null && !Objects.equals(value, currentValue));
            fields.add(field);
        });
        bean.setFields(fields);
        return bean;
    }

    private String render(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof String s) {
            return s;
        }
        if (value instanceof Map<?, ?>) {
            return toJson(value);
        }
        return String.valueOf(value);
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialize configuration snapshot", e);
        }
    }

    private Map<String, Object> fromJson(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<TreeMap<String, Object>>() { });
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot read configuration snapshot", e);
        }
    }

    /**
     * Returns the lowercase hex SHA-256 hash of a string.
     *
     * @param text the text to hash
     * @return the hash
     */
    static String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
