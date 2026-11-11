package info.lifeinuk.backend.guides;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Service
class GuideContentBatchService {
    static final Set<String> LOCALES = Set.of("zh-CN", "zh-TW", "en", "ur", "hi");
    private static final Set<String> TRANSLATIONS = Set.of("zh-TW", "en", "ur", "hi");
    private final GuideContentBatchStore batches;
    private final GuideRevisionService revisions;
    private final GuideImportReader reader;
    private final GuidePublicationService publications;
    private final JsonMapper json = JsonMapper.builder().build();

    GuideContentBatchService(GuideContentBatchStore batches, GuideRevisionService revisions,
            GuideImportReader reader, GuidePublicationService publications) {
        this.batches = batches; this.revisions = revisions;
        this.reader = reader; this.publications = publications;
    }

    @Transactional
    public BatchReceipt create(JsonNode request) {
        fields(request, Set.of("idempotencyKey", "policyVersion", "sourceVersion", "validFrom", "validUntil",
                "basedOnRevisionId", "source"), "batch request");
        String key = identifier(request, "idempotencyKey", 200);
        String policyVersion = identifier(request, "policyVersion", 120);
        String sourceVersion = identifier(request, "sourceVersion", 120);
        Instant validFrom = instant(request, "validFrom", false);
        Instant validUntil = instant(request, "validUntil", true);
        if (validUntil != null && !validUntil.isAfter(validFrom)) throw new IllegalArgumentException("validUntil must be after validFrom");
        UUID basedOn = request.hasNonNull("basedOnRevisionId") ? uuid(request.get("basedOnRevisionId"), "basedOnRevisionId") : null;
        if (!request.has("source") || request.get("source").isNull()) throw new IllegalArgumentException("source is required");
        var source = reader.parse(json.writeValueAsString(request.get("source")).getBytes(StandardCharsets.UTF_8));
        if (source.status() != GuideStatus.DRAFT || source.publishedAt() != null || source.evidence() == null) {
            throw new IllegalArgumentException("Chinese source must be DRAFT with null publishedAt and explicit evidence");
        }
        String sourceDigest = GuideContentDigest.digest(source);
        String requestDigest = sha256(String.join("\n", source.slug(), key, policyVersion, sourceVersion,
                validFrom.toString(), validUntil == null ? "" : validUntil.toString(), sourceDigest,
                basedOn == null ? "" : basedOn.toString()));
        batches.lockIdempotencyKey(key);
        var existing = batches.byIdempotencyKey(key);
        if (existing.isPresent()) {
            if (!requestDigest.equals(existing.get().requestDigest())) throw new IdempotencyConflict("Idempotency key was used with different content");
            return receipt(existing.get());
        }
        batches.lockSlug(source.slug());
        var revisionResult = revisions.create(source, basedOn, "n8n-content-draft", true, "content-batch:" + key);
        var revision = revisionResult.revision();
        UUID batchId = UUID.randomUUID();
        var batch = batches.insertBatch(batchId, source.slug(), revision.id(), revision.draftDigest(), key,
                requestDigest, policyVersion, sourceVersion, validFrom, validUntil, "n8n-content-draft");
        batches.addLocale(UUID.randomUUID(), batchId, "zh-CN", revision.id(), revision.canonicalForm(), revision.draftDigest());
        return receipt(batch);
    }

    @Transactional
    public LocaleReceipt translate(UUID batchId, String locale, byte[] payload) {
        requireTranslationLocale(locale);
        var batch = batches.byId(batchId).orElseThrow(() -> new NotFound("Batch not found"));
        batches.requireOpenDraft(batch);
        var translated = reader.parse(payload);
        if (translated.status() != GuideStatus.DRAFT || translated.publishedAt() != null || translated.evidence() == null) {
            throw new IllegalArgumentException("Locale content must be DRAFT with null publishedAt and explicit evidence");
        }
        if (!batch.slug().equals(translated.slug())) throw new IllegalArgumentException("Locale slug must match the Chinese source");
        var sourceRevision = revisions.find(batch.sourceRevisionId()).orElseThrow(() -> new IllegalStateException("Source revision missing"));
        var source = revisions.definition(sourceRevision.canonicalForm());
        if (!source.category().equals(translated.category())) {
            throw new IllegalArgumentException("Locale category must match the Chinese source");
        }
        validateSourceReferences(source, translated);
        String canonical = GuideContentDigest.canonicalForm(translated);
        String hash = GuideContentDigest.digest(translated);
        var stored = batches.replaceLocale(UUID.randomUUID(), batchId, locale, batch.sourceRevisionId(), canonical, hash);
        return localeReceipt(stored);
    }

