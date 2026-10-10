package info.lifeinuk.backend.guides;

import info.lifeinuk.backend.support.IsolatedPostgres;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ContextConfiguration;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import static info.lifeinuk.backend.guides.GuidePublicationService.Outcome.*;
import static org.assertj.core.api.Assertions.*;

/** Transactional publication and withdrawal on an isolated schema with synthetic fixtures only. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "spring.flyway.schemas=guide_publication_tests", "spring.flyway.default-schema=guide_publication_tests",
    "spring.jpa.properties.hibernate.default_schema=guide_publication_tests", "spring.datasource.hikari.schema=guide_publication_tests",
    "LIFE_IN_UK_OPERATOR=synthetic-operator",
    // Similarly named properties never invoke an operation on ordinary startup.
    "guide-publish=00000000-0000-0000-0000-000000000000", "guide-withdraw=synthetic-evidence-guide"
})
@ContextConfiguration(initializers = IsolatedPostgres.Initializer.class)
class GuidePublicationTest {
    static final String OPERATOR = "synthetic-operator";
    @Autowired GuidePublicationService publications;
    @Autowired GuideRevisionService revisions;
    @Autowired GuideWorkflowCommand command;
    @Autowired GuideImporter importer;
    @Autowired GuideImportReader reader;
    @Autowired JdbcTemplate jdbc;
    @LocalServerPort int port;
    private final JsonMapper json = JsonMapper.builder().build();
    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void clear() {
        jdbc.execute("DROP TRIGGER IF EXISTS guide_publication_fail ON guide_publication");
        jdbc.execute("DROP FUNCTION IF EXISTS guide_publication_fail()");
        jdbc.execute("TRUNCATE guide_publication, guide_workflow_activation, guide_revision, guide_evidence_support, guide_evidence, guide_source, guide");
    }
    void activate() { jdbc.update("INSERT INTO guide_workflow_activation (activated_by) VALUES ('synthetic-test')"); }

    GuideImportDefinition draft(Consumer<ObjectNode> edit) throws Exception {
        var root = (ObjectNode) json.readTree(GuideContentDigestTest.fixture("synthetic-evidence-import.json"));
        edit.accept(root);
        return reader.parse(json.writeValueAsString(root).getBytes(StandardCharsets.UTF_8));
    }
    GuideRevision revision(Consumer<ObjectNode> edit) throws Exception {
        return revisions.create(draft(edit), null, OPERATOR, false, null).revision();
    }
    GuideRevision revision() throws Exception { return revision(root -> { }); }
    static LocalDate today() { return LocalDate.now(ZoneOffset.UTC); }
    static GuidePublicationService.PublishRequest request(GuideRevision revision, UUID expectCurrent) {
        return new GuidePublicationService.PublishRequest(revision.id(), revision.draftDigest(), expectCurrent,
                "standard-v1", today(), true, OPERATOR);
    }
    GuidePublicationStore.Publication publish(GuideRevision revision, UUID expectCurrent) {
        var result = publications.publish(request(revision, expectCurrent));
        assertThat(result.outcome()).isEqualTo(PUBLISHED);
        return result.publication();
    }
    String state() {
        return jdbc.queryForObject("""
            SELECT json_build_object('g',(SELECT json_agg(row_to_json(g) ORDER BY id) FROM guide g),
              's',(SELECT json_agg(row_to_json(s) ORDER BY id) FROM guide_source s),
              'e',(SELECT json_agg(row_to_json(e) ORDER BY id) FROM guide_evidence e),
              'p',(SELECT json_agg(row_to_json(p) ORDER BY id) FROM guide_evidence_support p),
              'h',(SELECT json_agg(row_to_json(h) ORDER BY id) FROM guide_publication h))::text
            """, String.class);
    }
    int rows(String table) { return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class); }
    HttpResponse<String> get(String suffix) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/guides" + suffix))
                .timeout(java.time.Duration.ofSeconds(10)).GET().build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }
    static void refused(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, String code) {
        assertThatThrownBy(call).isInstanceOf(GuidePublicationService.Refused.class).hasMessageStartingWith(code + ":");
    }

    @Test
    void publishAndWithdrawRefuseUntilTheWorkflowIsActivated() throws Exception {
        var revision = revision();
        String before = state();
        refused(() -> publications.publish(request(revision, null)), "NOT_ACTIVATED");
        refused(() -> publications.withdraw(revision.slug(), UUID.randomUUID(), "reason", OPERATOR), "NOT_ACTIVATED");
        assertThat(state()).isEqualTo(before);
        // The database refuses too, independently of the service.
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO guide_publication (id, slug, revision_id, published_at, published_digest, published_by, checklist_id, verified_on)
                VALUES (?, ?, ?, now(), ?, 'x', 'standard-v1', current_date)""", UUID.randomUUID(), revision.slug(), revision.id(), "a".repeat(64)))
                .hasMessageContaining("requires workflow activation");
        assertThat(rows("guide_workflow_activation")).isZero();
    }

    @Test
    void publicationWritesTheDerivedProjectionAndBindsThePublishedDigest() throws Exception {
        activate();
        var revision = revision();
        var publication = publish(revision, null);
        var draft = revisions.definition(revision.canonicalForm());
        var derived = new GuideImportDefinition(draft.slug(), draft.category(), draft.title(), draft.summary(), draft.content(),
                GuideStatus.PUBLISHED, publication.publishedAt(), publication.publishedAt(), draft.sources(), draft.evidence());
        assertThat(publication.publishedDigest()).isEqualTo(GuideContentDigest.digest(derived)).isNotEqualTo(revision.draftDigest());
        assertThat(publication.publishedAt().getNano()).isZero();
        assertThat(publication).extracting("revisionId", "publishedBy", "checklistId", "verifiedOn", "legacy", "endedAt")
                .containsExactly(revision.id(), OPERATOR, "standard-v1", today(), false, null);
        assertThat(publication.dbSessionUser()).isEqualTo("life_in_uk_test");
        // The projection is exactly D_p.
        assertThat(jdbc.queryForObject("SELECT status FROM guide WHERE slug = ?", String.class, revision.slug())).isEqualTo("PUBLISHED");
        assertThat(jdbc.queryForObject("SELECT published_at = updated_at AND updated_at = ? FROM guide WHERE slug = ?", Boolean.class,
                java.sql.Timestamp.from(publication.publishedAt()), revision.slug())).isTrue();
        assertThat(jdbc.queryForObject("SELECT content FROM guide", String.class)).isEqualTo(draft.content());
        assertThat(rows("guide_source")).isEqualTo(2);
        assertThat(rows("guide_evidence_support")).isEqualTo(3);
        var detail = get("/" + revision.slug());
        assertThat(detail.statusCode()).isEqualTo(200);
        assertThat(detail.body()).contains("\"slug\":\"synthetic-evidence-guide\"", "example-application");
    }

    @Test
    void repeatingTheSamePublicationIsIdempotent() throws Exception {
        activate();
        var revision = revision();
        var first = publish(revision, null);
        String before = state();
        var again = publications.publish(request(revision, null));
        assertThat(again.outcome()).isEqualTo(ALREADY_PUBLISHED);
        assertThat(again.publication()).isEqualTo(first);
        assertThat(state()).isEqualTo(before);
    }

    @Test
    void replacementEndsThePreviousPublicationAtomicallyAndRevisionsPublishOnce() throws Exception {
        activate();
        var first = revision();
        var firstPublication = publish(first, null);
        var second = revision(root -> root.put("title", "Synthetic evidence guide, revised"));
        var secondPublication = publish(second, firstPublication.id());
        var history = new GuidePublicationStore(jdbc).history(first.slug());
        assertThat(history).hasSize(2);
        var ended = history.stream().filter(p -> p.id().equals(firstPublication.id())).findFirst().orElseThrow();
        assertThat(ended).extracting("endReason", "endedBy", "endedAt", "endNote")
                .containsExactly("REPLACED", OPERATOR, secondPublication.publishedAt(), null);
        assertThat(secondPublication.open()).isTrue();
        assertThat(jdbc.queryForObject("SELECT title FROM guide", String.class)).isEqualTo("Synthetic evidence guide, revised");
        assertThat(rows("guide")).isEqualTo(1);
        // A replaced revision is never published again (no rollback in the MVP).
        refused(() -> publications.publish(request(first, secondPublication.id())), "ALREADY_USED");
    }

    @Test
    void staleExpectationsAndUnmanagedPublicContentAreRefusedWithoutChanges() throws Exception {
        activate();
        var revision = revision();
        var other = revision(root -> root.put("title", "Other"));
        var publication = publish(revision, null);
        String before = state();
        refused(() -> publications.publish(request(other, null)), "STALE_CURRENT");
        refused(() -> publications.publish(request(other, UUID.randomUUID())), "STALE_CURRENT");
        refused(() -> publications.publish(new GuidePublicationService.PublishRequest(other.id(), revision.draftDigest(),
                publication.id(), "standard-v1", today(), true, OPERATOR)), "STALE_DIGEST");
        refused(() -> publications.withdraw(revision.slug(), UUID.randomUUID(), "reason", OPERATOR), "STALE_CURRENT");
        refused(() -> publications.withdraw("never-published", UUID.randomUUID(), "reason", OPERATOR), "NOT_PUBLIC");
        assertThat(state()).isEqualTo(before);

        // Public content without publication history (not yet baselined) is never overwritten.
        var legacy = reader.parse(GuideImportReaderTest.fixture().replace("\"status\": \"DRAFT\"", "\"status\": \"PUBLISHED\"")
                .replace("\"publishedAt\": null", "\"publishedAt\": \"2026-10-04T12:00:00Z\"").getBytes(StandardCharsets.UTF_8));
        importer.apply(legacy);
        var onLegacySlug = revision(root -> root.put("slug", legacy.slug()));
        String withLegacy = state();
        refused(() -> publications.publish(request(onLegacySlug, null)), "UNMANAGED_PUBLIC_GUIDE");
        assertThat(state()).isEqualTo(withLegacy);
    }

    @Test
    void operatorConfirmationChecklistAndVerifiedDateAreRequired() throws Exception {
        activate();
        var revision = revision();
        assertThatIllegalArgumentException().isThrownBy(() -> publications.publish(new GuidePublicationService.PublishRequest(
                revision.id(), revision.draftDigest(), null, "standard-v1", today(), false, OPERATOR))).withMessageContaining("--confirm");
        assertThatIllegalArgumentException().isThrownBy(() -> publications.publish(new GuidePublicationService.PublishRequest(
                revision.id(), revision.draftDigest(), null, "family-visa-v1", today(), true, OPERATOR))).withMessageContaining("requires checklist standard-v1");
        assertThatIllegalArgumentException().isThrownBy(() -> publications.publish(new GuidePublicationService.PublishRequest(
                revision.id(), revision.draftDigest(), null, "standard-v1", today().plusDays(2), true, OPERATOR))).withMessageContaining("future");
        assertThatIllegalArgumentException().isThrownBy(() -> publications.publish(new GuidePublicationService.PublishRequest(
                revision.id(), revision.draftDigest(), null, "standard-v1", today(), true, " "))).withMessageContaining("LIFE_IN_UK_OPERATOR");
        assertThat(rows("guide_publication")).isZero();
        assertThat(rows("guide")).isZero();

        var family = revision(root -> { root.put("slug", "synthetic-family-guide"); root.put("category", "family-visa"); });
        assertThatIllegalArgumentException().isThrownBy(() -> publications.publish(request(family, null)))
                .withMessageContaining("requires checklist family-visa-v1");
        var published = publications.publish(new GuidePublicationService.PublishRequest(family.id(), family.draftDigest(), null,
                "family-visa-v1", today(), true, OPERATOR));
        assertThat(published.publication().checklistId()).isEqualTo("family-visa-v1");
    }

    @Test
    void blockingQualityGateAndNonCanonicalStoredTextPreventPublication() throws Exception {
        activate();
        var marked = revision(root -> root.put("content", root.get("content").stringValue() + "\nTODO\n"));
        refused(() -> publications.publish(request(marked, null)), "QUALITY_GATE");
        // A row inserted directly with self-consistent but non-canonical text passes the database's structural
        // checks; the Java re-validation inside the publication transaction refuses it.
        var draft = draft(root -> root.put("slug", "hand-made-guide"));
        String spaced = GuideContentDigest.canonicalForm(draft).replaceFirst("\\{", "{ ");
        String digest = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(spaced.getBytes(StandardCharsets.UTF_8)));
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO guide_revision (id, slug, revision_number, canonicalization, canonical_form, draft_digest, created_by, ai_assisted)
                VALUES (?, 'hand-made-guide', 1, 'guide-content-v1', ?, ?, 'forged', false)""", id, spaced, digest);
        var forged = revisions.find(id).orElseThrow();
        refused(() -> publications.publish(request(forged, null)), "QUALITY_GATE");
        assertThat(rows("guide_publication")).isZero();
        assertThat(rows("guide")).isZero();
    }

    @Test
    void withdrawalRemovesTheProjectionKeepsHistoryAndNeverRepublishes() throws Exception {
        activate();
        var revision = revision();
        var publication = publish(revision, null);
        assertThatIllegalArgumentException().isThrownBy(() -> publications.withdraw(revision.slug(), publication.id(), " ", OPERATOR));
        var result = publications.withdraw(revision.slug(), publication.id(), "Synthetic correction needed", OPERATOR);
        assertThat(result.outcome()).isEqualTo(WITHDRAWN);
        assertThat(result.publication()).extracting("endReason", "endedBy", "endNote")
                .containsExactly("WITHDRAWN", OPERATOR, "Synthetic correction needed");
        for (var table : java.util.List.of("guide", "guide_source", "guide_evidence", "guide_evidence_support")) {
            assertThat(rows(table)).as(table).isZero();
        }
        assertThat(rows("guide_revision")).isEqualTo(1);
        var detail = get("/" + revision.slug());
        assertThat(detail.statusCode()).isEqualTo(404);
        assertThat(detail.headers().firstValue("Cache-Control")).hasValue("no-store");
        assertThat(get("").body()).isEqualTo("[]");

        refused(() -> publications.publish(request(revision, null)), "ALREADY_USED");
        var identical = revisions.create(draft(root -> { }), null, OPERATOR, false, null);
        assertThat(identical.outcome()).isEqualTo(GuideRevisionService.Outcome.EXISTING);
        refused(() -> publications.publish(request(identical.revision(), null)), "ALREADY_USED");
        var corrected = revision(root -> root.put("title", "Corrected synthetic guide"));
        assertThat(publish(corrected, null).open()).isTrue();
        assertThat(get("/" + revision.slug()).statusCode()).isEqualTo(200);
    }

    @Test
    void publicationHistoryAndActivationCannotBeRewritten() throws Exception {
        activate();
        var revision = revision();
        var publication = publish(revision, null);
        assertThatThrownBy(() -> jdbc.update("UPDATE guide_publication SET published_digest = ? WHERE id = ?", "b".repeat(64), publication.id()))
                .hasMessageContaining("Only ending an open publication is allowed");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM guide_publication WHERE id = ?", publication.id()))
                .hasMessageContaining("cannot be deleted");
        publications.withdraw(revision.slug(), publication.id(), "Synthetic reason", OPERATOR);
        assertThatThrownBy(() -> jdbc.update("UPDATE guide_publication SET end_note = 'rewritten' WHERE id = ?", publication.id()))
                .hasMessageContaining("An ended publication cannot change");
        assertThatThrownBy(() -> jdbc.update("UPDATE guide_workflow_activation SET activated_by = 'other'"))
                .hasMessageContaining("cannot be changed or removed");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM guide_workflow_activation")).hasMessageContaining("cannot be changed or removed");
        assertThatThrownBy(this::activate).hasMessageContaining("duplicate key");
    }

    void failOn(String operation) {
        jdbc.execute("""
            CREATE FUNCTION guide_publication_fail() RETURNS trigger LANGUAGE plpgsql AS $$
            BEGIN RAISE EXCEPTION 'synthetic publication failure'; END; $$""");
        jdbc.execute("CREATE TRIGGER guide_publication_fail BEFORE " + operation + " ON guide_publication FOR EACH ROW EXECUTE FUNCTION guide_publication_fail()");
    }

    @Test
    void aFailureAfterTheProjectionWriteRollsBackReplacementCompletely() throws Exception {
        activate();
        var first = revision();
        var live = publish(first, null);
        var second = revision(root -> root.put("title", "Never visible"));
        String before = state();
        failOn("INSERT");
        assertThatThrownBy(() -> publications.publish(request(second, live.id()))).hasMessageContaining("synthetic publication failure");
        assertThat(state()).isEqualTo(before);
        assertThat(get("/" + first.slug()).body()).doesNotContain("Never visible");
    }

    @Test
    void aFailureWhileEndingTheRecordRollsBackWithdrawalCompletely() throws Exception {
        activate();
        var revision = revision();
        var live = publish(revision, null);
        String before = state();
        failOn("UPDATE");
        assertThatThrownBy(() -> publications.withdraw(revision.slug(), live.id(), "Synthetic reason", OPERATOR))
                .hasMessageContaining("synthetic publication failure");
        assertThat(state()).isEqualTo(before);
        assertThat(get("/" + revision.slug()).statusCode()).isEqualTo(200);
    }

    <T> java.util.List<T> concurrently(int threads, java.util.function.IntFunction<Callable<T>> task) throws Exception {
        var pool = Executors.newFixedThreadPool(threads);
        try {
            var start = new CountDownLatch(1);
            var futures = new ArrayList<java.util.concurrent.Future<T>>();
            for (int index = 0; index < threads; index++) {
                var work = task.apply(index);
                futures.add(pool.submit(() -> { start.await(); return work.call(); }));
            }
            start.countDown();
            var results = new ArrayList<T>();
            for (var future : futures) { results.add(future.get(60, java.util.concurrent.TimeUnit.SECONDS)); }
            return results;
        } finally { pool.shutdownNow(); }
    }

    @Test
    void concurrentPublicationsOfOneSlugSerializeToExactlyOneWinner() throws Exception {
        activate();
        var candidates = new ArrayList<GuideRevision>();
        for (int index = 0; index < 6; index++) {
            int n = index;
            candidates.add(revision(root -> root.put("title", "Candidate " + n)));
        }
        var outcomes = concurrently(6, index -> () -> {
            try { return publications.publish(request(candidates.get(index), null)).outcome().name(); }
            catch (GuidePublicationService.Refused refused) { return refused.code; }
        });
        assertThat(outcomes).containsOnlyOnce("PUBLISHED");
        assertThat(outcomes).filteredOn(o -> !o.equals("PUBLISHED")).containsOnly("STALE_CURRENT");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM guide_publication WHERE ended_at IS NULL", Integer.class)).isEqualTo(1);
        assertThat(rows("guide")).isEqualTo(1);

        var winner = candidates.stream().filter(r -> new GuidePublicationStore(jdbc).forRevision(r.id()).isPresent()).findFirst().orElseThrow();
        var repeated = concurrently(6, index -> () -> publications.publish(request(winner, null)).outcome());
        assertThat(repeated).containsOnly(ALREADY_PUBLISHED);
        assertThat(rows("guide_publication")).isEqualTo(1);
    }

    @Test
    void guideResponsesAreNotCacheableAndKeepTheirBodies() throws Exception {
        activate();
        publish(revision(), null);
        for (var suffix : java.util.List.of("", "/synthetic-evidence-guide", "/missing-guide")) {
            var response = get(suffix);
            assertThat(response.headers().firstValue("Cache-Control")).as(suffix).hasValue("no-store");
        }
        assertThat(get("/missing-guide").body()).isEqualTo("{\"code\":\"GUIDE_NOT_FOUND\",\"message\":\"Guide not found.\"}");
        assertThat(get("").body()).startsWith("[{\"slug\":\"synthetic-evidence-guide\"");
    }

    @Test
    void cliPublishesAndWithdrawsOnlyWithEveryRequiredArgument(@TempDir Path dir) throws Exception {
        activate();
        var revision = revision();
        String id = revision.id().toString();
        String[] base = {"--guide-publish=" + id, "--expect-digest=" + revision.draftDigest(), "--expect-current=none",
                "--checklist=standard-v1", "--verified-on=" + today()};
        assertThatIllegalArgumentException().isThrownBy(() -> command.run(new DefaultApplicationArguments(base)))
                .withMessageContaining("--confirm");
        assertThatIllegalArgumentException().isThrownBy(() -> command.run(new DefaultApplicationArguments(
                "--guide-publish=" + id, "--expect-current=none", "--checklist=standard-v1", "--verified-on=" + today(), "--confirm")))
                .withMessageContaining("--expect-digest");
        assertThatIllegalArgumentException().isThrownBy(() -> command.run(new DefaultApplicationArguments(
                "--guide-publish=" + id, "--guide-withdraw=x")));
        var file = Files.writeString(dir.resolve("g.json"), "{}");
        assertThatIllegalArgumentException().isThrownBy(() -> command.run(new DefaultApplicationArguments(
                "--guide-publish=" + id, "--import-guide=" + file)));
        assertThatIllegalArgumentException().isThrownBy(() -> command.run(new DefaultApplicationArguments(
                "--guide-revision-create=" + file, "--reason=x")));
        assertThatIllegalArgumentException().isThrownBy(() -> command.run(new DefaultApplicationArguments("--confirm")));
        assertThat(rows("guide_publication")).isZero();

        var withConfirm = java.util.Arrays.copyOf(base, base.length + 1);
        withConfirm[base.length] = "--confirm";
        command.run(new DefaultApplicationArguments(withConfirm));
        var publication = new GuidePublicationStore(jdbc).open(revision.slug()).orElseThrow();
        assertThat(publication.revisionId()).isEqualTo(revision.id());

        assertThatIllegalArgumentException().isThrownBy(() -> command.run(new DefaultApplicationArguments(
                "--guide-withdraw=" + revision.slug(), "--expect-current=" + publication.id())));
        command.run(new DefaultApplicationArguments("--guide-withdraw=" + revision.slug(),
                "--expect-current=" + publication.id(), "--reason=Synthetic CLI withdrawal"));
        assertThat(rows("guide")).isZero();
        assertThat(new GuidePublicationStore(jdbc).forRevision(revision.id()).orElseThrow().endReason()).isEqualTo("WITHDRAWN");
    }
}
