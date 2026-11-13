package info.lifeinuk.backend.guides;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

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
        assertThrows(GuideOwnerEditorialController.OwnerCsrfRejected.class, () -> controller.logout(request, "wrong"));
        assertEquals(204, controller.logout(request, csrf).getStatusCode().value());
        assertNull(request.getSession(false));
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
