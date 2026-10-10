package info.lifeinuk.backend.guides;

import info.lifeinuk.backend.support.IsolatedPostgres;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ContextConfiguration;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import static info.lifeinuk.backend.guides.GuideRevisionService.Outcome.*;
import static org.assertj.core.api.Assertions.*;

/** Immutable revisions, revision CLI and read-only check, on an isolated schema with synthetic fixtures only. */
@SpringBootTest(properties = {
    "spring.flyway.schemas=guide_revision_tests", "spring.flyway.default-schema=guide_revision_tests",
    "spring.jpa.properties.hibernate.default_schema=guide_revision_tests", "spring.datasource.hikari.schema=guide_revision_tests",
    "LIFE_IN_UK_OPERATOR=synthetic-operator",
    // Similarly named properties never invoke a command on ordinary startup.
    "guide-revision-create=/must-not-be-read.json", "guide-check=00000000-0000-0000-0000-000000000000"
})
@ContextConfiguration(initializers = IsolatedPostgres.Initializer.class)
class GuideRevisionTest {
    @Autowired GuideRevisionService revisions;
    @Autowired GuideRevisionCommand command;
    @Autowired GuideQualityGate gate;
    @Autowired GuideImporter importer;
    @Autowired GuideImportReader reader;
    @Autowired GuideQuery query;
    @Autowired JdbcTemplate jdbc;
    private final JsonMapper json = JsonMapper.builder().build();

    @BeforeEach
    void clear() { jdbc.execute("TRUNCATE guide_revision, guide_evidence_support, guide_evidence, guide_source, guide"); }

    GuideImportDefinition draft(Consumer<ObjectNode> edit) throws Exception {
        var root = (ObjectNode) json.readTree(GuideContentDigestTest.fixture("synthetic-evidence-import.json"));
        edit.accept(root);
        return reader.parse(json.writeValueAsString(root).getBytes(StandardCharsets.UTF_8));
    }
    GuideImportDefinition draft() throws Exception { return draft(root -> { }); }
    GuideRevisionService.Result create(GuideImportDefinition draft) {
        return revisions.create(draft, null, "synthetic-operator", false, null);
    }
    int count() { return jdbc.queryForObject("SELECT count(*) FROM guide_revision", Integer.class); }
    String projection() {
        return jdbc.queryForObject("""
            SELECT json_build_object('g',(SELECT json_agg(row_to_json(g) ORDER BY id) FROM guide g),
              's',(SELECT json_agg(row_to_json(s) ORDER BY id) FROM guide_source s),
              'e',(SELECT json_agg(row_to_json(e) ORDER BY id) FROM guide_evidence e),
              'p',(SELECT json_agg(row_to_json(p) ORDER BY id) FROM guide_evidence_support p))::text
            """, String.class);
    }
    String revisionTable() {
        return jdbc.queryForObject("SELECT coalesce(json_agg(row_to_json(r) ORDER BY id), '[]')::text FROM guide_revision r", String.class);
    }
    Path file(Path dir, String content) throws Exception {
        return Files.writeString(dir.resolve("draft.json"), content, StandardCharsets.UTF_8);
    }

    @Test
    void createStoresExactCanonicalDraftWithStableDigestAndDatabaseProvenance() throws Exception {
        var input = draft();
        var result = revisions.create(input, null, "synthetic-operator", true, "First synthetic draft");
        var revision = result.revision();
        assertThat(result.outcome()).isEqualTo(CREATED);
        assertThat(revision.revisionNumber()).isEqualTo(1);
        assertThat(revision.canonicalization()).isEqualTo("guide-content-v1");
        assertThat(revision.canonicalForm()).isEqualTo(GuideContentDigest.canonicalForm(input));
        // The digest of the unchanged DRAFT fixture is pinned independently in GuideContentDigestTest.
        assertThat(revision.draftDigest()).isEqualTo(GuideContentDigestTest.SYNTHETIC_EVIDENCE_DIGEST);
        assertThat(jdbc.queryForObject("SELECT encode(sha256(convert_to(canonical_form,'UTF8')),'hex') FROM guide_revision", String.class))
                .isEqualTo(revision.draftDigest());
        assertThat(revision.createdBy()).isEqualTo("synthetic-operator");
        assertThat(revision.dbSessionUser()).isEqualTo("life_in_uk_test");
        assertThat(revision.aiAssisted()).isTrue();
        assertThat(revision.note()).isEqualTo("First synthetic draft");
        assertThat(revision.createdAt()).isBefore(Instant.now().plusSeconds(1));
        assertThat(revisions.definition(revision.canonicalForm())).isEqualTo(input);
    }

