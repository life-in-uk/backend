package info.lifeinuk.backend.guides;

import java.util.List;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class GuideController {
    private final GuideQuery query;
    GuideController(GuideQuery query) { this.query = query; }

    // Withdrawal takes effect at commit, so Guide responses must not be cached anywhere.
    @GetMapping("/api/guides")
    public ResponseEntity<List<GuideResponse.Metadata>> list() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(query.list());
    }

    @GetMapping("/api/guides/{slug}")
    public ResponseEntity<?> detail(@PathVariable("slug") String slug) {
        return query.detail(slug).<ResponseEntity<?>>map(detail -> ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(detail))
                .orElseGet(() -> ResponseEntity.status(404).cacheControl(CacheControl.noStore())
                        .body(new Error("GUIDE_NOT_FOUND", "Guide not found.")));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Error> failedRead() {
        return ResponseEntity.internalServerError().cacheControl(CacheControl.noStore())
                .body(new Error("GUIDES_READ_FAILED", "Guides could not be read."));
    }
    public record Error(String code, String message) { }
}
