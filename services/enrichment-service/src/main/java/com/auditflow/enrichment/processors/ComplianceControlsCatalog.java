package com.auditflow.enrichment.processors;

import com.auditflow.common.enums.EventType;
import com.auditflow.common.model.ComplianceControl;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Loads the config-driven compliance control definitions from
 * {@code compliance-controls/*.yaml} (canonical source:
 * {@code shared/compliance-controls}) and indexes them by the event types
 * they accept as evidence. Fails fast at startup on a malformed file - a
 * silently empty catalog would mean events stop being classified.
 */
@Component
public class ComplianceControlsCatalog {

    private static final String CONTROLS_LOCATION = "classpath*:compliance-controls/*.yaml";

    private final Map<EventType, List<ComplianceControl>> controlsByEventType;

    public ComplianceControlsCatalog() {
        this(CONTROLS_LOCATION);
    }

    ComplianceControlsCatalog(String location) {
        Map<EventType, List<ComplianceControl>> index = new EnumMap<>(EventType.class);
        try {
            Resource[] resources = new PathMatchingResourcePatternResolver().getResources(location);
            if (resources.length == 0) {
                throw new IllegalStateException("No compliance control files found at " + location);
            }
            Yaml yaml = new Yaml();
            for (Resource resource : resources) {
                try (InputStream in = resource.getInputStream()) {
                    loadFile(yaml.load(in), resource.getFilename(), index);
                }
            }
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load compliance controls from " + location, e);
        }
        index.replaceAll((type, controls) -> List.copyOf(controls));
        this.controlsByEventType = index;
    }

    @SuppressWarnings("unchecked")
    private static void loadFile(Object document, String fileName,
                                 Map<EventType, List<ComplianceControl>> index) {
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
            ComplianceControl built = ComplianceControl.builder()
                    .controlId(controlId)
                    .framework(framework)
                    .name(name)
                    .description(description == null ? null : description.toString().strip())
                    .build();
            for (Object typeName : typeNames) {
                EventType type = EventType.valueOf(typeName.toString());
                index.computeIfAbsent(type, t -> new ArrayList<>()).add(built);
            }
        }
    }

    private static String required(Map<String, Object> control, String key, String fileName) {
        Object value = control.get(key);
        if (value == null) {
            throw new IllegalStateException("Missing '%s' on a control in %s".formatted(key, fileName));
        }
        return value.toString();
    }

    public List<ComplianceControl> controlsFor(EventType type) {
        return controlsByEventType.getOrDefault(type, List.of());
    }
}
