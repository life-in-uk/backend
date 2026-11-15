package info.lifeinuk.backend.guides;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.HttpStatus;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/** Separate bearer credentials for draft ingestion, independent review, and human approval/publication. */
@Configuration
class GuideEditorialSecurity implements WebMvcConfigurer {
    private final Gate gate;

    GuideEditorialSecurity(@Value("${life-in-uk.editorial.draft-token:}") String draft,
            @Value("${life-in-uk.editorial.review-token:}") String review,
            @Value("${life-in-uk.editorial.mock-review-token:}") String mockReview,
            @Value("${life-in-uk.editorial.approval-token:}") String approval,
            @Value("${life-in-uk.editorial.local-review-token:}") String localReview,
            Environment environment) {
        if (sameNonblank(draft, review) || sameNonblank(draft, mockReview) || sameNonblank(draft, approval)
                || sameNonblank(draft, localReview) || sameNonblank(review, mockReview) || sameNonblank(review, approval)
                || sameNonblank(review, localReview) || sameNonblank(mockReview, approval)
                || sameNonblank(mockReview, localReview) || sameNonblank(approval, localReview)) {
            throw new IllegalArgumentException("Editorial draft, review and approval tokens must be distinct");
        }
        if (localReview != null && !localReview.isBlank() && !environment.acceptsProfiles(Profiles.of("local"))) {
            throw new IllegalArgumentException("The Codex local review credential is only allowed with the local Spring profile");
        }
        gate = new Gate(draft, review, mockReview, approval, localReview);
    }

    String authenticatedReviewer(HttpServletRequest request) { return gate.authenticatedReviewer(request); }

    private static boolean sameNonblank(String left, String right) {
        return left != null && !left.isBlank() && left.equals(right);
    }

    @Override public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(gate).addPathPatterns("/api/editorial/**");
    }

    static final class Gate implements HandlerInterceptor {
        private final byte[] draft;
        private final byte[] review;
        private final byte[] mockReview;
        private final byte[] approval;
        private final byte[] localReview;
        Gate(String draft, String review, String mockReview, String approval, String localReview) {
            this.draft = bytes(draft); this.review = bytes(review); this.mockReview = bytes(mockReview);
            this.approval = bytes(approval); this.localReview = bytes(localReview);
        }

        @Override public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
            CredentialClass credentialClass = credentialClass(handler);
            if (credentialClass == CredentialClass.UNRECOGNIZED) {
                response.setStatus(HttpStatus.FORBIDDEN.value());
                response.setHeader("Cache-Control", "no-store");
                response.getWriter().write("{\"code\":\"EDITORIAL_ROUTE_NOT_AUTHORIZED\"}");
                return false;
            }
            byte[] expected = switch (credentialClass) {
                case DRAFT -> draft;
                case REVIEW -> review;
                case APPROVAL -> approval;
                case UNRECOGNIZED -> new byte[0];
            };
            if (expected.length == 0 && !(credentialClass == CredentialClass.REVIEW
                    && (mockReview.length > 0 || localReview.length > 0))) {
                response.setStatus(HttpStatus.SERVICE_UNAVAILABLE.value());
                response.setHeader("Cache-Control", "no-store");
                response.getWriter().write("{\"code\":\"EDITORIAL_AUTH_NOT_CONFIGURED\"}");
                return false;
            }
            String authorization = request.getHeader("Authorization");
            byte[] supplied = authorization != null && authorization.startsWith("Bearer ")
                    ? bytes(authorization.substring(7)) : new byte[0];
            boolean valid = expected.length > 0 && MessageDigest.isEqual(expected, supplied)
                    || credentialClass == CredentialClass.REVIEW && mockReview.length > 0
                    && MessageDigest.isEqual(mockReview, supplied)
                    || credentialClass == CredentialClass.REVIEW && localReview.length > 0
                    && MessageDigest.isEqual(localReview, supplied);
            if (!valid) {
                response.setStatus(HttpStatus.UNAUTHORIZED.value());
                response.setHeader("Cache-Control", "no-store");
                response.getWriter().write("{\"code\":\"EDITORIAL_UNAUTHORIZED\"}");
                return false;
            }
            return true;
        }

        private static CredentialClass credentialClass(Object handler) {
            if (!(handler instanceof HandlerMethod method)
                    || method.getBeanType() != GuideEditorialController.class) return CredentialClass.UNRECOGNIZED;
            return switch (method.getMethod().getName()) {
                case "review" -> CredentialClass.REVIEW;
                case "approve", "publish" -> CredentialClass.APPROVAL;
                case "create", "get", "expiryReport", "locale", "failedLocale" -> CredentialClass.DRAFT;
                default -> CredentialClass.UNRECOGNIZED;
            };
        }

        String authenticatedReviewer(HttpServletRequest request) {
            String authorization = request.getHeader("Authorization");
            byte[] supplied = authorization != null && authorization.startsWith("Bearer ")
                    ? bytes(authorization.substring(7)) : new byte[0];
            if (mockReview.length > 0 && MessageDigest.isEqual(mockReview, supplied)) return "mock-adapter";
            if (localReview.length > 0 && MessageDigest.isEqual(localReview, supplied)) return "codex-local-review";
            if (review.length > 0 && MessageDigest.isEqual(review, supplied)) return "claude-independent";
            throw new IllegalStateException("No authenticated review identity");
        }

        private static byte[] bytes(String value) {
            return value == null ? new byte[0] : value.getBytes(StandardCharsets.UTF_8);
        }

        private enum CredentialClass { DRAFT, REVIEW, APPROVAL, UNRECOGNIZED }
    }
}
