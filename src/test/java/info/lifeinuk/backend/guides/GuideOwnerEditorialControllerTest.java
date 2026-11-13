package info.lifeinuk.backend.guides;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

class GuideOwnerEditorialControllerTest {
    @Test
    void draftAndApprovalPasswordsCreateDifferentThirtyMinuteSessions() {
        var controller = controller();
        var draftRequest = request();
        String draftPassword = "draft-session-password-0123456789abcdef";
        var draft = controller.login(new GuideOwnerEditorialController.LoginRequest("DRAFT", draftPassword), draftRequest);
        assertEquals(200, draft.getStatusCode().value());
        var draftView = (GuideOwnerEditorialController.SessionView) draft.getBody();
        assertEquals("DRAFT", draftView.role());
        assertFalse(draftView.csrfToken().contains(draftPassword));
        assertEquals(1800, draftRequest.getSession(false).getMaxInactiveInterval());
        assertThrows(GuideOwnerEditorialController.OwnerUnauthorized.class,
                () -> controller.approve(java.util.UUID.randomUUID(), draftRequest, draftView.csrfToken()));

        var approvalRequest = request();
        var approval = controller.login(new GuideOwnerEditorialController.LoginRequest("APPROVAL", "approval-session-password-0123456789abcdef"), approvalRequest);
        assertEquals(200, approval.getStatusCode().value());
        assertEquals("APPROVAL", ((GuideOwnerEditorialController.SessionView) approval.getBody()).role());
        assertThrows(GuideOwnerEditorialController.OwnerUnauthorized.class,
                () -> controller.create(null, approvalRequest, ((GuideOwnerEditorialController.SessionView) approval.getBody()).csrfToken()));
    }

    @Test
    void loginRequiresConfiguredOriginAndWrongPasswordNeverCreatesSession() {
        var controller = controller();
        var request = new MockHttpServletRequest();
        request.addHeader("Origin", "https://attacker.example");
        assertEquals(403, controller.login(new GuideOwnerEditorialController.LoginRequest("APPROVAL", "approval-session-password-0123456789abcdef"), request)
                .getStatusCode().value());
        var sameOrigin = request();
        assertEquals(401, controller.login(new GuideOwnerEditorialController.LoginRequest("APPROVAL", "wrong"), sameOrigin)
                .getStatusCode().value());
        assertNull(sameOrigin.getSession(false));
    }

    @Test
    void mutationsRequireSynchronizerCsrfTokenAndLogoutExpiresSession() {
        var controller = controller();
        var request = request();
        var response = controller.login(new GuideOwnerEditorialController.LoginRequest("DRAFT", "draft-session-password-0123456789abcdef"), request);
        String csrf = ((GuideOwnerEditorialController.SessionView) response.getBody()).csrfToken();
        assertThrows(GuideOwnerEditorialController.OwnerCsrfRejected.class,
                () -> controller.create(null, request, "wrong"));
        assertThrows(GuideOwnerEditorialController.OwnerCsrfRejected.class, () -> controller.logout(request, "wrong"));
        assertEquals(204, controller.logout(request, csrf).getStatusCode().value());
        assertNull(request.getSession(false));
    }

    @Test
    void failedLoginsAreAtomicallyLimitedByRoleAndSocketPeerAddress() throws Exception {
        var controller = controller();
        int attempts = 32;
        var pool = Executors.newFixedThreadPool(attempts);
        var ready = new CountDownLatch(attempts);
        var start = new CountDownLatch(1);
        try {
            var futures = new ArrayList<java.util.concurrent.Future<Integer>>();
            for (int i = 0; i < attempts; i++) {
                int forwarded = i;
                futures.add(pool.submit(() -> {
                    var request = request();
                    request.addHeader("X-Forwarded-For", "198.51.100." + forwarded);
                    ready.countDown();
                    start.await();
                    return controller.login(new GuideOwnerEditorialController.LoginRequest("DRAFT", "wrong"), request)
                            .getStatusCode().value();
                }));
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            var statuses = new ArrayList<Integer>();
            for (var future : futures) statuses.add(future.get(5, TimeUnit.SECONDS));
            assertEquals(5, statuses.stream().filter(status -> status == 401).count());
            assertEquals(attempts - 5, statuses.stream().filter(status -> status == 429).count());

            // Same socket peer but another role has its own failed-attempt bucket.
            var approval = controller.login(new GuideOwnerEditorialController.LoginRequest("APPROVAL", "wrong"), request());
            assertEquals(401, approval.getStatusCode().value());
            // Spoofing a different forwarded IP did not evade the exhausted DRAFT bucket.
            var draft = request(); draft.addHeader("X-Forwarded-For", "203.0.113.9");
            assertEquals(429, controller.login(new GuideOwnerEditorialController.LoginRequest("DRAFT", "wrong"), draft)
                    .getStatusCode().value());
        } finally {
            pool.shutdownNow();
        }
    }

    private static GuideOwnerEditorialController controller() {
        return new GuideOwnerEditorialController("draft-session-password-0123456789abcdef", "approval-session-password-0123456789abcdef",
                "machine-draft-token-not-used-here", "machine-approval-token-not-used-here", "owner",
                "http://127.0.0.1:5179", null);
    }

    private static MockHttpServletRequest request() {
        var request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");
        request.addHeader("Origin", "http://127.0.0.1:5179");
        return request;
    }
}
