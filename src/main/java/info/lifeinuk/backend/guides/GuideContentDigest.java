package info.lifeinuk.backend.guides;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.List;

/**
 * Deterministic SHA-256 digest of one validated Guide import definition, canonicalization {@value #VERSION}.
 * It is computed from {@link GuideImportDefinition} (never raw file bytes or persistence entities), so JSON
 * formatting, property order and equivalent timestamp offsets cannot change it, and database UUIDs are never
 * part of it. The canonical form is one line of JSON built by this class:
 *
 * <pre>
 * {"canonicalization":"guide-content-v1","slug":…,"category":…,"title":…,"summary":…,"content":…,
 *  "status":"DRAFT"|"PUBLISHED","publishedAt":TIME|null,"updatedAt":TIME,
 *  "sources":[{"key":…|null,"organisation":…,"title":…,"url":…,"accessedAt":TIME},…],
 *  "evidence":null|[{"key":…,"statement":…,"supports":[{"sourceKey":…,"locator":…|null,"excerpt":…|null,"note":…},…]},…]}
 * </pre>
 *
 * <ul>
 * <li>Properties appear exactly in the order above, with no whitespace between tokens.</li>
 * <li>Arrays keep input order: source, evidence and support order are editorial and change the digest.</li>
 * <li>TIME is the UTC instant as {@code yyyy-MM-dd'T'HH:mm:ss.SSSSSS'Z'} (always six fractional digits).</li>
 * <li>Optional values that are absent or null are written as JSON null; every property is always present.
 *     An absent {@code evidence} collection is written as {@code null} and an explicit empty one as {@code []}:
 *     the importer treats them differently for a Guide that already has evidence.</li>
 * <li>Strings are taken exactly as validated (no trimming or Unicode normalization). Quotation mark and
 *     backslash are escaped with a preceding backslash. U+0000–U+001F and unpaired surrogates are escaped as a
 *     backslash, {@code u} and four lower-case hexadecimal digits. Every other character is written literally.</li>
 * <li>The digest is SHA-256 over the UTF-8 bytes of that line, as 64 lower-case hexadecimal characters.</li>
 * </ul>
 *
 * Any change to this representation must introduce a new canonicalization version, never alter this one.
 */
final class GuideContentDigest {
    static final String VERSION = "guide-content-v1";
    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSSSS'Z'").withZone(ZoneOffset.UTC);

    private GuideContentDigest() { }

    /** Lower-case hexadecimal SHA-256 of {@link #canonicalForm}. */
    static String digest(GuideImportDefinition guide) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonicalForm(guide).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 is unavailable", unavailable);
        }
    }

    /** The exact {@value #VERSION} text that is hashed. */
    static String canonicalForm(GuideImportDefinition guide) {
        var out = new StringBuilder();
        out.append('{');
        property(out, "canonicalization", VERSION, true);
        property(out, "slug", guide.slug(), false);
        property(out, "category", guide.category(), false);
        property(out, "title", guide.title(), false);
        property(out, "summary", guide.summary(), false);
        property(out, "content", guide.content(), false);
        property(out, "status", guide.status().name(), false);
        property(out, "publishedAt", time(guide.publishedAt()), false);
        property(out, "updatedAt", time(guide.updatedAt()), false);
        name(out, "sources", false).append('[');
        for (int index = 0; index < guide.sources().size(); index++) {
            var source = guide.sources().get(index);
            if (index > 0) { out.append(','); }
            out.append('{');
            property(out, "key", source.key(), true);
            property(out, "organisation", source.organisation(), false);
            property(out, "title", source.title(), false);
            property(out, "url", source.url(), false);
            property(out, "accessedAt", time(source.accessedAt()), false);
            out.append('}');
        }
        out.append(']');
        name(out, "evidence", false);
        if (guide.evidence() == null) { return out.append("null}").toString(); }
        List<GuideImportDefinition.Evidence> evidence = guide.evidence();
        out.append('[');
        for (int index = 0; index < evidence.size(); index++) {
            var item = evidence.get(index);
            if (index > 0) { out.append(','); }
            out.append('{');
            property(out, "key", item.key(), true);
            property(out, "statement", item.statement(), false);
            name(out, "supports", false).append('[');
            for (int supportIndex = 0; supportIndex < item.supports().size(); supportIndex++) {
                var support = item.supports().get(supportIndex);
                if (supportIndex > 0) { out.append(','); }
                out.append('{');
                property(out, "sourceKey", support.sourceKey(), true);
                property(out, "locator", support.locator(), false);
                property(out, "excerpt", support.excerpt(), false);
                property(out, "note", support.note(), false);
                out.append('}');
            }
            out.append("]}");
        }
        return out.append("]}").toString();
    }

    private static String time(Instant value) { return value == null ? null : TIME.format(value); }

    private static StringBuilder name(StringBuilder out, String name, boolean first) {
        if (!first) { out.append(','); }
        return string(out, name).append(':');
    }

    private static void property(StringBuilder out, String name, String value, boolean first) {
        name(out, name, first);
        if (value == null) { out.append("null"); } else { string(out, value); }
    }

    private static StringBuilder string(StringBuilder out, String value) {
        out.append('"');
        for (int index = 0; index < value.length(); index++) {
            char c = value.charAt(index);
            if (c == '"' || c == '\\') {
                out.append('\\').append(c);
            } else if (c < 0x20) {
                unicode(out, c);
            } else if (Character.isHighSurrogate(c) && index + 1 < value.length()
                    && Character.isLowSurrogate(value.charAt(index + 1))) {
                out.append(c).append(value.charAt(++index));
            } else if (Character.isSurrogate(c)) {
                unicode(out, c); // unpaired: UTF-8 cannot encode it, so escape instead of losing it
            } else {
                out.append(c);
            }
        }
        return out.append('"');
    }

    private static void unicode(StringBuilder out, char c) {
        out.append("\\u").append(HexFormat.of().toHexDigits(c));
    }
}
