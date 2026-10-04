package info.lifeinuk.backend.guides;

import java.util.HashSet;
import java.util.Set;
import org.commonmark.node.AbstractVisitor;
import org.commonmark.node.Link;
import org.commonmark.parser.Parser;

/** Only CommonMark links reserve this fragment namespace. Code and raw HTML are not references. */
final class GuideEvidenceReferences {
    private static final String PREFIX = "#guide-evidence-";
    private GuideEvidenceReferences() { }
    static void validate(String markdown, Set<String> defined) {
        var used = new HashSet<String>();
        Parser.builder().build().parse(markdown).accept(new AbstractVisitor() {
            @Override public void visit(Link link) {
                String destination = link.getDestination();
                if (destination.startsWith(PREFIX)) {
                    String key = destination.substring(PREFIX.length());
                    try { Guide.key(key, 160); }
                    catch (IllegalArgumentException invalid) { throw new IllegalArgumentException("Malformed evidence reference: " + destination); }
                    if (!defined.contains(key)) { throw new IllegalArgumentException("Unknown evidence reference: " + key); }
                    used.add(key);
                }
                visitChildren(link);
            }
        });
        var unused = new java.util.TreeSet<>(defined); unused.removeAll(used);
        if (!unused.isEmpty()) { throw new IllegalArgumentException("Unused evidence definitions: " + unused); }
    }
}
