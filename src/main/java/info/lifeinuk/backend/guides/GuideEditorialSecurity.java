package info.lifeinuk.backend.guides;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
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
            @Value("${life-in-uk.editorial.approval-token:}") String approval) {
        if (sameNonblank(draft, review) || sameNonblank(draft, approval) || sameNonblank(review, approval)) {
            throw new IllegalArgumentException("Editorial draft, review and approval tokens must be distinct");
        }
        gate = new Gate(draft, review, approval);
    }

    private static boolean sameNonblank(String left, String right) {
        return left != null && !left.isBlank() && left.equals(right);
    }

    @Override public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(gate).addPathPatterns("/api/editorial/**");
    }

    static final class Gate implements HandlerInterceptor {
        private final byte[] draft;
        private final byte[] review;
        private final byte[] approval;
        Gate(String draft, String review, String approval) {
            this.draft = bytes(draft); this.review = bytes(review); this.approval = bytes(approval);
        }

        @Override public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
            String path = request.getRequestURI();
            byte[] expected = path.endsWith("/approve") || path.endsWith("/publish") ? approval
                    : path.contains("/reviews/") ? review : draft;
            if (expected.length == 0) {
                response.setStatus(HttpStatus.SERVICE_UNAVAILABLE.value());
                response.setHeader("Cache-Control", "no-store");
                response.getWriter().write("{\"code\":\"EDITORIAL_AUTH_NOT_CONFIGURED\"}");
                return false;
            }
            String authorization = request.getHeader("Authorization");
            byte[] supplied = authorization != null && authorization.startsWith("Bearer ")
                    ? bytes(authorization.substring(7)) : new byte[0];
            if (!MessageDigest.isEqual(expected, supplied)) {
                response.setStatus(HttpStatus.UNAUTHORIZED.value());
                response.setHeader("Cache-Control", "no-store");
                response.getWriter().write("{\"code\":\"EDITORIAL_UNAUTHORIZED\"}");
                return false;
            }
            return true;
        }

        private static byte[] bytes(String value) {
            return value == null ? new byte[0] : value.getBytes(StandardCharsets.UTF_8);
        }
    }
}
