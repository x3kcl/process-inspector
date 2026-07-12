package io.inspector.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import io.inspector.security.mapping.SecurityAlertChannel;
import java.time.Clock;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.BadCredentialsException;

/**
 * Rung 2: break-glass failure handling (S4). Every failure is counted; a sustained burst alerts
 * (once per threshold, not every attempt); the response is a generic 401 until the account enters
 * cooldown, then 429 + Retry-After — the same generic text either way (no account enumeration).
 */
class BreakGlassFailureHandlerTest {

    private final BreakGlassThrottle throttle =
            new BreakGlassThrottle(Clock.fixed(java.time.Instant.parse("2026-07-12T00:00:00Z"), ZoneOffset.UTC));
    private final SecurityAlertChannel alert = mock(SecurityAlertChannel.class);
    private final BreakGlassFailureHandler handler = new BreakGlassFailureHandler(throttle, alert, "break-glass");

    private MockHttpServletResponse fail() throws Exception {
        MockHttpServletResponse res = new MockHttpServletResponse();
        handler.onAuthenticationFailure(new MockHttpServletRequest(), res, new BadCredentialsException("bad"));
        return res;
    }

    @Test
    void firstFailuresReturn401ThenCooldownReturns429WithRetryAfter() throws Exception {
        assertThat(fail().getStatus()).isEqualTo(401); // 1st — free
        assertThat(fail().getStatus()).isEqualTo(401); // 2nd — free
        MockHttpServletResponse third = fail(); // 3rd — enters cooldown
        assertThat(third.getStatus()).isEqualTo(429);
        assertThat(third.getHeader("Retry-After")).isEqualTo("1");
    }

    @Test
    void alertsOnceAtTheThresholdNotOnEveryFailure() throws Exception {
        for (int i = 0; i < 4; i++) {
            fail();
        }
        verify(alert, never())
                .fire(
                        org.mockito.ArgumentMatchers.eq("break-glass-brute-force"),
                        org.mockito.ArgumentMatchers.anyString());

        fail(); // 5th → the alert threshold
        ArgumentCaptor<String> detail = ArgumentCaptor.forClass(String.class);
        verify(alert, times(1)).fire(org.mockito.ArgumentMatchers.eq("break-glass-brute-force"), detail.capture());
        assertThat(detail.getValue()).contains("5 consecutive");
    }
}
