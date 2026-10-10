package info.lifeinuk.backend.guides;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Read-only editorial quality gate for one stored revision (architecture section 4). Automatic checks cover
 * structure, consistency and evidence references only: they never establish that a source is current or correct,
 * and they are not legal approval or immigration advice. The category checklist is for the human operator.
 */
@Component
class GuideQualityGate {
    enum Severity { BLOCKING, WARNING }
    record Finding(Severity severity, String code, String message) { }

    enum Checklist {
        STANDARD_V1("standard-v1", List.of(
                "Every factual claim that needs support has evidence from an official or authoritative source, checked as at the verified-on date.",
                "Eligibility conditions and limitations are stated.",
                "Material uncertainty is stated, not hidden.",
                "The text is general information, not advice about an individual's situation.")),
        FAMILY_VISA_V1("family-visa-v1", List.of(
                "Rules are checked against the current official rules and guidance as at the verified-on date; the version or date of the rules checked is noted in the evidence.",
                "Every eligibility condition, exception and time limit in the cited source is stated, or explicitly placed outside the Guide's scope.",
                "Every number (fees, thresholds, periods) has its own evidence.",
                "Pathways are described as general options with their conditions: no recommendation for a particular person, no assessment of an individual case, no guarantee or prediction of outcome.",
                "Points where general information may not be enough are signposted to official sources and appropriately regulated advisers.",
                "Any uncertain item, including whether wording could amount to regulated advice, is removed or flagged for professional verification before publishing."));
        final String id;
        final List<String> items;
        Checklist(String id, List<String> items) { this.id = id; this.items = items; }
        static Checklist forCategory(String category) {
            return FAMILY_VISA_CATEGORY.equals(category) ? FAMILY_VISA_V1 : STANDARD_V1;
        }
    }

    record Report(GuideRevision revision, GuideImportDefinition definition, Checklist checklist,
            Optional<GuideRevisionStore.PublicState> publicState, List<Finding> findings) {
        boolean blocked() { return findings.stream().anyMatch(f -> f.severity() == Severity.BLOCKING); }
        List<Finding> of(Severity severity) { return findings.stream().filter(f -> f.severity() == severity).toList(); }
    }

    static final String FAMILY_VISA_CATEGORY = "family-visa";
    static final List<String> EDITORIAL_MARKERS = List.of("[EDITORIAL", "[EVIDENCE:", "TODO", "待核对");
    static final String LAST_UPDATED_MARKER = "最后更新";
    private static final String EVIDENCE_LINK = "(#guide-evidence-";

    private final GuideRevisionService revisions;

    GuideQualityGate(GuideRevisionService revisions) { this.revisions = revisions; }

    /** Loads a revision and checks it. Read-only: performs no writes of any kind. */
    Report check(java.util.UUID revisionId, Instant now) {
        var revision = revisions.find(revisionId)
                .orElseThrow(() -> new IllegalArgumentException("Revision not found: " + revisionId));
        return check(revision, revisions.publicState(revision.slug()), now);
    }

