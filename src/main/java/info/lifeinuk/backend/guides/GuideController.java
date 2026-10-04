package info.lifeinuk.backend.guides;

import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class GuideController {
    private final GuideQuery query;
    GuideController(GuideQuery query) { this.query = query; }

    @GetMapping("/api/guides")
    public List<GuideResponse.Metadata> list() { return query.list(); }

    @GetMapping("/api/guides/{slug}")
    public ResponseEntity<?> detail(@PathVariable("slug") String slug) {
        return query.detail(slug).<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(404).body(new Error("GUIDE_NOT_FOUND", "Guide not found.")));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Error> failedRead() {
        return ResponseEntity.internalServerError().body(new Error("GUIDES_READ_FAILED", "Guides could not be read."));
    }
    public record Error(String code, String message) { }
}
