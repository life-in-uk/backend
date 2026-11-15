package info.lifeinuk.backend.underground;

import info.lifeinuk.backend.evidence.EvidenceArtifact;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Pure source-specific interpretation. Unknown metadata is ignored, known facts are validated. */
@Component
public final class TflUndergroundStatusParser {
    private final JsonMapper json = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    public UndergroundStatusInterpretation parse(EvidenceArtifact evidence) {
        Objects.requireNonNull(evidence, "Evidence artifact is required");
        return parse(evidence.getId(), evidence.getPayload(), evidence.getObservedAt());
    }

    /**
     * The same validation for an observation that is not persisted as evidence (the latest-only
     * Underground refresh). {@code observationId} identifies this observation in Current State.
     */
    public UndergroundStatusInterpretation parse(UUID observationId, byte[] payload, Instant observedAt) {
        Objects.requireNonNull(payload, "Payload is required");
        JsonNode root;
        try {
            root = json.readTree(payload);
        } catch (JacksonException invalid) {
            throw new IllegalArgumentException("Evidence is not one valid JSON document");
        }
        if (root == null || !root.isArray()) {
            throw new IllegalArgumentException("Underground evidence root must be an array");
        }
        var lines = new ArrayList<UndergroundLineStatus>();
        var identities = new HashSet<String>();
        for (JsonNode line : root) {
            requireObject(line, "Line");
            String id = requireText(line.get("id"), "Line id");
            String name = requireText(line.get("name"), "Line name");
            if (!"tube".equals(requireText(line.get("modeName"), "Line modeName"))) {
                throw new IllegalArgumentException("Line modeName must be tube");
            }
            if (!identities.add(id)) {
                throw new IllegalArgumentException("Duplicate line identity");
            }
            JsonNode entries = line.get("lineStatuses");
            if (entries == null || !entries.isArray()) {
                throw new IllegalArgumentException("Line lineStatuses must be an array");
            }
            var statuses = new ArrayList<UndergroundOperationalStatus>();
            for (JsonNode entry : entries) {
                requireObject(entry, "Status");
                JsonNode severity = entry.get("statusSeverity");
                if (severity == null || !severity.isIntegralNumber() || !severity.canConvertToInt()) {
                    throw new IllegalArgumentException("Status severity must be a 32-bit integer");
                }
                String description = requireText(entry.get("statusSeverityDescription"), "Status description");
                JsonNode reason = entry.get("reason");
                Optional<String> reasonText = Optional.empty();
                if (reason != null && !reason.isNull()) {
                    if (!reason.isString()) {
                        throw new IllegalArgumentException("Status reason must be a string or null");
                    }
                    reasonText = Optional.of(reason.stringValue());
                }
                statuses.add(new UndergroundOperationalStatus(severity.intValue(), description, reasonText));
            }
            lines.add(new UndergroundLineStatus(id, name, statuses));
        }
        return new UndergroundStatusInterpretation(observationId, observedAt, lines);
    }

    private static void requireObject(JsonNode value, String label) {
        if (value == null || !value.isObject()) {
            throw new IllegalArgumentException(label + " must be an object");
        }
    }

    private static String requireText(JsonNode value, String label) {
        if (value == null || !value.isString() || value.stringValue().isBlank()) {
            throw new IllegalArgumentException(label + " must be a nonblank string");
        }
        return value.stringValue();
    }
}