    @Transactional
    public LocaleReceipt failTranslation(UUID batchId, String locale, String failureCode) {
        requireTranslationLocale(locale);
        if (!Set.of("MODEL_ERROR", "INVALID_OUTPUT", "SOURCE_MISMATCH", "TIMEOUT").contains(failureCode)) {
            throw new IllegalArgumentException("Unsupported failure code");
        }
        var batch = batches.byId(batchId).orElseThrow(() -> new NotFound("Batch not found"));
        batches.requireOpenDraft(batch);
        return localeReceipt(batches.failLocale(UUID.randomUUID(), batchId, locale, batch.sourceRevisionId(), failureCode));
    }

    @Transactional
    public LocaleReceipt review(UUID batchId, String locale, JsonNode request, String reviewer) {
        if (!LOCALES.contains(locale)) throw new IllegalArgumentException("Unsupported locale");
        fields(request, Set.of("contentHash", "passed", "findings"), "review request");
        String hash = text(request, "contentHash", 64);
        if (!Set.of("claude-independent", "mock-adapter").contains(reviewer)) {
            throw new IllegalArgumentException("Unsupported authenticated reviewer identity");
        }
        if (!hash.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("contentHash must be lower-case SHA-256");
        if (!request.has("passed") || !request.get("passed").isBoolean()) throw new IllegalArgumentException("passed must be boolean");
        JsonNode findings = request.get("findings");
        if (findings == null || !findings.isArray() || findings.size() > 100) throw new IllegalArgumentException("findings must be an array of at most 100 items");
        String result = json.writeValueAsString(findings);
        if (result.length() > 64_000) throw new IllegalArgumentException("review findings exceed the size limit");
        var batch = batches.byId(batchId).orElseThrow(() -> new NotFound("Batch not found"));
        batches.requireOpenDraft(batch);
        var reviewed = batches.review(batchId, locale, hash, result, reviewer, request.get("passed").booleanValue());
        return localeReceipt(reviewed);
    }

    @Transactional(readOnly = true)
    public BatchDetail detail(UUID batchId) {
        var batch = batches.byId(batchId).orElseThrow(() -> new NotFound("Batch not found"));
        return new BatchDetail(receipt(batch), batches.locales(batchId).stream().map(GuideContentBatchService::localeReceipt).toList());
    }

    @Transactional(readOnly = true)
    public ExpiryReport expiryReport() {
        return new ExpiryReport(Instant.now(), batches.expiryCandidates());
    }

    @Transactional
    public BatchReceipt approve(UUID batchId, String actor) {
        actor = GuideRevisionService.operator(actor);
        var batch = batches.byId(batchId).orElseThrow(() -> new NotFound("Batch not found"));
        if (batch.validUntil() != null && !batch.validUntil().isAfter(Instant.now())) {
            batches.expire(batchId);
            throw new IllegalStateException("Expired content cannot be approved");
        }
        batches.approve(batchId, actor);
        return receipt(batches.byId(batchId).orElseThrow());
    }

    @Transactional
    public BatchReceipt publish(UUID batchId, PublishRequest request, String actor) {
        actor = GuideRevisionService.operator(actor);
        var batch = batches.byId(batchId).orElseThrow(() -> new NotFound("Batch not found"));
        if (!"APPROVED".equals(batch.status())) throw new GuideContentBatchStore.IncompleteBatch("Batch needs explicit human approval");
        if (batch.validUntil() != null && !batch.validUntil().isAfter(Instant.now())) {
            batches.expire(batchId);
            throw new IllegalStateException("Expired content cannot be published");
        }
        if (batch.validFrom().isAfter(Instant.now())) {
            throw new IllegalStateException("Content cannot be published before its validity begins");
        }
        batches.lockSlug(batch.slug());
        var revisionsForBatch = batches.locales(batchId);
        if (!fiveReviewed(revisionsForBatch)) throw new GuideContentBatchStore.IncompleteBatch("Batch review hashes are incomplete or stale");
        publications.publish(new GuidePublicationService.PublishRequest(batch.sourceRevisionId(), batch.sourceDigest(),
                request.expectCurrentPublicationId(), request.checklistId(), request.verifiedOn(), true, actor));
        batches.publish(batchId);
        return receipt(batches.byId(batchId).orElseThrow());
    }

    private static BatchReceipt receipt(GuideContentBatchStore.Batch batch) {
        return new BatchReceipt(batch.id(), batch.slug(), batch.sourceRevisionId(), batch.sourceDigest(), batch.status(),
                batch.validFrom(), batch.validUntil());
    }
    private static LocaleReceipt localeReceipt(GuideContentBatchStore.LocaleRevision row) {
        return new LocaleReceipt(row.id(), row.batchId(), row.locale(), row.sourceRevisionId(), row.contentHash(),
                row.validationStatus(), row.reviewStatus(), row.reviewedHash(), row.failureCode(), row.reviewer());
    }
    private static boolean fiveReviewed(List<GuideContentBatchStore.LocaleRevision> rows) {
        return LOCALES.size() == rows.size() && rows.stream().allMatch(row -> "VALID".equals(row.validationStatus())
                && "PASSED".equals(row.reviewStatus()) && row.contentHash().equals(row.reviewedHash()));
    }
    private static void requireTranslationLocale(String locale) {
        if (!TRANSLATIONS.contains(locale)) throw new IllegalArgumentException("Only zh-TW, en, ur and hi are translation locales");
    }
    private static void validateSourceReferences(GuideImportDefinition source, GuideImportDefinition translated) {
        var sourceRefs = source.sources().stream().map(s -> s.key() + "\n" + s.url() + "\n" + s.accessedAt()).toList();
        var translatedRefs = translated.sources().stream().map(s -> s.key() + "\n" + s.url() + "\n" + s.accessedAt()).toList();
        if (!sourceRefs.equals(translatedRefs)) throw new IllegalArgumentException("Locale source references must match the Chinese source in order");
        var sourceEvidence = source.evidence().stream().map(GuideImportDefinition.Evidence::key).sorted().toList();
        var translatedEvidence = translated.evidence().stream().map(GuideImportDefinition.Evidence::key).sorted().toList();
        if (!sourceEvidence.equals(translatedEvidence)) throw new IllegalArgumentException("Locale evidence keys must match the Chinese source");
        for (int i = 0; i < source.evidence().size(); i++) {
            var sourceItem = source.evidence().get(i);
            var translatedItem = translated.evidence().stream().filter(item -> item.key().equals(sourceItem.key())).findFirst().orElseThrow();
            if (!sourceItem.supports().stream().map(GuideImportDefinition.Support::sourceKey).sorted().toList()
                    .equals(translatedItem.supports().stream().map(GuideImportDefinition.Support::sourceKey).sorted().toList())) {
                throw new IllegalArgumentException("Locale evidence support source keys must match the Chinese source");
            }
        }
    }
    private static void fields(JsonNode node, Set<String> allowed, String label) {
        if (node == null || !node.isObject()) throw new IllegalArgumentException(label + " must be an object");
        for (var field : node.properties()) if (!allowed.contains(field.getKey())) throw new IllegalArgumentException(label + " contains unsupported field: " + field.getKey());
    }
    private static String identifier(JsonNode node, String name, int max) {
        String value = text(node, name, max);
        if (!value.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0," + (max - 1) + "}")) throw new IllegalArgumentException(name + " has invalid characters");
        return value;
    }
    private static String text(JsonNode node, String name, int max) {
        var value = node.get(name);
        if (value == null || !value.isString() || value.stringValue().isBlank() || value.stringValue().length() > max) {
            throw new IllegalArgumentException(name + " is required and must be bounded text");
        }
        return value.stringValue();
    }
    private static Instant instant(JsonNode node, String name, boolean nullable) {
        var value = node.get(name);
        if ((value == null || value.isNull()) && nullable) return null;
        if (value == null || !value.isString()) throw new IllegalArgumentException(name + " must be an ISO instant");
        try { return Instant.parse(value.stringValue()); }
        catch (RuntimeException invalid) { throw new IllegalArgumentException(name + " must be an ISO instant"); }
    }
    private static UUID uuid(JsonNode value, String name) {
        if (!value.isString()) throw new IllegalArgumentException(name + " must be a UUID");
        try { return UUID.fromString(value.stringValue()); }
        catch (IllegalArgumentException invalid) { throw new IllegalArgumentException(name + " must be a UUID"); }
    }
    private static String sha256(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    record BatchReceipt(UUID batchId, String slug, UUID sourceRevisionId, String sourceContentHash, String status,
            Instant validFrom, Instant validUntil) { }
    record BatchDetail(BatchReceipt batch, List<LocaleReceipt> locales) { }
    record ExpiryReport(Instant generatedAt, List<GuideContentBatchStore.ExpiryCandidate> candidates) { }
    record LocaleReceipt(UUID localeRevisionId, UUID batchId, String locale, UUID sourceRevisionId, String contentHash,
            String validationStatus, String reviewStatus, String reviewedHash, String failureCode, String reviewer) { }
    record PublishRequest(UUID expectCurrentPublicationId, String checklistId, LocalDate verifiedOn) { }
    static class IdempotencyConflict extends IllegalStateException { IdempotencyConflict(String message) { super(message); } }
    static class NotFound extends IllegalArgumentException { NotFound(String message) { super(message); } }
}
