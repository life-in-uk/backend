package info.lifeinuk.backend.guides;

import info.lifeinuk.backend.support.IsolatedPostgres;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
    "spring.flyway.schemas=guide_content_batch_tests", "spring.flyway.default-schema=guide_content_batch_tests",
    "spring.jpa.properties.hibernate.default_schema=guide_content_batch_tests", "spring.datasource.hikari.schema=guide_content_batch_tests",
    "life-in-uk.editorial.draft-token=test-draft-token", "life-in-uk.editorial.review-token=test-review-token",
    "life-in-uk.editorial.approval-token=test-approval-token"
})
@ContextConfiguration(initializers = IsolatedPostgres.Initializer.class)
class GuideContentBatchTest {
    @Autowired GuideContentBatchService batches;
    @Autowired GuideContentBatchStore store;
    @Autowired GuideRevisionService revisions;
    @Autowired GuideImportReader reader;
    @Autowired JdbcTemplate jdbc;
    @LocalServerPort int port;
    private final JsonMapper json = JsonMapper.builder().build();

    private HttpResponse<String> get(String path) throws Exception {
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    @BeforeEach
    void clear() {
        jdbc.execute("TRUNCATE guide_locale_current, guide_locale_revision, guide_content_batch, guide_publication, guide_workflow_activation, guide_revision, guide_evidence_support, guide_evidence, guide_source, guide");
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

    private void fillAndReview(GuideContentBatchService.BatchReceipt batch, ObjectNode source) throws Exception {
        var locales = new java.util.LinkedHashMap<String, String>();
        locales.put("zh-CN", source.get("title").stringValue());
        locales.put("zh-TW", "Traditional Chinese"); locales.put("en", "English");
        locales.put("ur", "Urdu"); locales.put("hi", "Hindi");
        for (var entry : locales.entrySet()) {
            if (!entry.getKey().equals("zh-CN")) {
                batches.translate(batch.batchId(), entry.getKey(), json.writeValueAsBytes(translation(source, entry.getValue())));
            }
            String hash = store.locale(batch.batchId(), entry.getKey()).orElseThrow().contentHash();
            var review = json.createObjectNode(); review.put("contentHash", hash); review.put("passed", true); review.putArray("findings");
            batches.review(batch.batchId(), entry.getKey(), review);
        }
        assertThat(store.byId(batch.batchId()).orElseThrow().status()).isEqualTo("READY");
        batches.approve(batch.batchId(), "synthetic-owner");
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
    void reviewIsBoundToTheExactCurrentHash() throws Exception {
        var source = source("Review hash");
        var batch = batches.create(createRequest("batch-hash", source));
        batches.translate(batch.batchId(), "en", json.writeValueAsBytes(translation(source, "First")));
        String originalHash = store.locale(batch.batchId(), "en").orElseThrow().contentHash();
        batches.translate(batch.batchId(), "en", json.writeValueAsBytes(translation(source, "Replacement")));
        var request = json.createObjectNode();
        request.put("contentHash", originalHash); request.put("passed", true); request.putArray("findings");
        assertThatThrownBy(() -> batches.review(batch.batchId(), "en", request))
                .isInstanceOf(GuideContentBatchStore.StaleLocaleRevision.class);
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
    void fiveLocalePublicationIsAtomicAndExpiredContentIsHidden() throws Exception {
        var source = source("Five locale sample");
        var batch = batches.create(createRequest("batch-five", source));
        fillAndReview(batch, source);
        jdbc.update("INSERT INTO guide_workflow_activation (activated_by) VALUES ('synthetic-test')");
        batches.publish(batch.batchId(), new GuideContentBatchService.PublishRequest(null, "standard-v1",
                LocalDate.now(ZoneOffset.UTC)), "synthetic-owner");

        assertThat(jdbc.queryForObject("SELECT count(*) FROM guide_locale_current WHERE slug = ? AND batch_id = ?",
                Integer.class, batch.slug(), batch.batchId())).isEqualTo(5);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM guide_locale_revision WHERE batch_id = ? AND source_revision_id = ?",
                Integer.class, batch.batchId(), batch.sourceRevisionId())).isEqualTo(5);
        assertThat(jdbc.queryForObject("SELECT count(DISTINCT batch_id) FROM guide_locale_current WHERE slug = ?",
                Integer.class, batch.slug())).isEqualTo(1);

        jdbc.update("UPDATE guide_locale_current SET valid_until = ? WHERE slug = ?",
                java.sql.Timestamp.from(Instant.now().minusSeconds(1)), batch.slug());
        assertThat(store.current(batch.slug(), "en")).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM guide_locale_current WHERE slug = ? AND valid_until <= CURRENT_TIMESTAMP",
                Integer.class, batch.slug())).isEqualTo(5);
        assertThat(get("/api/guides").body()).doesNotContain(batch.slug());
        assertThat(get("/api/guides/" + batch.slug()).statusCode()).isEqualTo(404);
        assertThat(get("/api/guides/" + batch.slug() + "/locales/en").statusCode()).isEqualTo(404);
    }

    @Test
    void failedReplacementPublicationLeavesPreviousProjectionAndLocalePointers() throws Exception {
        var firstSource = source("Initial published batch");
        var first = batches.create(createRequest("batch-replace-first", firstSource));
        fillAndReview(first, firstSource);
        jdbc.update("INSERT INTO guide_workflow_activation (activated_by) VALUES ('synthetic-test')");
        batches.publish(first.batchId(), new GuideContentBatchService.PublishRequest(null, "standard-v1",
                LocalDate.now(ZoneOffset.UTC)), "synthetic-owner");
        var oldTitle = jdbc.queryForObject("SELECT title FROM guide WHERE slug = ?", String.class, first.slug());

        var replacementSource = sourceWithSlug("Replacement draft", first.slug());
        var replacement = batches.create(createRequest("batch-replace-next", replacementSource));
        fillAndReview(replacement, replacementSource);
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
}