    @Test
    void identicalContentReturnsTheExistingRevisionAndChangesNumberPerSlug() throws Exception {
        var first = create(draft());
        String before = revisionTable();
        var again = create(draft());
        assertThat(again.outcome()).isEqualTo(EXISTING);
        assertThat(again.revision()).isEqualTo(first.revision());
        assertThat(revisionTable()).isEqualTo(before);
        var second = create(draft(root -> root.put("title", "Synthetic evidence guide, revised")));
        assertThat(second.outcome()).isEqualTo(CREATED);
        assertThat(second.revision().revisionNumber()).isEqualTo(2);
        var otherSlug = create(draft(root -> root.put("slug", "another-synthetic-guide")));
        assertThat(otherSlug.revision().revisionNumber()).isEqualTo(1);
        assertThat(count()).isEqualTo(3);
    }

    @Test
    void absentEvidenceIsRejectedButExplicitEmptyEvidenceIsAcceptedWithItsOwnDigest() throws Exception {
        var absent = reader.parse(GuideImportReaderTest.fixture().getBytes(StandardCharsets.UTF_8));
        assertThat(absent.evidence()).isNull();
        assertThatIllegalArgumentException().isThrownBy(() -> create(absent)).withMessageContaining("explicit evidence");
        var root = (ObjectNode) json.readTree(GuideImportReaderTest.fixture());
        root.putArray("evidence");
        var empty = create(reader.parse(json.writeValueAsString(root).getBytes(StandardCharsets.UTF_8)));
        assertThat(empty.outcome()).isEqualTo(CREATED);
        assertThat(empty.revision().draftDigest()).isEqualTo(GuideContentDigestTest.SYNTHETIC_EMPTY_EVIDENCE_DIGEST);
        assertThat(empty.revision().canonicalForm()).endsWith(",\"evidence\":[]}");
        assertThat(count()).isEqualTo(1);
    }

    @Test
    void onlyDraftContentFromAValidOperatorIsAccepted() throws Exception {
        var published = draft(root -> { root.put("status", "PUBLISHED"); root.put("publishedAt", "2026-10-04T12:00:00Z"); });
        assertThatIllegalArgumentException().isThrownBy(() -> create(published)).withMessageContaining("DRAFT");
        assertThatIllegalArgumentException().isThrownBy(() -> revisions.create(draft(), null, " ", false, null))
                .withMessageContaining("LIFE_IN_UK_OPERATOR");
        assertThatIllegalArgumentException().isThrownBy(() -> revisions.create(draft(), null, "op", false, " "))
                .withMessageContaining("note");
        assertThat(count()).isZero();
    }

    @Test
    void basedOnMustBeAnExistingRevisionOfTheSameSlug() throws Exception {
        var parent = create(draft()).revision();
        var child = revisions.create(draft(root -> root.put("title", "Child")), parent.id(), "op", false, null).revision();
        assertThat(child.basedOnRevisionId()).isEqualTo(parent.id());
        assertThatIllegalArgumentException().isThrownBy(() -> revisions.create(draft(root -> root.put("title", "Orphan")),
                UUID.randomUUID(), "op", false, null)).withMessageContaining("does not exist");
        assertThatIllegalArgumentException().isThrownBy(() -> revisions.create(draft(root -> root.put("slug", "other-guide")),
                parent.id(), "op", false, null)).withMessageContaining("different slug");
        assertThat(count()).isEqualTo(2);
    }

