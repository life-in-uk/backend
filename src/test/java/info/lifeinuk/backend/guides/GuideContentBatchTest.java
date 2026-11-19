package info.lifeinuk.backend.guides;

import info.lifeinuk.backend.support.IsolatedPostgres;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.Instant;
import java.util.UUID;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ContextConfiguration;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

/** Isolated PostgreSQL lifecycle tests; synthetic data only. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "spring.profiles.active=local",
    "spring.flyway.schemas=guide_content_batch_tests", "spring.flyway.default-schema=guide_content_batch_tests",
    "spring.jpa.properties.hibernate.default_schema=guide_content_batch_tests", "spring.datasource.hikari.schema=guide_content_batch_tests",
    "life-in-uk.public-base-url=http://127.0.0.1:5173",
    "life-in-uk.editorial.draft-token=test-draft-token", "life-in-uk.editorial.review-token=test-review-token",
    "life-in-uk.editorial.mock-review-token=test-mock-review-token",
    "life-in-uk.editorial.local-review-token=test-codex-local-review-token",
    "life-in-uk.editorial.approval-token=test-approval-token",
    "life-in-uk.editorial.approval-actor=synthetic-owner",
    "life-in-uk.editorial.owner-draft-password=test-owner-draft-password-0123456789abcdef",
    "life-in-uk.editorial.owner-approval-password=test-owner-approval-password-0123456789abcdef",
    "life-in-uk.editorial.owner-origins=http://127.0.0.1:5179",
    "server.servlet.session.cookie.secure=false"
})
@ContextConfiguration(initializers = IsolatedPostgres.Initializer.class)
class GuideContentBatchTest {
    @Autowired GuideContentBatchService batches;
    @Autowired GuidePublicationService publications;
    @Autowired GuideContentBatchStore store;
    @Autowired GuideRevisionService revisions;
    @Autowired GuideImportReader reader;
    @Autowired GuideQualityGate qualityGate;
    @Autowired JdbcTemplate jdbc;
    @LocalServerPort int port;
    private final JsonMapper json = JsonMapper.builder().build();

    private HttpResponse<String> get(String path) throws Exception {
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> getWithDraftCredential(String path) throws Exception {
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Authorization", "Bearer test-draft-token").GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    @BeforeEach
    void clear() {
        jdbc.execute("TRUNCATE guide_revision_body_redaction, guide_locale_current, guide_locale_revision, guide_content_batch, guide_publication, guide_workflow_activation, guide_revision, guide_evidence_support, guide_evidence, guide_source, guide");
    }

    private ObjectNode source(String title) throws Exception {
        var root = (ObjectNode) json.readTree(GuideContentDigestTest.fixture("synthetic-evidence-import.json"));
        root.put("slug", "batch-" + UUID.randomUUID().toString().substring(0, 8));
        root.put("title", title);
        return root;
    }

    private ObjectNode sourceWithSlug(String title, String slug) throws Exception {
        var root = source(title);
        root.put("slug", slug);
        return root;
    }

    private ObjectNode createRequest(String key, ObjectNode source) {
        var req = json.createObjectNode();
        req.put("idempotencyKey", key);
        req.put("policyVersion", "policy-v1");
        req.put("sourceVersion", "source-v1");
        req.put("validFrom", "2026-01-01T00:00:00Z");
        req.putNull("validUntil");
        req.set("source", source);
        return req;
    }

    private ObjectNode translation(ObjectNode source, String title) {
        var translated = source.deepCopy();
        translated.put("title", title);
        translated.put("summary", "Synthetic translated summary");
        return translated;
    }

    private void fillAndReview(GuideContentBatchService.BatchReceipt batch, ObjectNode source, String reviewer) throws Exception {
        var locales = new java.util.LinkedHashMap<String, String>();
        locales.put("zh-CN", source.get("title").stringValue());
        locales.put("zh-TW", "Traditional Chinese"); locales.put("en", "English");
        locales.put("ur", "Urdu"); locales.put("hi", "Hindi");
        for (var entry : locales.entrySet()) {
            if (!entry.getKey().equals("zh-CN")) {
                batches.translate(batch.batchId(), entry.getKey(), json.writeValueAsBytes(translation(source, entry.getValue())));
            }
            String hash = store.locale(batch.batchId(), entry.getKey()).orElseThrow().contentHash();
            var review = json.createObjectNode(); review.put("contentHash", hash); review.put("passed", true);
            review.putArray("findings");
            batches.review(batch.batchId(), entry.getKey(), review, reviewer);
        }
        assertThat(store.byId(batch.batchId()).orElseThrow().status()).isEqualTo("READY");
    }

    @Test
    void incompleteBatchCannotBeApproved() throws Exception {
        var batch = batches.create(createRequest("batch-incomplete", source("Incomplete")));
        assertThat(batch.status()).isEqualTo("DRAFT");
        assertThat(store.locales(batch.batchId())).hasSize(1).extracting(GuideContentBatchStore.LocaleRevision::locale).containsExactly("zh-CN");
        assertThatThrownBy(() -> batches.approve(batch.batchId(), "editor"))
                .isInstanceOf(GuideContentBatchStore.IncompleteBatch.class);
        assertThatThrownBy(() -> batches.publish(batch.batchId(),
                new GuideContentBatchService.PublishRequest(null, "standard-v1", LocalDate.now(ZoneOffset.UTC)), "editor"))
                .isInstanceOf(GuideContentBatchStore.IncompleteBatch.class);
        assertThat(store.byId(batch.batchId()).orElseThrow().status()).isEqualTo("DRAFT");
    }

    @Test
    void duplicateRequestReturnsSameBatchAndCreatesOneSourceRevision() throws Exception {
        var request = createRequest("batch-idempotent", source("Idempotent"));
        var first = batches.create(request);
        var again = batches.create(request);
        assertThat(again.batchId()).isEqualTo(first.batchId());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM guide_content_batch", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM guide_revision", Integer.class)).isEqualTo(1);
    }

    @Test
    void failedLocaleDoesNotRemoveSuccessfulLocaleAndCanRetry() throws Exception {
        var source = source("Retry");
        var batch = batches.create(createRequest("batch-retry", source));
        var successful = batches.translate(batch.batchId(), "en", json.writeValueAsBytes(translation(source, "English draft")));
        var failed = batches.failTranslation(batch.batchId(), "ur", "TIMEOUT");
        assertThat(failed.validationStatus()).isEqualTo("FAILED");
        assertThat(store.locale(batch.batchId(), "en").orElseThrow().contentHash()).isEqualTo(successful.contentHash());
        var retried = batches.translate(batch.batchId(), "ur", json.writeValueAsBytes(translation(source, "Urdu draft")));
        assertThat(retried.validationStatus()).isEqualTo("VALID");
        assertThat(store.locale(batch.batchId(), "en")).isPresent();
    }

    @Test
    void manualTranslationExportImportRoundTripsFourDraftsWithoutReviewingThem() throws Exception {
        var source = source("Manual translation source");
        var batch = batches.create(createRequest("manual-translation-roundtrip", source));
        var exported = batches.translationPackage(batch.batchId());
        assertThat(exported.contractVersion()).isEqualTo("translation-package-v1");
        assertThat(exported.sourceRevisionId()).isEqualTo(batch.sourceRevisionId());
        assertThat(exported.sourceContentHash()).isEqualTo(batch.sourceContentHash());
        assertThat(exported.sourceGuide().has("canonicalization")).isFalse();
        assertThat(exported.requiredLocales()).containsExactly("zh-TW", "en", "ur", "hi");
        assertThat(exported.responseTemplate().get("translations")).hasSize(4);
        assertThat(exported.responseTemplate().get("translations").get(0).get("guide").has("evidence")).isTrue();

        var packageJson = json.createObjectNode();
        packageJson.put("contractVersion", "translation-package-v1");
        packageJson.put("batchId", batch.batchId().toString());
        packageJson.put("sourceRevisionId", batch.sourceRevisionId().toString());
        packageJson.put("sourceContentHash", batch.sourceContentHash());
        var translations = packageJson.putArray("translations");
        for (String locale : exported.requiredLocales()) {
            var item = translations.addObject();
            item.put("locale", locale);
            item.put("sourceRevisionId", batch.sourceRevisionId().toString());
            item.put("sourceContentHash", batch.sourceContentHash());
            var translated = source.deepCopy();
            translated.put("title", "Manual " + locale);
            item.set("guide", translated);
        }
        var imported = batches.importTranslations(batch.batchId(), packageJson);
        assertThat(imported).hasSize(4).allSatisfy(locale -> {
            assertThat(locale.validationStatus()).isEqualTo("VALID");
            assertThat(locale.reviewStatus()).isEqualTo("PENDING");
            assertThat(locale.contentHash()).matches("[0-9a-f]{64}");
            assertThat(locale.sourceRevisionId()).isEqualTo(batch.sourceRevisionId());
        });
        assertThat(store.locales(batch.batchId())).hasSize(5);
        assertThat(store.byId(batch.batchId()).orElseThrow().status()).isEqualTo("DRAFT");
    }

    @Test
    void manualImportRejectsChangedEvidenceAndDoesNotPersistAnyTranslation() throws Exception {
        var source = source("Evidence must remain unchanged");
        var batch = batches.create(createRequest("manual-translation-evidence", source));
        var packageJson = manualPackage(batch, source);
        var first = (ObjectNode) packageJson.get("translations").get(0).get("guide");
        first.withArray("evidence").get(0).withArray("supports").get(0).asObject().put("locator", "changed locator");
        assertThatThrownBy(() -> batches.importTranslations(batch.batchId(), packageJson))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("locators and excerpts");
        assertThat(store.locales(batch.batchId())).hasSize(1);
    }

    @Test
    void manualImportAllowsLocalizedSupportNotesButKeepsEvidenceLocatorsAndExcerptsExact() throws Exception {
        var source = source("Localized evidence notes");
        var batch = batches.create(createRequest("manual-translation-localized-notes", source));
        var packageJson = manualPackage(batch, source);
        var support = packageJson.get("translations").get(0).get("guide").get("evidence").get(0)
                .get("supports").get(0).asObject();
        support.put("note", "Localized explanation of the same source evidence.");
        var imported = batches.importTranslations(batch.batchId(), packageJson);
        assertThat(imported).hasSize(4).allSatisfy(locale -> assertThat(locale.validationStatus()).isEqualTo("VALID"));
        var stored = store.locale(batch.batchId(), "zh-TW").orElseThrow();
        assertThat(stored.canonicalForm()).contains("Localized explanation of the same source evidence.");
        assertThat(store.locales(batch.batchId())).hasSize(5);
    }

    @Test
    void manualImportRejectsSourceOrganisationOrTitleChangesWithoutPartialWrites() throws Exception {
        for (String field : List.of("organisation", "title")) {
            var source = source("Source metadata integrity " + field);
            var batch = batches.create(createRequest("manual-source-metadata-" + field, source));
            var packageJson = manualPackage(batch, source);
            var firstGuide = packageJson.get("translations").get(0).get("guide").asObject();
            firstGuide.withArray("sources").get(0).asObject().put(field, "Altered source " + field);
            assertThatThrownBy(() -> batches.importTranslations(batch.batchId(), packageJson))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("organisations and titles");
            assertThat(store.locales(batch.batchId())).as("no locale writes after changing source %s", field).hasSize(1);
            assertThat(store.byId(batch.batchId()).orElseThrow().status()).isEqualTo("DRAFT");
        }
    }

    @Test
    void supersededReadyBatchCannotBeApprovedOrPublished() throws Exception {
        var firstSource = source("Older batch");
        var first = batches.create(createRequest("superseded-ready-first", firstSource));
        fillAndReview(first, firstSource, "claude-independent");
        var newerSource = sourceWithSlug("Newer draft", first.slug());
        var newerRequest = createRequest("superseded-ready-newer", newerSource);
        newerRequest.put("basedOnRevisionId", first.sourceRevisionId().toString());
        newerRequest.put("sourceVersion", "source-v2");
        var newer = batches.create(newerRequest);

        assertThatThrownBy(() -> batches.approve(first.batchId(), "synthetic-owner"))
                .isInstanceOf(GuideContentBatchStore.StaleLocaleRevision.class).hasMessageContaining("newer Guide batch");
        assertThatThrownBy(() -> batches.publish(first.batchId(), new GuideContentBatchService.PublishRequest(
                null, "standard-v1", LocalDate.now(ZoneOffset.UTC)), "synthetic-owner"))
                .isInstanceOf(GuideContentBatchStore.StaleLocaleRevision.class).hasMessageContaining("newer Guide batch");
        assertThat(store.byId(first.batchId()).orElseThrow().status()).isEqualTo("READY");
        assertThat(store.byId(newer.batchId()).orElseThrow().status()).isEqualTo("DRAFT");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM guide_publication", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM guide", Integer.class)).isZero();
    }

    @Test
    void newerDraftDoesNotAlterHistoricalPublicationWhenOldBatchActionsAreRejected() throws Exception {
        var source = source("Published content remains intact");
        source.put("category", "health-nhs");
        var published = batches.create(createRequest("superseded-published-first", source));
        fillAndReview(published, source, "claude-independent");
        jdbc.update("INSERT INTO guide_workflow_activation (activated_by) VALUES ('synthetic-test')");
        batches.approve(published.batchId(), "synthetic-owner");
        batches.publish(published.batchId(), new GuideContentBatchService.PublishRequest(null, "standard-v1",
                LocalDate.now(ZoneOffset.UTC)), "synthetic-owner");
        String originalTitle = jdbc.queryForObject("SELECT title FROM guide WHERE slug = ?", String.class, published.slug());

        var newerSource = sourceWithSlug("Unpublished later draft", published.slug());
        newerSource.put("category", "health-nhs");
        var newerRequest = createRequest("superseded-published-newer", newerSource);
        newerRequest.put("basedOnRevisionId", published.sourceRevisionId().toString());
        newerRequest.put("sourceVersion", "source-v2");
        var newer = batches.create(newerRequest);

        assertThatThrownBy(() -> batches.approve(published.batchId(), "synthetic-owner"))
                .isInstanceOf(GuideContentBatchStore.StaleLocaleRevision.class);
        assertThatThrownBy(() -> batches.publish(published.batchId(), new GuideContentBatchService.PublishRequest(
                null, "standard-v1", LocalDate.now(ZoneOffset.UTC)), "synthetic-owner"))
                .isInstanceOf(GuideContentBatchStore.StaleLocaleRevision.class);
        assertThat(store.byId(published.batchId()).orElseThrow().status()).isEqualTo("PUBLISHED");
        assertThat(store.byId(newer.batchId()).orElseThrow().status()).isEqualTo("DRAFT");
        assertThat(jdbc.queryForObject("SELECT title FROM guide WHERE slug = ?", String.class, published.slug())).isEqualTo(originalTitle);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM guide_locale_current WHERE slug = ? AND batch_id = ?",
                Integer.class, published.slug(), published.batchId())).isEqualTo(5);
        assertThat(get("/api/guides/" + published.slug()).statusCode()).isEqualTo(200);
    }

    @Test
    void manualImportRejectsStaleSourceAndPrevalidatesAllLocalesBeforeSaving() throws Exception {
        var source = source("Stale translation source");
        var batch = batches.create(createRequest("manual-translation-stale", source));
        var stale = manualPackage(batch, source);
        stale.put("sourceRevisionId", UUID.randomUUID().toString());
        assertThatThrownBy(() -> batches.importTranslations(batch.batchId(), stale))
                .isInstanceOf(GuideContentBatchStore.StaleLocaleRevision.class);

        var malformedLaterLocale = manualPackage(batch, source);
        malformedLaterLocale.get("translations").get(3).get("guide").asObject().remove("title");
        assertThatThrownBy(() -> batches.importTranslations(batch.batchId(), malformedLaterLocale))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(store.locales(batch.batchId())).hasSize(1);

        var newerSource = source.deepCopy();
        newerSource.put("title", "Newer Chinese source revision");
        var newerRequest = createRequest("manual-translation-newer", newerSource);
        newerRequest.put("basedOnRevisionId", batch.sourceRevisionId().toString());
        newerRequest.put("sourceVersion", "source-v2");
        var newer = batches.create(newerRequest);
        assertThatThrownBy(() -> batches.importTranslations(batch.batchId(), manualPackage(batch, source)))
                .isInstanceOf(GuideContentBatchStore.StaleLocaleRevision.class);
        assertThat(store.locales(newer.batchId())).hasSize(1);
    }

    private ObjectNode manualPackage(GuideContentBatchService.BatchReceipt batch, ObjectNode source) {
        var result = json.createObjectNode();
        result.put("contractVersion", "translation-package-v1");
        result.put("batchId", batch.batchId().toString());
        result.put("sourceRevisionId", batch.sourceRevisionId().toString());
        result.put("sourceContentHash", batch.sourceContentHash());
        var translations = result.putArray("translations");
        for (String locale : List.of("zh-TW", "en", "ur", "hi")) {
            var item = translations.addObject(); item.put("locale", locale);
            item.put("sourceRevisionId", batch.sourceRevisionId().toString());
            item.put("sourceContentHash", batch.sourceContentHash());
            var translated = source.deepCopy(); translated.put("title", "Manual " + locale);
            item.set("guide", translated);
        }
        return result;
    }

    @Test
    void reviewIsBoundToTheExactCurrentHash() throws Exception {
        var source = source("Review hash");
        var batch = batches.create(createRequest("batch-hash", source));
        batches.translate(batch.batchId(), "en", json.writeValueAsBytes(translation(source, "First")));
        String originalHash = store.locale(batch.batchId(), "en").orElseThrow().contentHash();
        batches.translate(batch.batchId(), "en", json.writeValueAsBytes(translation(source, "Replacement")));
        var request = json.createObjectNode();
        request.put("contentHash", originalHash); request.put("passed", true);
        request.putArray("findings");
        assertThatThrownBy(() -> batches.review(batch.batchId(), "en", request, "mock-adapter"))
                .isInstanceOf(GuideContentBatchStore.StaleLocaleRevision.class);
    }

    @Test
    void reviewIdentityComesFromBearerCredentialAndCannotBeSpoofedInPayload() throws Exception {
        var batch = batches.create(createRequest("batch-review-identity", source("Review identity")));
        String hash = store.locale(batch.batchId(), "zh-CN").orElseThrow().contentHash();
        var payload = json.createObjectNode(); payload.put("contentHash", hash); payload.put("passed", true); payload.putArray("findings");
        var client = HttpClient.newHttpClient();
        payload.put("reviewer", "claude-independent");
        var unauthenticated = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port
                        + "/api/editorial/guide-batches/" + batch.batchId() + "/reviews/zh-CN"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"contentHash\":\"" + hash + "\",\"passed\":true,\"findings\":[]}"))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertThat(unauthenticated.statusCode()).isEqualTo(401);
        var forged = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port
                        + "/api/editorial/guide-batches/" + batch.batchId() + "/reviews/zh-CN"))
                .header("Authorization", "Bearer test-mock-review-token").header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload.toString())).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(forged.statusCode()).isEqualTo(400);
        payload.remove("reviewer");
        var reviewResponse = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port
                        + "/api/editorial/guide-batches/" + batch.batchId() + "/reviews/zh-CN"))
                .header("Authorization", "Bearer test-mock-review-token").header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload.toString())).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(reviewResponse.statusCode()).isEqualTo(200);
        assertThat(reviewResponse.body()).contains("\"reviewer\":\"mock-adapter\"");
        assertThat(reviewResponse.body()).contains("\"reviewedHash\":\"" + hash + "\"");

        var independentBatch = batches.create(createRequest("batch-independent-review-identity", source("Independent review identity")));
        String independentHash = store.locale(independentBatch.batchId(), "zh-CN").orElseThrow().contentHash();
        var independentPayload = json.createObjectNode(); independentPayload.put("contentHash", independentHash);
        independentPayload.put("passed", true); independentPayload.putArray("findings");
        var independentResponse = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port
                        + "/api/editorial/guide-batches/" + independentBatch.batchId() + "/reviews/zh-CN"))
                .header("Authorization", "Bearer test-review-token").header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(independentPayload.toString())).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(independentResponse.statusCode()).isEqualTo(200);
        assertThat(independentResponse.body()).contains("\"reviewer\":\"claude-independent\"");

        var localBatch = batches.create(createRequest("batch-codex-local-review-identity", source("Codex local review identity")));
        String localHash = store.locale(localBatch.batchId(), "zh-CN").orElseThrow().contentHash();
        var localPayload = json.createObjectNode(); localPayload.put("contentHash", localHash);
        localPayload.put("passed", false); localPayload.putArray("findings").add("LOCAL_REVIEW_TEST_FAILURE");
        var localResponse = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port
                        + "/api/editorial/guide-batches/" + localBatch.batchId() + "/reviews/zh-CN"))
                .header("Authorization", "Bearer test-codex-local-review-token").header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(localPayload.toString())).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(localResponse.statusCode()).isEqualTo(200);
        assertThat(localResponse.body()).contains("\"reviewer\":\"codex-local-review\"", "\"reviewStatus\":\"FAILED\"");
    }

    @Test
    void draftCredentialCannotApproveOrPublish() throws Exception {
        var batch = batches.create(createRequest("batch-auth", source("Auth")));
        var client = HttpClient.newHttpClient();
        var response = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port
                        + "/api/editorial/guide-batches/" + batch.batchId() + "/publish"))
                .header("Authorization", "Bearer test-draft-token")
                .header("X-Editorial-Actor", "synthetic-editor")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{}", StandardCharsets.UTF_8)).build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM guide_publication", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM guide", Integer.class)).isZero();
    }

    @Test
    void ownerSessionsRotateHttpOnlyCookieEnforceCsrfAndSeparateDraftFromApprovalOverHttp() throws Exception {
        var source = source("HTTP owner session controls");
        var batch = batches.create(createRequest("owner-session-http-controls", source));
        var client = HttpClient.newHttpClient();
        String root = "http://127.0.0.1:" + port + "/api/owner/editorial";

        var draftLogin = client.send(HttpRequest.newBuilder(URI.create(root + "/session"))
                .header("Origin", "http://127.0.0.1:5179").header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"role\":\"DRAFT\",\"credential\":\"test-owner-draft-password-0123456789abcdef\"}"))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertThat(draftLogin.statusCode()).isEqualTo(200);
        String draftCookie = sessionCookie(draftLogin);
        String draftCsrf = json.readTree(draftLogin.body()).get("csrfToken").stringValue();
        assertThat(draftLogin.headers().firstValue("set-cookie").orElse("").toLowerCase())
                .contains("httponly", "samesite=strict").doesNotContain("secure");
        assertThat(draftCookie).startsWith("JSESSIONID=");

        assertThat(ownerRequest(client, root + "/batches/" + batch.batchId() + "/approve", "POST", draftCookie,
                draftCsrf, "{}").statusCode()).isEqualTo(401);
        assertThat(ownerRequest(client, root + "/batches/" + batch.batchId() + "/publish", "POST", draftCookie,
                draftCsrf, "{\"checklistId\":\"standard-v1\",\"verifiedOn\":\"2026-11-13\",\"expectCurrentPublicationId\":null}").statusCode()).isEqualTo(401);

        var approvalLogin = client.send(HttpRequest.newBuilder(URI.create(root + "/session"))
                .header("Origin", "http://127.0.0.1:5179").header("Cookie", draftCookie)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"role\":\"APPROVAL\",\"credential\":\"test-owner-approval-password-0123456789abcdef\"}"))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertThat(approvalLogin.statusCode()).isEqualTo(200);
        String approvalCookie = sessionCookie(approvalLogin);
        String approvalCsrf = json.readTree(approvalLogin.body()).get("csrfToken").stringValue();
        assertThat(approvalCookie).isNotEqualTo(draftCookie);
        assertThat(ownerRequest(client, root + "/session", "GET", draftCookie, null, null).statusCode()).isEqualTo(401);
        assertThat(ownerRequest(client, root + "/batches/" + batch.batchId() + "/approve", "POST", approvalCookie,
                null, "{}").statusCode()).isEqualTo(403);
        assertThat(ownerRequest(client, root + "/batches", "POST", approvalCookie, approvalCsrf, "{}").statusCode()).isEqualTo(401);
        assertThat(ownerRequest(client, root + "/batches/" + batch.batchId() + "/translations/import", "POST", approvalCookie,
                approvalCsrf, "{}").statusCode()).isEqualTo(401);

        var refusedApproval = ownerRequest(client, root + "/batches/" + batch.batchId() + "/approve", "POST",
                approvalCookie, approvalCsrf, "{}");
        assertThat(refusedApproval.statusCode()).isEqualTo(409);
        var refusedPublication = ownerRequest(client, root + "/batches/" + batch.batchId() + "/publish", "POST",
                approvalCookie, approvalCsrf, "{\"checklistId\":\"standard-v1\",\"verifiedOn\":\"2026-11-13\",\"expectCurrentPublicationId\":null}");
        assertThat(refusedPublication.statusCode()).isEqualTo(409);
        assertThat(store.byId(batch.batchId()).orElseThrow().status()).isEqualTo("DRAFT");

        assertThat(ownerRequest(client, root + "/session", "DELETE", approvalCookie, approvalCsrf, null).statusCode()).isEqualTo(204);
        assertThat(ownerRequest(client, root + "/session", "GET", approvalCookie, null, null).statusCode()).isEqualTo(401);
    }

    private HttpResponse<String> ownerRequest(HttpClient client, String url, String method, String cookie,
            String csrf, String body) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create(url)).header("Cookie", cookie);
        if (csrf != null) builder.header("X-CSRF-Token", csrf);
        if (body != null) builder.header("Content-Type", "application/json");
        builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String sessionCookie(HttpResponse<?> response) {
        return response.headers().firstValue("set-cookie").orElseThrow().split(";", 2)[0];
    }

    @Test
    void draftAndMockReviewCredentialsCannotReachApprovalThroughNonCanonicalRoutes() throws Exception {
        var source = source("Encoded route protection");
        var batch = batches.create(createRequest("batch-encoded-auth", source));
        var client = HttpClient.newHttpClient();
        for (String operation : java.util.List.of("approve", "publish")) {
            for (String encoded : java.util.List.of(operation + ";x", "%61" + operation.substring(1))) {
                String path = "/api/editorial/guide-batches/" + batch.batchId() + "/" + encoded;
                for (String token : java.util.List.of("test-draft-token", "test-mock-review-token")) {
                    var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                            .header("Authorization", "Bearer " + token)
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(operation.equals("publish")
                                    ? "{\"checklistId\":\"test\",\"verifiedOn\":\"2026-01-01\",\"expectCurrentPublicationId\":null}"
                                    : "{}"))
                            .build();
                    var response = client.send(request, HttpResponse.BodyHandlers.ofString());
                    assertThat(response.statusCode()).as("%s using %s at %s", operation, token, path)
                            .isIn(401, 403, 404, 405);
                }
            }
        }
        assertThat(store.byId(batch.batchId()).orElseThrow().status()).isEqualTo("DRAFT");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM guide_publication", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM guide", Integer.class)).isZero();
    }

    @Test
    void legitimateDraftAndReviewEndpointsAcceptTheirScopedCredentials() throws Exception {
        var source = source("Scoped credentials");
        var requestBody = json.writeValueAsString(createRequest("batch-scoped-http", source));
        var client = HttpClient.newHttpClient();
        var createResponse = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port
                        + "/api/editorial/guide-batches"))
                .header("Authorization", "Bearer test-draft-token").header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody)).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(createResponse.statusCode()).isEqualTo(200);
        var created = json.readTree(createResponse.body());
        UUID batchId = UUID.fromString(created.get("batchId").stringValue());
        String hash = store.locale(batchId, "zh-CN").orElseThrow().contentHash();
        var reviewBody = json.createObjectNode(); reviewBody.put("contentHash", hash); reviewBody.put("passed", true);
        reviewBody.putArray("findings");
        var reviewResponse = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port
                        + "/api/editorial/guide-batches/" + batchId + "/reviews/zh-CN"))
                .header("Authorization", "Bearer test-mock-review-token").header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(reviewBody.toString())).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(reviewResponse.statusCode()).isEqualTo(200);
        assertThat(reviewResponse.body()).contains("\"reviewer\":\"mock-adapter\"");
    }

    @Test
    void mockReviewsReachReadyButCannotBeOwnerApproved() throws Exception {
        var source = source("Mock approval guard");
        var batch = batches.create(createRequest("batch-mock-approval", source));
        fillAndReview(batch, source, "mock-adapter");
        assertThat(store.byId(batch.batchId()).orElseThrow().status()).isEqualTo("READY");
        assertThatThrownBy(() -> batches.approve(batch.batchId(), "synthetic-owner"))
                .isInstanceOf(GuideContentBatchStore.IncompleteBatch.class)
                .hasMessageContaining("mock-adapter reviews cannot be approved");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM guide_publication", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM guide", Integer.class)).isZero();
    }

    @Test
    void fiveLocalePublicationIsAtomicAndExpiredContentIsHidden() throws Exception {
        var source = source("Five locale sample");
        source.put("category", "health-nhs");
        var batch = batches.create(createRequest("batch-five", source));
        fillAndReview(batch, source, "claude-independent");
        assertThat(store.byId(batch.batchId()).orElseThrow().status()).isEqualTo("READY");
        assertThat(get("/api/guides").body()).doesNotContain(batch.slug());
        assertThat(get("/api/guides/locales/en").body()).doesNotContain(batch.slug());
        assertThat(get("/sitemap.xml").body()).doesNotContain(batch.slug());
        jdbc.update("INSERT INTO guide_workflow_activation (activated_by) VALUES ('synthetic-test')");
        batches.approve(batch.batchId(), "synthetic-owner");
        batches.publish(batch.batchId(), new GuideContentBatchService.PublishRequest(null, "standard-v1",
                LocalDate.now(ZoneOffset.UTC)), "synthetic-owner");

        assertThat(jdbc.queryForObject("SELECT count(*) FROM guide_locale_current WHERE slug = ? AND batch_id = ?",
                Integer.class, batch.slug(), batch.batchId())).isEqualTo(5);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM guide_locale_revision WHERE batch_id = ? AND source_revision_id = ?",
                Integer.class, batch.batchId(), batch.sourceRevisionId())).isEqualTo(5);
        assertThat(jdbc.queryForObject("SELECT count(DISTINCT batch_id) FROM guide_locale_current WHERE slug = ?",
                Integer.class, batch.slug())).isEqualTo(1);
        assertThat(get("/api/guides/locales/en").body()).contains(batch.slug(), "English");
        assertThat(get("/api/guides/locales/zh-CN").body()).contains(batch.slug(), "Five locale sample");
        assertThat(get("/api/guides/locales/unknown").statusCode()).isEqualTo(404);
        var sitemap = get("/sitemap.xml");
        assertThat(sitemap.statusCode()).isEqualTo(200);
        assertThat(sitemap.headers().firstValue("content-type").orElse("")).contains("application/xml");
        assertThat(sitemap.body()).contains("http://127.0.0.1:5173/en/health/" + batch.slug(),
                "hreflang=\"zh-TW\"", "hreflang=\"en-GB\"", "hreflang=\"ur\"", "hreflang=\"hi\"")
                .doesNotContain("hreflang=\"en\"");
        // The section's topic hub is listed once per published locale.
        assertThat(sitemap.body()).contains("<loc>http://127.0.0.1:5173/en/health</loc>", "<loc>http://127.0.0.1:5173/zh-CN/health</loc>",
                "hreflang=\"x-default\" href=\"http://127.0.0.1:5173/zh-CN/health\"");
        // Home pages: one per locale with the full alternate set; the root is never listed (it redirects to /zh-CN).
        assertThat(sitemap.body()).contains("<loc>http://127.0.0.1:5173/zh-CN</loc>", "<loc>http://127.0.0.1:5173/ur</loc>",
                "hreflang=\"x-default\" href=\"http://127.0.0.1:5173/zh-CN\"")
                .doesNotContain("<loc>http://127.0.0.1:5173/</loc>", "<loc>http://127.0.0.1:5173</loc>",
                        // Its Family & Visa Guide is not published in this schema, so no topic page is listed.
                        "/family-visa/topics/");
        assertThat(get("/robots.txt").body()).contains("Sitemap: http://127.0.0.1:5173/sitemap.xml");

        jdbc.update("UPDATE guide_locale_current SET valid_until = ? WHERE slug = ?",
                java.sql.Timestamp.from(Instant.now().minusSeconds(1)), batch.slug());
        assertThat(store.current(batch.slug(), "en")).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM guide_locale_current WHERE slug = ? AND valid_until <= CURRENT_TIMESTAMP",
                Integer.class, batch.slug())).isEqualTo(5);
        assertThat(get("/api/guides").body()).doesNotContain(batch.slug());
        assertThat(get("/api/guides/" + batch.slug()).statusCode()).isEqualTo(404);
        assertThat(get("/api/guides/" + batch.slug() + "/locales/en").statusCode()).isEqualTo(404);
        var report = getWithDraftCredential("/api/editorial/guide-batches/expiry-report");
        assertThat(report.statusCode()).isEqualTo(200);
        assertThat(report.body()).contains(batch.slug(), "EXPIRED_CURRENT_LOCALE");
    }

    @ParameterizedTest
    @ValueSource(strings = {"university-applications-student-visas", "education"})
    void categorySitemapAndPublicLocalesFollowPublicationAndWithdrawal(String category) throws Exception {
        var source = source("University lifecycle fixture");
        source.put("category", category);
        var batch = batches.create(createRequest(category + "-sitemap-lifecycle", source));
        fillAndReview(batch, source, "claude-independent");
        var baseline = get("/sitemap.xml").body();
        assertThat(baseline).doesNotContain(category);
        jdbc.update("INSERT INTO guide_workflow_activation (activated_by) VALUES ('synthetic-test')");
        batches.approve(batch.batchId(), "synthetic-owner");
        assertThat(get("/sitemap.xml").body()).isEqualTo(baseline);
        batches.publish(batch.batchId(), new GuideContentBatchService.PublishRequest(null, "standard-v1",
                LocalDate.now(ZoneOffset.UTC)), "synthetic-owner");

        var xml = get("/sitemap.xml").body();
        var urls = java.util.regex.Pattern.compile("<loc>([^<]+)</loc>").matcher(xml).results()
                .map(row -> row.group(1)).toList();
        assertThat(urls).hasSize(15).doesNotHaveDuplicates();
        for (String locale : GuideContentBatchService.LOCALES) {
            var hub = "http://127.0.0.1:5173/" + locale + "/" + category;
            assertThat(urls).contains(hub, hub + "/" + batch.slug());
            assertThat(get("/api/guides/" + batch.slug() + "/locales/" + locale).statusCode()).isEqualTo(200);
        }
        assertThat(xml).contains("hreflang=\"en-GB\" href=\"http://127.0.0.1:5173/en/" + category)
                .doesNotContain("hreflang=\"en\"");
        var publication = jdbc.queryForObject("SELECT id FROM guide_publication WHERE slug = ? AND ended_at IS NULL",
                UUID.class, batch.slug());
        assertThatThrownBy(() -> publications.withdraw(batch.slug(), UUID.randomUUID(), "stale rollback", "synthetic-owner"))
                .isInstanceOf(GuidePublicationService.Refused.class);
        assertThat(get("/sitemap.xml").body()).isEqualTo(xml);
        publications.withdraw(batch.slug(), publication, "synthetic rollback", "synthetic-owner");
        assertThat(get("/sitemap.xml").body()).isEqualTo(baseline);
        for (String locale : GuideContentBatchService.LOCALES) {
            assertThat(get("/api/guides/" + batch.slug() + "/locales/" + locale).statusCode()).isEqualTo(404);
        }
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "EDUCATION_WAVE1_ARTIFACTS", matches = ".+")
    void privateEducationWaveOneDraftsImportWithoutPublicEffect() throws Exception {
        var root = Path.of(System.getenv("EDUCATION_WAVE1_ARTIFACTS"));
        var records = json.readTree(Files.readAllBytes(root.resolve("MANIFEST.json"))).get("records");
        assertThat(records.size()).isEqualTo(25);
        var slugs = List.of("joining-school-after-moving-to-uk", "uk-school-stages-and-year-groups",
                "in-year-school-admissions-england", "joining-school-during-gcses-england", "sixth-form-or-fe-college-england");
        var baselineSitemap = get("/sitemap.xml").body();
        var baselineListing = get("/api/guides").body();
        for (String slug : slugs) {
            var definitions = new java.util.HashMap<String, ObjectNode>();
            var hashes = new java.util.HashMap<String, String>();
            for (var item : records) {
                if (!item.get("slug").asString().equals(slug)) continue;
                var locale = item.get("locale").asString();
                var bytes = Files.readAllBytes(root.resolve("drafts").resolve(slug).resolve(locale + ".json"));
                assertThat(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)))
                        .isEqualTo(item.get("sha256").asString());
                var definition = reader.parse(bytes);
                assertThat(definition.slug()).isEqualTo(slug);
                assertThat(definition.category()).isEqualTo("education");
                assertThat(definition.status()).isEqualTo(GuideStatus.DRAFT);
                assertThat(definition.evidence()).isNotEmpty();
                assertThat(definition.sources()).allSatisfy(source -> assertThat(source.url()).startsWith("https://"));
                var hash = GuideContentDigest.digest(definition);
                assertThat(hash).isEqualTo(item.get("contentHash").asString());
                assertThat(hashes.put(locale, hash)).isNull();
                definitions.put(locale, (ObjectNode) json.readTree(bytes));
            }
            assertThat(hashes.keySet()).containsExactlyInAnyOrderElementsOf(GuideContentBatchService.LOCALES);
            var batch = batches.create(createRequest("education-wave1-" + slug, definitions.get("zh-CN")));
            assertThat(batch.sourceContentHash()).isEqualTo(hashes.get("zh-CN"));
            assertThat(qualityGate.check(batch.sourceRevisionId(), Instant.now()).findings()).isEmpty();
            var template = batches.translationPackage(batch.batchId()).responseTemplate();
            for (var entry : template.get("translations")) {
                ((ObjectNode) entry).set("guide", definitions.get(entry.get("locale").asString()));
            }
            var receipts = batches.importTranslations(batch.batchId(), template);
            assertThat(receipts).hasSize(4).allSatisfy(receipt -> {
                assertThat(receipt.contentHash()).isEqualTo(hashes.get(receipt.locale()));
                assertThat(receipt.sourceRevisionId()).isEqualTo(batch.sourceRevisionId());
                assertThat(receipt.validationStatus()).isEqualTo("VALID");
                assertThat(receipt.reviewedHash()).isNull();
            });
            for (String locale : GuideContentBatchService.LOCALES) {
                assertThat(get("/api/guides/" + slug + "/locales/" + locale).statusCode()).isEqualTo(404);
            }
            assertThat(store.byId(batch.batchId()).orElseThrow().status()).isEqualTo("DRAFT");
        }
        assertThat(get("/sitemap.xml").body()).isEqualTo(baselineSitemap);
        assertThat(get("/api/guides").body()).isEqualTo(baselineListing);
    }

    @Test
    void independentReviewOwnerHttpApprovalAndPublicationCompleteFiveLocaleLifecycle() throws Exception {
        var source = source("Owner HTTP lifecycle sample");
        source.put("category", "health-nhs");
        var batch = batches.create(createRequest("owner-http-lifecycle-first", source));
        var localeTitles = new java.util.LinkedHashMap<String, String>();
        localeTitles.put("zh-CN", source.get("title").stringValue());
        localeTitles.put("zh-TW", "繁體中文合成測試"); localeTitles.put("en", "Synthetic English");
        localeTitles.put("ur", "اردو آزمائشی متن"); localeTitles.put("hi", "हिन्दी परीक्षण पाठ");
        var client = HttpClient.newHttpClient();
        for (var localeEntry : localeTitles.entrySet()) {
            String locale = localeEntry.getKey();
            if (!locale.equals("zh-CN")) {
                batches.translate(batch.batchId(), locale, json.writeValueAsBytes(translation(source, localeEntry.getValue())));
            }
            String hash = store.locale(batch.batchId(), locale).orElseThrow().contentHash();
            var review = json.createObjectNode(); review.put("contentHash", hash); review.put("passed", true); review.putArray("findings");
            var response = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port
                            + "/api/editorial/guide-batches/" + batch.batchId() + "/reviews/" + locale))
                    .header("Authorization", "Bearer test-review-token").header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(review.toString())).build(), HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).as("independent review of %s", locale).isEqualTo(200);
            assertThat(response.body()).contains("\"reviewer\":\"claude-independent\"", "\"reviewedHash\":\"" + hash + "\"");
        }
        assertThat(store.byId(batch.batchId()).orElseThrow().status()).isEqualTo("READY");
        jdbc.update("INSERT INTO guide_workflow_activation (activated_by) VALUES ('synthetic-test')");

        var login = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/owner/editorial/session"))
                .header("Origin", "http://127.0.0.1:5179").header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"role\":\"APPROVAL\",\"credential\":\"test-owner-approval-password-0123456789abcdef\"}"))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertThat(login.statusCode()).isEqualTo(200);
        String cookie = sessionCookie(login);
        String csrf = json.readTree(login.body()).get("csrfToken").stringValue();
        String ownerRoot = "http://127.0.0.1:" + port + "/api/owner/editorial";
        var approve = ownerRequest(client, ownerRoot + "/batches/" + batch.batchId() + "/approve", "POST", cookie, csrf, "{}");
        assertThat(approve.statusCode()).isEqualTo(200);
        assertThat(approve.body()).contains("\"status\":\"APPROVED\"");
        var publishBody = "{\"checklistId\":\"standard-v1\",\"verifiedOn\":\"" + LocalDate.now(ZoneOffset.UTC)
                + "\",\"expectCurrentPublicationId\":null}";
        var publish = ownerRequest(client, ownerRoot + "/batches/" + batch.batchId() + "/publish", "POST", cookie, csrf, publishBody);
        assertThat(publish.statusCode()).isEqualTo(200);
        assertThat(publish.body()).contains("\"status\":\"PUBLISHED\"");

        for (String locale : GuideContentBatchService.LOCALES) {
            var listing = get("/api/guides/locales/" + locale);
            assertThat(listing.statusCode()).as("published %s list", locale).isEqualTo(200);
            assertThat(listing.body()).contains(batch.slug());
            var detail = get("/api/guides/" + batch.slug() + "/locales/" + locale);
            assertThat(detail.statusCode()).as("published %s detail", locale).isEqualTo(200);
            assertThat(detail.body()).contains("\"locale\":\"" + locale + "\"", "\"contentHash\":");
        }

        var unpublishedSource = source("Still unpublished sample");
        var unpublished = batches.create(createRequest("owner-http-lifecycle-unpublished", unpublishedSource));
        assertThat(get("/api/guides/" + unpublished.slug()).statusCode()).isEqualTo(404);
        assertThat(get("/api/guides/" + unpublished.slug() + "/locales/en").statusCode()).isEqualTo(404);

        var newerSource = sourceWithSlug("Newer draft must not hide old publication", batch.slug());
        newerSource.put("category", "health-nhs");
        var newerRequest = createRequest("owner-http-lifecycle-newer", newerSource);
        newerRequest.put("basedOnRevisionId", batch.sourceRevisionId().toString());
        newerRequest.put("sourceVersion", "source-v2");
        var newer = batches.create(newerRequest);
        assertThat(ownerRequest(client, ownerRoot + "/batches/" + batch.batchId() + "/approve", "POST", cookie, csrf, "{}").statusCode())
                .isEqualTo(409);
        assertThat(ownerRequest(client, ownerRoot + "/batches/" + batch.batchId() + "/publish", "POST", cookie, csrf, publishBody).statusCode())
                .isEqualTo(409);
        assertThat(store.byId(batch.batchId()).orElseThrow().status()).isEqualTo("PUBLISHED");
        assertThat(store.byId(newer.batchId()).orElseThrow().status()).isEqualTo("DRAFT");
        assertThat(get("/api/guides/" + batch.slug()).statusCode()).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM guide_locale_current WHERE slug = ? AND batch_id = ?",
                Integer.class, batch.slug(), batch.batchId())).isEqualTo(5);
    }

    @Test
    void failedReplacementPublicationLeavesPreviousProjectionAndLocalePointers() throws Exception {
        var firstSource = source("Initial published batch");
        var first = batches.create(createRequest("batch-replace-first", firstSource));
        fillAndReview(first, firstSource, "claude-independent");
        jdbc.update("INSERT INTO guide_workflow_activation (activated_by) VALUES ('synthetic-test')");
        batches.approve(first.batchId(), "synthetic-owner");
        batches.publish(first.batchId(), new GuideContentBatchService.PublishRequest(null, "standard-v1",
                LocalDate.now(ZoneOffset.UTC)), "synthetic-owner");
        var oldTitle = jdbc.queryForObject("SELECT title FROM guide WHERE slug = ?", String.class, first.slug());

        var replacementSource = sourceWithSlug("Replacement draft", first.slug());
        var replacement = batches.create(createRequest("batch-replace-next", replacementSource));
        fillAndReview(replacement, replacementSource, "claude-independent");
        batches.approve(replacement.batchId(), "synthetic-owner");
        UUID currentPublication = jdbc.queryForObject("SELECT id FROM guide_publication WHERE slug = ? AND ended_at IS NULL",
                UUID.class, first.slug());
        jdbc.execute("CREATE FUNCTION fail_batch_publication_insert() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'synthetic publication failure'; END; $$");
        jdbc.execute("CREATE TRIGGER fail_batch_publication_insert BEFORE INSERT ON guide_publication FOR EACH ROW EXECUTE FUNCTION fail_batch_publication_insert()");
        try {
            assertThatThrownBy(() -> batches.publish(replacement.batchId(), new GuideContentBatchService.PublishRequest(
                    currentPublication, "standard-v1", LocalDate.now(ZoneOffset.UTC)), "synthetic-owner"))
                    .hasMessageContaining("synthetic publication failure");
        } finally {
            jdbc.execute("DROP TRIGGER IF EXISTS fail_batch_publication_insert ON guide_publication");
            jdbc.execute("DROP FUNCTION IF EXISTS fail_batch_publication_insert()");
        }
        assertThat(jdbc.queryForObject("SELECT title FROM guide WHERE slug = ?", String.class, first.slug())).isEqualTo(oldTitle);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM guide_locale_current WHERE slug = ? AND batch_id = ?",
                Integer.class, first.slug(), first.batchId())).isEqualTo(5);
        assertThat(store.byId(first.batchId()).orElseThrow().status()).isEqualTo("PUBLISHED");
        assertThat(store.byId(replacement.batchId()).orElseThrow().status()).isEqualTo("APPROVED");
    }

    @Test
    void successfulReplacementRetainsSupersededLocaleBodies() throws Exception {
        var firstSource = source("Retained historical locale body");
        firstSource.put("category", "health-nhs");
        var first = batches.create(createRequest("batch-retain-first", firstSource));
        fillAndReview(first, firstSource, "claude-independent");
        jdbc.update("INSERT INTO guide_workflow_activation (activated_by) VALUES ('synthetic-test')");
        batches.approve(first.batchId(), "synthetic-owner");
        batches.publish(first.batchId(), new GuideContentBatchService.PublishRequest(null, "standard-v1",
                LocalDate.now(ZoneOffset.UTC)), "synthetic-owner");
        UUID previousPublication = jdbc.queryForObject("SELECT id FROM guide_publication WHERE slug = ? AND ended_at IS NULL",
                UUID.class, first.slug());
        var previousBodies = jdbc.query("SELECT locale, canonical_form FROM guide_locale_revision WHERE batch_id = ? ORDER BY locale",
                (row, index) -> row.getString("locale") + ":" + row.getString("canonical_form"), first.batchId());

        var secondSource = sourceWithSlug("Replacement batch body", first.slug());
        secondSource.put("category", "health-nhs");
        var second = batches.create(createRequest("batch-retain-second", secondSource));
        fillAndReview(second, secondSource, "claude-independent");
        batches.approve(second.batchId(), "synthetic-owner");
        batches.publish(second.batchId(), new GuideContentBatchService.PublishRequest(previousPublication, "standard-v1",
                LocalDate.now(ZoneOffset.UTC)), "synthetic-owner");

        var after = jdbc.query("SELECT locale, canonical_form FROM guide_locale_revision WHERE batch_id = ? ORDER BY locale",
                (row, index) -> row.getString("locale") + ":" + row.getString("canonical_form"), first.batchId());
        assertThat(after).containsExactlyElementsOf(previousBodies);
        assertThat(after).allMatch(body -> !body.endsWith(":null"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM guide_locale_current WHERE slug = ? AND batch_id = ?",
                Integer.class, first.slug(), second.batchId())).isEqualTo(5);
    }
}