    Report check(GuideRevision revision, Optional<GuideRevisionStore.PublicState> publicState, Instant now) {
        var findings = new ArrayList<Finding>();
        GuideImportDefinition definition;
        try {
            definition = revisions.definition(revision.canonicalForm());
        } catch (IllegalArgumentException invalid) {
            findings.add(new Finding(Severity.BLOCKING, "CANONICAL_INVALID",
                    "Stored content no longer passes Guide validation: " + invalid.getMessage()));
            return new Report(revision, null, Checklist.STANDARD_V1, publicState, List.copyOf(findings));
        }
        var checklist = Checklist.forCategory(definition.category());
        boolean familyVisa = checklist == Checklist.FAMILY_VISA_V1;

        if (!GuideContentDigest.canonicalForm(definition).equals(revision.canonicalForm())
                || !GuideContentDigest.digest(definition).equals(revision.draftDigest())) {
            findings.add(new Finding(Severity.BLOCKING, "CANONICAL_MISMATCH",
                    "Stored text is not the exact guide-content-v1 form of its content, or its digest differs"));
        }
        if (definition.status() != GuideStatus.DRAFT || definition.publishedAt() != null) {
            findings.add(new Finding(Severity.BLOCKING, "NOT_DRAFT", "Revision content must be DRAFT with no publishedAt"));
        }

        var evidence = definition.evidence();
        if (evidence == null) {
            findings.add(new Finding(Severity.BLOCKING, "EVIDENCE_ABSENT", "The evidence collection is absent"));
        } else if (evidence.isEmpty()) {
            findings.add(new Finding(familyVisa ? Severity.BLOCKING : Severity.WARNING, "EVIDENCE_EMPTY",
                    familyVisa ? "Family & Visa content requires at least one evidence entry"
                            : "No evidence entries: claims in this Guide have no recorded support"));
        }

        markers(definition, findings);

        var cited = new HashSet<String>();
        for (var item : evidence == null ? List.<GuideImportDefinition.Evidence>of() : evidence) {
            item.supports().forEach(support -> cited.add(support.sourceKey()));
        }
        for (int index = 0; index < definition.sources().size(); index++) {
            var source = definition.sources().get(index);
            String label = "source[" + index + "]" + (source.key() == null ? "" : " " + source.key());
            String scheme = scheme(source.url());
            if (!"https".equalsIgnoreCase(scheme)) {
                findings.add(new Finding(Severity.BLOCKING, "SOURCE_NOT_HTTPS", label + " URL is not HTTPS: " + source.url()));
            }
            if (source.accessedAt().isAfter(now)) {
                findings.add(new Finding(Severity.BLOCKING, "SOURCE_ACCESSED_IN_FUTURE",
                        label + " accessedAt is in the future: " + source.accessedAt()));
            }
            if (source.key() == null || !cited.contains(source.key())) {
                findings.add(new Finding(familyVisa ? Severity.BLOCKING : Severity.WARNING, "SOURCE_UNUSED",
                        label + " is not cited by any evidence support" + (source.key() == null ? " (it has no key)" : "")));
            }
        }

        if (!definition.content().contains(LAST_UPDATED_MARKER)) {
            findings.add(new Finding(Severity.WARNING, "LAST_UPDATED_MISSING",
                    "Content has no \"" + LAST_UPDATED_MARKER + "\" (last updated) line"));
        }
        return new Report(revision, definition, checklist, publicState, List.copyOf(findings));
    }

    private static void markers(GuideImportDefinition guide, List<Finding> findings) {
        scan("title", guide.title(), findings);
        scan("summary", guide.summary(), findings);
        String[] lines = guide.content().split("\n", -1);
        for (int line = 0; line < lines.length; line++) {
            scan("content line " + (line + 1), lines[line], findings);
        }
        for (int index = 0; index < guide.sources().size(); index++) {
            var source = guide.sources().get(index);
            scan("source[" + index + "].organisation", source.organisation(), findings);
            scan("source[" + index + "].title", source.title(), findings);
        }
        for (var item : guide.evidence() == null ? List.<GuideImportDefinition.Evidence>of() : guide.evidence()) {
            scan("evidence " + item.key() + " statement", item.statement(), findings);
            for (int index = 0; index < item.supports().size(); index++) {
                var support = item.supports().get(index);
                String label = "evidence " + item.key() + " support[" + index + "]";
                scan(label + ".locator", support.locator(), findings);
                scan(label + ".excerpt", support.excerpt(), findings);
                scan(label + ".note", support.note(), findings);
            }
        }
    }

    private static void scan(String location, String text, List<Finding> findings) {
        if (text == null) { return; }
        for (String marker : EDITORIAL_MARKERS) {
            if (text.contains(marker)) {
                findings.add(new Finding(Severity.BLOCKING, "EDITORIAL_MARKER", location + " contains \"" + marker + "\""));
            }
        }
    }

