package info.lifeinuk.backend.guides;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.concurrent.ConcurrentHashMap;
import java.util.UUID;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/** Browser session facade. The existing bearer-protected machine API remains unchanged. */
@RestController
@RequestMapping("/api/owner/editorial")
class GuideOwnerEditorialController {
    private static final String ROLE = GuideOwnerEditorialController.class.getName() + ".role";
    private static final String CSRF = GuideOwnerEditorialController.class.getName() + ".csrf";
    private static final SecureRandom RANDOM = new SecureRandom();
    private final String draftPassword;
    private final String approvalPassword;
    private final String allowedOrigins;
    private final String approvalActor;
    private final GuideContentBatchService batches;
    private final ConcurrentHashMap<String, LoginWindow> attempts = new ConcurrentHashMap<>();

    GuideOwnerEditorialController(
            @Value("${life-in-uk.editorial.owner-draft-password:}") String draftPassword,
            @Value("${life-in-uk.editorial.owner-approval-password:}") String approvalPassword,
            @Value("${life-in-uk.editorial.draft-token:}") String machineDraftToken,
            @Value("${life-in-uk.editorial.approval-token:}") String machineApprovalToken,
            @Value("${life-in-uk.editorial.approval-actor:}") String approvalActor,
            @Value("${life-in-uk.editorial.owner-origins:http://127.0.0.1:5173,http://localhost:5173,http://127.0.0.1:5179,http://localhost:5179}") String allowedOrigins,
            GuideContentBatchService batches) {
        validatePassword(draftPassword, machineDraftToken, approvalPassword);
        validatePassword(approvalPassword, machineApprovalToken, draftPassword);
        this.draftPassword = draftPassword;
        this.approvalPassword = approvalPassword;
        this.approvalActor = approvalActor;
        this.allowedOrigins = allowedOrigins;
        this.batches = batches;
    }

    private static void validatePassword(String password, String machineCredential, String otherOwnerPassword) {
        if (password == null || password.isBlank()) return;
        if (password.length() < 32 || password.equals(machineCredential) || password.equals(otherOwnerPassword))
            throw new IllegalArgumentException("Owner session passwords must be distinct from each other and machine API credentials, and at least 32 characters");
    }

