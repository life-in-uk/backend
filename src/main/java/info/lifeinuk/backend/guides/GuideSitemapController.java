package info.lifeinuk.backend.guides;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Sitemap of the prerendered public pages: published Guides, topic hubs, the five home pages and the
 * Family & Visa topic pages. Its public origin is explicit so a proxy host cannot poison canonical URLs.
 */
@RestController
class GuideSitemapController {
    private static final List<String> LOCALES = List.of("zh-CN", "zh-TW", "en", "ur", "hi");
    /**
     * Family & Visa topic pages (frontend router FAMILY_VISA_TOPICS) and the Simplified Chinese Guide whose verified
     * content each shows. Listed in every locale while that Guide is published; the frontend release build checks that
     * the page really shows verified content and fails if this list and the prerendered pages disagree.
     */
    private static final Map<String, String> FAMILY_VISA_TOPICS = Map.of(
            "children-from-previous-relationship", "partner-visa-dependent-children");
    private final GuideQuery query;
    private final String publicBaseUrl;

    GuideSitemapController(GuideQuery query, @Value("${life-in-uk.public-base-url:}") String publicBaseUrl) {
        this.query = query;
        this.publicBaseUrl = publicBaseUrl;
    }

    @GetMapping(value = "/sitemap.xml", produces = MediaType.APPLICATION_XML_VALUE)
    ResponseEntity<String> sitemap() {
        URI origin = publicOrigin();
        if (origin == null) return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).cacheControl(CacheControl.noStore()).body("");
        Map<String, Map<String, GuideResponse.Metadata>> guides = new LinkedHashMap<>();
        for (String locale : LOCALES) {
            query.localizedList(locale).orElse(List.of()).forEach(metadata -> {
                if (domainPath(metadata.category()) != null) {
                    guides.computeIfAbsent(metadata.slug(), ignored -> new LinkedHashMap<>()).put(locale, metadata);
                }
            });
        }
        String base = origin.toString().replaceAll("/$", "");
        StringBuilder xml = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
                .append("<urlset xmlns=\"http://www.sitemaps.org/schemas/sitemap/0.9\" ")
                .append("xmlns:xhtml=\"http://www.w3.org/1999/xhtml\">\n");
        guides.forEach((slug, variants) -> variants.forEach((locale, metadata) -> {
            String path = "/" + locale + "/" + domainPath(metadata.category()) + "/" + slug;
            String loc = base + path;
            xml.append("  <url><loc>").append(xml(loc)).append("</loc>");
            for (String available : variants.keySet()) {
                GuideResponse.Metadata variant = variants.get(available);
                String alternate = base + "/" + available + "/" + domainPath(variant.category()) + "/" + slug;
                xml.append("<xhtml:link rel=\"alternate\" hreflang=\"").append(hreflang(available))
                        .append("\" href=\"").append(xml(alternate)).append("\"/>");
            }
            if (variants.containsKey("zh-CN")) {
                GuideResponse.Metadata fallback = variants.get("zh-CN");
                xml.append("<xhtml:link rel=\"alternate\" hreflang=\"x-default\" href=\"")
                        .append(xml(base + "/zh-CN/" + domainPath(fallback.category()) + "/" + slug)).append("\"/>");
            }
            xml.append("</url>\n");
        }));
        // Topic hubs (/{locale}/{domain}): one per section, in each locale where it has a published Guide.
        Map<String, java.util.Set<String>> hubs = new LinkedHashMap<>();
        guides.values().forEach(variants -> variants.forEach((locale, metadata) ->
                hubs.computeIfAbsent(domainPath(metadata.category()), ignored -> new java.util.LinkedHashSet<>()).add(locale)));
        hubs.forEach((domain, present) -> {
            List<String> locales = LOCALES.stream().filter(present::contains).toList();
            for (String locale : locales) {
                xml.append("  <url><loc>").append(xml(base + "/" + locale + "/" + domain)).append("</loc>");
                for (String available : locales) {
                    xml.append("<xhtml:link rel=\"alternate\" hreflang=\"").append(hreflang(available))
                            .append("\" href=\"").append(xml(base + "/" + available + "/" + domain)).append("\"/>");
                }
                if (locales.contains("zh-CN")) {
                    xml.append("<xhtml:link rel=\"alternate\" hreflang=\"x-default\" href=\"")
                            .append(xml(base + "/zh-CN/" + domain)).append("\"/>");
                }
                xml.append("</url>\n");
            }
        });
        // Home pages (/{locale}); the root / redirects to /zh-CN on the public host and is not listed.
        appendLocaleSet(xml, base, locale -> "/" + locale);
        FAMILY_VISA_TOPICS.forEach((topic, guide) -> {
            GuideResponse.Metadata source = guides.getOrDefault(guide, Map.of()).get("zh-CN");
            if (source != null && "family-visa".equals(domainPath(source.category())))
                appendLocaleSet(xml, base, locale -> "/" + locale + "/family-visa/topics/" + topic);
        });
        xml.append("</urlset>\n");
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).contentType(MediaType.APPLICATION_XML).body(xml.toString());
    }

    @GetMapping(value = "/robots.txt", produces = MediaType.TEXT_PLAIN_VALUE)
    ResponseEntity<String> robots() {
        URI origin = publicOrigin();
        if (origin == null) return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).cacheControl(CacheControl.noStore()).body("");
        String base = origin.toString().replaceAll("/$", "");
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).contentType(MediaType.TEXT_PLAIN)
                .body("User-agent: *\nAllow: /\nSitemap: " + base + "/sitemap.xml\n");
    }

    /** One entry per locale for a page that exists in all five, each listing the full alternate set and x-default (zh-CN). */
    private static void appendLocaleSet(StringBuilder xml, String base, java.util.function.Function<String, String> path) {
        for (String locale : LOCALES) {
            xml.append("  <url><loc>").append(xml(base + path.apply(locale))).append("</loc>");
            for (String available : LOCALES) {
                xml.append("<xhtml:link rel=\"alternate\" hreflang=\"").append(hreflang(available))
                        .append("\" href=\"").append(xml(base + path.apply(available))).append("\"/>");
            }
            xml.append("<xhtml:link rel=\"alternate\" hreflang=\"x-default\" href=\"")
                    .append(xml(base + path.apply("zh-CN"))).append("\"/>");
            xml.append("</url>\n");
        }
    }

    private URI publicOrigin() {
        try {
            URI uri = URI.create(publicBaseUrl);
            boolean localHttp = "http".equals(uri.getScheme()) && ("localhost".equals(uri.getHost()) || "127.0.0.1".equals(uri.getHost()));
            if ((!"https".equals(uri.getScheme()) && !localHttp) || uri.getHost() == null || uri.getUserInfo() != null
                    || (uri.getPath() != null && !uri.getPath().isEmpty() && !"/".equals(uri.getPath()))
                    || uri.getQuery() != null || uri.getFragment() != null) return null;
            return uri;
        } catch (IllegalArgumentException invalid) { return null; }
    }

    private static String domainPath(String category) {
        return switch (category) {
            case "health-nhs" -> "health";
            case "family-visa" -> "family-visa";
            case "work-visa" -> "work-visa";
            case "settlement" -> "settlement";
            case "money" -> "money";
            case "renting" -> "renting";
            case "university-applications-student-visas" -> "university-applications-student-visas";
            case "education" -> "education";
            default -> null;
        };
    }

    private static String hreflang(String locale) {
        return "en".equals(locale) ? "en-GB" : locale;
    }

    private static String xml(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&apos;");
    }
}
