package info.lifeinuk.backend.guides;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Creates immutable DRAFT revisions from validated import definitions. Never writes the public guide
 * projection and has no publication capability.
 */
@Service
class GuideRevisionService {
    enum Outcome { CREATED, EXISTING }
    record Result(Outcome outcome, GuideRevision revision) { }

    private final GuideRevisionStore store;
    private final GuideImportReader reader;
    private final JsonMapper json = JsonMapper.builder().build();

    GuideRevisionService(GuideRevisionStore store, GuideImportReader reader) {
        this.store = store;
        this.reader = reader;
    }

    /**
     * Stores {@code draft} as a new revision, or returns the existing revision with identical canonical content.
     * The draft must be DRAFT with an explicit evidence collection; {@code []} is allowed and differs from absent.
     */
    @Transactional
    public Result create(GuideImportDefinition draft, UUID basedOnRevisionId, String operator, boolean aiAssisted, String note) {
        Objects.requireNonNull(draft, "Validated definition is required");
        if (draft.status() != GuideStatus.DRAFT) {
            throw new IllegalArgumentException("A revision must be DRAFT content (status DRAFT, publishedAt null)");
        }
        if (draft.evidence() == null) {
            throw new IllegalArgumentException("A revision requires an explicit evidence collection; use [] only deliberately");
        }
        String createdBy = operator(operator);
        if (note != null && (note.isBlank() || note.length() > 2000)) {
            throw new IllegalArgumentException("note must be nonblank text of at most 2000 characters");
        }
        String canonicalForm = GuideContentDigest.canonicalForm(draft);
        String digest = GuideContentDigest.digest(draft);

        store.lockSlug(draft.slug());
        var existing = store.findByDigest(draft.slug(), digest);
        if (existing.isPresent()) {
            return new Result(Outcome.EXISTING, existing.get());
        }
        if (basedOnRevisionId != null) {
            var parent = store.findById(basedOnRevisionId)
                    .orElseThrow(() -> new IllegalArgumentException("based-on revision does not exist: " + basedOnRevisionId));
            if (!parent.slug().equals(draft.slug())) {
                throw new IllegalArgumentException("based-on revision belongs to a different slug: " + parent.slug());
            }
        }
        UUID id = UUID.randomUUID();
        store.insert(id, draft.slug(), store.nextRevisionNumber(draft.slug()), canonicalForm, digest,
                basedOnRevisionId, createdBy, aiAssisted, note);
        return new Result(Outcome.CREATED, store.findById(id).orElseThrow());
    }

    @Transactional(readOnly = true)
    public java.util.Optional<GuideRevision> find(UUID id) { return store.findById(id); }

    @Transactional(readOnly = true)
    public java.util.Optional<GuideRevisionStore.PublicState> publicState(String slug) { return store.publicState(slug); }

    /**
     * Rebuilds the validated definition from a stored canonical form using the production reader. Fails if the
     * text is not guide-content-v1 or no longer passes validation.
     */
    GuideImportDefinition definition(String canonicalForm) {
        ObjectNode root;
        try {
            root = (ObjectNode) json.readTree(canonicalForm);
        } catch (JacksonException | ClassCastException invalid) {
            throw new IllegalArgumentException("Stored canonical form is not a JSON object", invalid);
        }
        var version = root.remove("canonicalization");
        if (version == null || !version.isString() || !GuideContentDigest.VERSION.equals(version.stringValue())) {
            throw new IllegalArgumentException("Stored canonical form is not " + GuideContentDigest.VERSION);
        }
        // guide-content-v1 writes an absent evidence collection as null; the reader expresses that as absence.
        if (root.has("evidence") && root.get("evidence").isNull()) {
            root.remove("evidence");
        }
        return reader.parse(json.writeValueAsString(root).getBytes(StandardCharsets.UTF_8));
    }

    static String operator(String operator) {
        if (operator == null || operator.isBlank() || operator.length() > 100
                || operator.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("LIFE_IN_UK_OPERATOR must identify the operator (nonblank, at most 100 characters)");
        }
        return operator;
    }
}
