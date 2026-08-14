package com.auditflow.reporting.rag;

import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Loads the config-driven compliance control definitions from
 * {@code compliance-controls/*.yaml} (canonical source:
 * {@code shared/compliance-controls}) as a flat corpus for the RAG insights
 * endpoint. Fails fast at startup on a malformed file - a silently empty
 * corpus would mean the endpoint retrieves nothing. Deliberately mirrors
 * enrichment-service's {@code ComplianceControlsCatalog} rather than sharing
 * code: that loader indexes by event type for classification, this one keeps
 * the flat documents (including eventTypes) for embedding.
 */
@Component
public class ReportingControlsCatalog {

    private static final String CONTROLS_LOCATION = "classpath*:compliance-controls/*.yaml";

    private final List<ControlDocument> documents;

    public ReportingControlsCatalog() {
        this(CONTROLS_LOCATION);
    }

    ReportingControlsCatalog(String location) {
        List<ControlDocument> loaded = new ArrayList<>();
        try {
            Resource[] resources = new PathMatchingResourcePatternResolver().getResources(location);
            if (resources.length == 0) {
                throw new IllegalStateException("No compliance control files found at " + location);
            }
            Yaml yaml = new Yaml();
            for (Resource resource : resources) {
                try (InputStream in = resource.getInputStream()) {
                    loadFile(yaml.load(in), resource.getFilename(), loaded);
                }
            }
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load compliance controls from " + location, e);
        }
        this.documents = List.copyOf(loaded);
    }

    @SuppressWarnings("unchecked")
    private static void loadFile(Object document, String fileName, List<ControlDocument> out) {
        if (!(document instanceof Map<?, ?> root) || root.get("framework") == null) {
            throw new IllegalStateException("Missing 'framework' in " + fileName);
        }
        String framework = root.get("framework").toString();
        Object controls = root.get("controls");
        if (!(controls instanceof List<?> controlList) || controlList.isEmpty()) {
            throw new IllegalStateException("Missing or empty 'controls' in " + fileName);
        }
        for (Object entry : controlList) {
            Map<String, Object> control = (Map<String, Object>) entry;
            String controlId = required(control, "controlId", fileName);
            String name = required(control, "name", fileName);
            Object description = control.get("description");
            Object eventTypes = control.get("eventTypes");
            if (!(eventTypes instanceof List<?> typeNames) || typeNames.isEmpty()) {
                throw new IllegalStateException(
                        "Control %s in %s declares no eventTypes".formatted(controlId, fileName));
            }
            out.add(new ControlDocument(
                    controlId,
                    framework,
                    name,
                    description == null ? "" : description.toString().strip(),
                    typeNames.stream().map(Object::toString).toList()));
        }
    }

    private static String required(Map<String, Object> control, String key, String fileName) {
        Object value = control.get(key);
        if (value == null) {
            throw new IllegalStateException("Missing '%s' on a control in %s".formatted(key, fileName));
        }
        return value.toString();
    }

    public List<ControlDocument> documents() {
        return documents;
    }
}