    @PostMapping("/session")
    ResponseEntity<?> login(@RequestBody LoginRequest body, HttpServletRequest request) {
        if (!allowedOrigin(request)) return failure(HttpStatus.FORBIDDEN, "OWNER_ORIGIN_REJECTED");
        String address = request.getRemoteAddr();
        LoginWindow window = attempts.compute(address, (key, current) -> current == null || current.until().isBefore(Instant.now())
                ? new LoginWindow(0, Instant.now().plusSeconds(900)) : current);
        if (window.count() >= 5) return failure(HttpStatus.TOO_MANY_REQUESTS, "OWNER_LOGIN_RATE_LIMITED");
        if (body == null || body.role() == null || body.credential() == null || body.credential().length() > 512)
            return failure(HttpStatus.BAD_REQUEST, "INVALID_OWNER_LOGIN");
        String expected = switch (body.role()) {
            case "DRAFT" -> draftPassword;
            case "APPROVAL" -> approvalPassword;
            default -> "";
        };
        if (expected.isBlank()) return failure(HttpStatus.SERVICE_UNAVAILABLE, "OWNER_ROLE_NOT_CONFIGURED");
        if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), body.credential().getBytes(StandardCharsets.UTF_8))) {
            attempts.compute(address, (key, current) -> new LoginWindow((current == null ? 0 : current.count()) + 1,
                    current == null ? Instant.now().plusSeconds(900) : current.until()));
            return failure(HttpStatus.UNAUTHORIZED, "OWNER_LOGIN_FAILED");
        }
        attempts.remove(address);
        HttpSession session = request.getSession(true);
        request.changeSessionId();
        String csrf = randomToken();
        session.setAttribute(ROLE, body.role());
        session.setAttribute(CSRF, csrf);
        session.setMaxInactiveInterval(1800);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new SessionView(body.role(), csrf));
    }

    @GetMapping("/session")
    ResponseEntity<?> session(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null || session.getAttribute(ROLE) == null) return failure(HttpStatus.UNAUTHORIZED, "OWNER_SESSION_REQUIRED");
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new SessionView((String) session.getAttribute(ROLE), (String) session.getAttribute(CSRF)));
    }

    @DeleteMapping("/session")
    ResponseEntity<?> logout(HttpServletRequest request, @RequestHeader(value = "X-CSRF-Token", required = false) String csrf) {
        HttpSession session = requireSession(request, "DRAFT", "APPROVAL");
        requireCsrf(session, csrf);
        session.invalidate();
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    @GetMapping("/batches")
    ResponseEntity<?> list(HttpServletRequest request) {
        requireSession(request, "DRAFT", "APPROVAL");
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(batches.recentBatches());
    }

    @GetMapping("/batches/{batchId}")
    ResponseEntity<?> detail(@PathVariable UUID batchId, HttpServletRequest request) {
        requireSession(request, "DRAFT", "APPROVAL");
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(batches.ownerDetail(batchId));
    }

    @PostMapping("/batches/{batchId}/approve")
    ResponseEntity<?> approve(@PathVariable UUID batchId, HttpServletRequest request,
            @RequestHeader(value = "X-CSRF-Token", required = false) String csrf) {
        HttpSession session = requireSession(request, "APPROVAL");
        requireCsrf(session, csrf);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(batches.approve(batchId, approvalActor));
    }

    @PostMapping("/batches/{batchId}/publish")
    ResponseEntity<?> publish(@PathVariable UUID batchId, @RequestBody GuideContentBatchService.PublishRequest body,
            HttpServletRequest request, @RequestHeader(value = "X-CSRF-Token", required = false) String csrf) {
        HttpSession session = requireSession(request, "APPROVAL");
        requireCsrf(session, csrf);
        if (body == null) return failure(HttpStatus.BAD_REQUEST, "INVALID_PUBLISH_REQUEST");
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(batches.publish(batchId, body, approvalActor));
    }

    @PostMapping("/batches")
    ResponseEntity<?> create(@RequestBody JsonNode body, HttpServletRequest request,
            @RequestHeader(value = "X-CSRF-Token", required = false) String csrf) {
        HttpSession session = requireSession(request, "DRAFT");
        requireCsrf(session, csrf);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(batches.create(body));
    }

    @PostMapping("/batches/{batchId}/locales/{locale}")
    ResponseEntity<?> translation(@PathVariable UUID batchId, @PathVariable String locale, @RequestBody byte[] body,
            HttpServletRequest request, @RequestHeader(value = "X-CSRF-Token", required = false) String csrf) {
        HttpSession session = requireSession(request, "DRAFT");
        requireCsrf(session, csrf);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(batches.translate(batchId, locale, body));
    }

    private HttpSession requireSession(HttpServletRequest request, String... roles) {
        HttpSession session = request.getSession(false);
        if (session == null || java.util.Arrays.stream(roles).noneMatch(role -> role.equals(session.getAttribute(ROLE))))
            throw new OwnerUnauthorized();
        return session;
    }

    private static void requireCsrf(HttpSession session, String supplied) {
        Object expected = session.getAttribute(CSRF);
        if (!(expected instanceof String value) || supplied == null || !MessageDigest.isEqual(
                value.getBytes(StandardCharsets.UTF_8), supplied.getBytes(StandardCharsets.UTF_8))) throw new OwnerCsrfRejected();
    }

    private boolean allowedOrigin(HttpServletRequest request) {
        String origin = request.getHeader("Origin");
        return origin != null && java.util.Arrays.stream(allowedOrigins.split(","))
                .map(String::trim).anyMatch(origin::equals);
    }

    private static String randomToken() {
        byte[] bytes = new byte[32]; RANDOM.nextBytes(bytes); return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static ResponseEntity<Error> failure(HttpStatus status, String code) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(new Error(code));
    }

    @org.springframework.web.bind.annotation.ExceptionHandler(OwnerUnauthorized.class)
    ResponseEntity<Error> unauthorized() { return failure(HttpStatus.UNAUTHORIZED, "OWNER_SESSION_REQUIRED"); }
    @org.springframework.web.bind.annotation.ExceptionHandler(OwnerCsrfRejected.class)
    ResponseEntity<Error> csrfRejected() { return failure(HttpStatus.FORBIDDEN, "OWNER_CSRF_REJECTED"); }

    record LoginRequest(String role, String credential) { }
    record SessionView(String role, String csrfToken) { }
    record Error(String code) { }
    record LoginWindow(int count, Instant until) { }
    static final class OwnerUnauthorized extends RuntimeException { }
    static final class OwnerCsrfRejected extends RuntimeException { }
}