    @Test
    void databaseRejectsUpdateAndDeleteOfStoredRevisions() throws Exception {
        var revision = create(draft()).revision();
        String before = revisionTable();
        assertThatThrownBy(() -> jdbc.update("UPDATE guide_revision SET note = 'changed' WHERE id = ?", revision.id()))
                .hasMessageContaining("Guide revisions are immutable");
        assertThatThrownBy(() -> jdbc.update("UPDATE guide_revision SET canonical_form = canonical_form || ' ' WHERE id = ?", revision.id()))
                .hasMessageContaining("Guide revisions are immutable");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM guide_revision WHERE id = ?", revision.id()))
                .hasMessageContaining("Guide revisions are immutable");
        assertThat(revisionTable()).isEqualTo(before);
    }

    String insert(String slug, String canonicalForm, String digest) {
        jdbc.update("""
                INSERT INTO guide_revision (id, slug, revision_number, canonicalization, canonical_form, draft_digest,
                    created_at, created_by, db_session_user, ai_assisted)
                VALUES (?, ?, 1, 'guide-content-v1', ?, ?, '2000-01-01T00:00:00Z', 'forged', 'forged', false)
                """, UUID.randomUUID(), slug, canonicalForm, digest);
        return jdbc.queryForObject("SELECT created_by || '|' || db_session_user || '|' || (created_at > '2001-01-01') FROM guide_revision", String.class);
    }
    static String sha(String text) throws Exception {
        return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void databaseChecksDigestDraftStatusSlugAndEvidencePresenceOnDirectInserts() throws Exception {
        var input = draft();
        String canonical = GuideContentDigest.canonicalForm(input);
        assertThatThrownBy(() -> insert(input.slug(), canonical, "0".repeat(64))).hasMessageContaining("digest does not match");
        assertThatThrownBy(() -> insert("other-slug", canonical, sha(canonical))).hasMessageContaining("canonical DRAFT content");
        String published = canonical.replace("\"status\":\"DRAFT\",\"publishedAt\":null",
                "\"status\":\"PUBLISHED\",\"publishedAt\":\"2026-10-04T12:00:00.000000Z\"");
        assertThatThrownBy(() -> insert(input.slug(), published, sha(published))).hasMessageContaining("canonical DRAFT content");
        var root = (ObjectNode) json.readTree(GuideImportReaderTest.fixture());
        String absentEvidence = GuideContentDigest.canonicalForm(reader.parse(json.writeValueAsString(root).getBytes(StandardCharsets.UTF_8)));
        assertThat(absentEvidence).endsWith(",\"evidence\":null}");
        assertThatThrownBy(() -> insert("synthetic-guide", absentEvidence, sha(absentEvidence))).hasMessageContaining("explicit evidence");
        assertThatThrownBy(() -> insert(input.slug(), "not json", sha("not json"))).hasMessageContaining("not valid JSON");
        assertThat(count()).isZero();
        // Provenance columns are written by the database, whatever the client supplies.
        assertThat(insert(input.slug(), canonical, sha(canonical))).isEqualTo("forged|life_in_uk_test|true");
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
    void concurrentCreationIsSerializedPerSlug() throws Exception {
        var input = draft();
        var same = concurrently(8, index -> () -> create(input));
        assertThat(same).extracting(GuideRevisionService.Result::outcome).containsOnlyOnce(CREATED);
        assertThat(same).extracting(result -> result.revision().id()).containsOnly(same.getFirst().revision().id());
        var different = concurrently(8, index -> () -> create(draft(root -> root.put("title", "Concurrent title " + index))));
        assertThat(different).extracting(GuideRevisionService.Result::outcome).containsOnly(CREATED);
        assertThat(jdbc.queryForList("SELECT revision_number FROM guide_revision ORDER BY revision_number", Integer.class))
                .containsExactly(1, 2, 3, 4, 5, 6, 7, 8, 9);
    }

    @Test
    void revisionsAndChecksNeverTouchThePublicProjectionOrPublicApi() throws Exception {
        var live = reader.parse(GuideImportReaderTest.fixture().replace("\"status\": \"DRAFT\"", "\"status\": \"PUBLISHED\"")
                .replace("\"publishedAt\": null", "\"publishedAt\": \"2026-10-04T12:00:00Z\"").getBytes(StandardCharsets.UTF_8));
        importer.apply(live);
        String before = projection();
        var publicList = query.list();
        var publicDetail = query.detail(live.slug());
        var revision = create(draft(root -> root.put("slug", live.slug()))).revision();
        var report = gate.check(revision.id(), Instant.now());
        assertThat(projection()).isEqualTo(before);
        assertThat(query.list()).isEqualTo(publicList);
        assertThat(query.detail(live.slug())).isEqualTo(publicDetail);
        assertThat(query.detail("synthetic-evidence-guide")).isEmpty();
        assertThat(GuideQualityGate.render(report)).contains("public now:    PUBLISHED");
    }

    @Test
    void cliCreatesAndReportsWhileCheckIsReadOnly(@TempDir Path dir) throws Exception {
        var path = file(dir, GuideContentDigestTest.fixture("synthetic-evidence-import.json"));
        command.run(new DefaultApplicationArguments("--guide-revision-create=" + path, "--ai-assisted", "--note=CLI draft"));
        var stored = jdbc.queryForMap("SELECT id, created_by, ai_assisted, note FROM guide_revision");
        assertThat(stored).containsEntry("created_by", "synthetic-operator").containsEntry("ai_assisted", true).containsEntry("note", "CLI draft");
        command.run(new DefaultApplicationArguments("--guide-revision-create=" + path));
        assertThat(count()).isEqualTo(1);

        String revisionsBefore = revisionTable();
        String projectionBefore = projection();
        command.run(new DefaultApplicationArguments("--guide-check=" + stored.get("id")));
        assertThat(revisionTable()).isEqualTo(revisionsBefore);
        assertThat(projection()).isEqualTo(projectionBefore);
    }

    @Test
    void cliRejectsInvalidFilesAndOptionCombinationsWithoutWriting(@TempDir Path dir) throws Exception {
        var valid = file(dir, GuideContentDigestTest.fixture("synthetic-evidence-import.json"));
        var invalid = Files.writeString(dir.resolve("invalid.json"), "{\"slug\":\"x\"}");
        String id = UUID.randomUUID().toString();
        assertThatIllegalArgumentException().isThrownBy(() -> command.run(new DefaultApplicationArguments("--guide-revision-create=" + invalid)));
        assertThatIllegalArgumentException().isThrownBy(() -> command.run(new DefaultApplicationArguments(
                "--guide-revision-create=" + valid, "--guide-check=" + id)));
        assertThatIllegalArgumentException().isThrownBy(() -> command.run(new DefaultApplicationArguments(
                "--guide-revision-create=" + valid, "--import-guide=" + valid)));
        assertThatIllegalArgumentException().isThrownBy(() -> command.run(new DefaultApplicationArguments("--ai-assisted")));
        assertThatIllegalArgumentException().isThrownBy(() -> command.run(new DefaultApplicationArguments(
                "--guide-revision-create=" + valid, "--ai-assisted=yes")));
        assertThatIllegalArgumentException().isThrownBy(() -> command.run(new DefaultApplicationArguments(
                "--guide-revision-create=" + valid, "--based-on=not-a-uuid")));
        assertThatIllegalArgumentException().isThrownBy(() -> command.run(new DefaultApplicationArguments("--guide-check=" + id)))
                .withMessageContaining("Revision not found");
        assertThatIllegalArgumentException().isThrownBy(() -> new GuideRevisionCommand(reader, revisions, gate, "")
                .run(new DefaultApplicationArguments("--guide-revision-create=" + valid))).withMessageContaining("LIFE_IN_UK_OPERATOR");
        assertThat(count()).isZero();
        assertThat(projection()).contains("\"g\" : null");
    }

    @Test
    void ordinaryStartupWithSimilarlyNamedPropertiesCreatesNothing() {
        assertThat(count()).isZero();
        assertThatNoException().isThrownBy(() -> command.run(new DefaultApplicationArguments()));
        assertThat(count()).isZero();
    }
}