    private static String scheme(String url) {
        try { return new URI(url).getScheme(); }
        catch (java.net.URISyntaxException invalid) { return null; }
    }

    /** Plain-text report for the operator. */
    static String render(Report report) {
        var out = new StringBuilder();
        var revision = report.revision();
        out.append("Guide revision quality check\n");
        out.append("  slug:          ").append(revision.slug()).append('\n');
        out.append("  revision:      ").append(revision.id()).append(" (#").append(revision.revisionNumber()).append(")\n");
        out.append("  draft digest:  ").append(revision.draftDigest()).append(" (").append(revision.canonicalization()).append(")\n");
        out.append("  created:       ").append(revision.createdAt()).append(" by ").append(revision.createdBy())
                .append(revision.aiAssisted() ? " (AI-assisted)" : "").append('\n');
        if (revision.basedOnRevisionId() != null) {
            out.append("  based on:      ").append(revision.basedOnRevisionId()).append('\n');
        }
        out.append("  public now:    ").append(report.publicState()
                .map(state -> state.status() + " (updatedAt " + state.updatedAt() + ")").orElse("none")).append('\n');
        out.append("  (A comparison with the published version needs publication history, which this release does not record.)\n");

        var guide = report.definition();
        if (guide != null) {
            var evidence = guide.evidence() == null ? List.<GuideImportDefinition.Evidence>of() : guide.evidence();
            int links = count(guide.content(), EVIDENCE_LINK);
            int supports = evidence.stream().mapToInt(item -> item.supports().size()).sum();
            out.append("\nContent: ").append(guide.category()).append(" | ").append(guide.title()).append('\n');
            out.append("  sources ").append(guide.sources().size()).append(", evidence ").append(evidence.size())
                    .append(", supports ").append(supports).append(", evidence links in content ").append(links).append('\n');
            out.append("\nSources:\n");
            for (var source : guide.sources()) {
                out.append("  - ").append(source.key() == null ? "(no key)" : source.key()).append(": ")
                        .append(source.organisation()).append(" | ").append(source.title()).append(" | ")
                        .append(source.url()).append(" | accessed ").append(source.accessedAt()).append('\n');
            }
            out.append("\nEvidence:\n");
            for (var item : evidence) {
                out.append("  - ").append(item.key()).append(": ").append(item.statement()).append('\n');
                for (var support : item.supports()) {
                    out.append("      supported by ").append(support.sourceKey())
                            .append(support.locator() == null ? "" : " @ " + support.locator()).append('\n');
                }
            }
        }

        out.append("\nBlocking failures (").append(report.of(Severity.BLOCKING).size()).append("):\n");
        report.of(Severity.BLOCKING).forEach(f -> out.append("  [").append(f.code()).append("] ").append(f.message()).append('\n'));
        out.append("Warnings (").append(report.of(Severity.WARNING).size()).append("):\n");
        report.of(Severity.WARNING).forEach(f -> out.append("  [").append(f.code()).append("] ").append(f.message()).append('\n'));

        out.append("\nChecklist ").append(report.checklist().id)
                .append(" (confirmed by the operator at publication; NOT checked by this tool):\n");
        for (int index = 0; index < report.checklist().items.size(); index++) {
            out.append("  ").append(index + 1).append(". ").append(report.checklist().items.get(index)).append('\n');
        }
        out.append("\nAutomatic checks cover structure, consistency and evidence references only. They do not confirm\n")
                .append("that any source is current or correct, and they are not legal approval or immigration advice.\n")
                .append("Human verification is required before publication. This command publishes nothing.\n");
        out.append("\nRESULT: ").append(report.blocked() ? "BLOCKED" : "NO BLOCKING FAILURES (human checklist still required)")
                .append('\n');
        return out.toString();
    }

    private static int count(String text, String needle) {
        int count = 0;
        for (int at = text.indexOf(needle); at >= 0; at = text.indexOf(needle, at + needle.length())) { count++; }
        return count;
    }
}
