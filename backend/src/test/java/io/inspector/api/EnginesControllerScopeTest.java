package io.inspector.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.inspector.config.InspectorProperties;
import io.inspector.config.InspectorProperties.Auth;
import io.inspector.config.InspectorProperties.EngineConfig;
import io.inspector.dto.EngineDto;
import io.inspector.registry.EngineRegistry;
import io.inspector.registry.EngineSystemInfoService;
import io.inspector.security.RbacAuthorizer;
import io.inspector.security.ReadScopeGate;
import io.inspector.security.Role;
import io.inspector.support.TestEngines;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;

/**
 * Rung 1 (#740): the engines controller's scope decisions with the gate and the authorizer as
 * seams. The wire contract (status, refusal body) is {@code EngineListScopeFactsTest}'s job.
 *
 * <p>An OIDC session that is ADMIN on ONE engine carries the engine-agnostic {@code ROLE_ADMIN}
 * authority ({@code InspectorAuthoritiesMapper}); under enforcement the full system read must ask
 * "ADMIN on THIS engine", not "ROLE_ADMIN anywhere".
 */
class EnginesControllerScopeTest {

    private final ReadScopeGate gate = mock(ReadScopeGate.class);
    private final RbacAuthorizer rbac = mock(RbacAuthorizer.class);
    private final EngineSystemInfoService systemInfo = mock(EngineSystemInfoService.class);
    private final Authentication adminOfA =
            new UsernamePasswordAuthenticationToken("ann", "n/a", AuthorityUtils.createAuthorityList("ROLE_ADMIN"));

    private static EngineConfig engine(String id, boolean enabled) {
        return TestEngines.builder(id, "http://" + id)
                .enabled(enabled)
                .auth(Auth.none())
                .build();
    }

    private final EnginesController controller = new EnginesController(
            new EngineRegistry(new InspectorProperties(
                    4,
                    10,
                    null,
                    null,
                    List.of(engine("engine-a", true), engine("engine-b", true), engine("engine-d", false)))),
            systemInfo,
            gate,
            rbac);

    @Test
    void enforcedListKeepsOnlyReadableEnginesIncludingADisabledOneInScope() {
        when(gate.enforced()).thenReturn(true);
        when(gate.canRead(adminOfA, "engine-a")).thenReturn(true);
        when(gate.canRead(adminOfA, "engine-d")).thenReturn(true);

        List<EngineDto> list = controller.list(adminOfA);

        assertThat(list).extracting(EngineDto::id).containsExactly("engine-a", "engine-d");
    }

    @Test
    void enforcedSystemReadOfAnUnreadableEngineIsRefusedBeforeAnyEngineCall() {
        when(gate.enforced()).thenReturn(true);
        when(gate.canRead(adminOfA, "engine-b")).thenReturn(false);

        assertThatThrownBy(() -> controller.system("engine-b", adminOfA)).isInstanceOf(AccessDeniedException.class);
        verify(systemInfo, never()).load(anyString(), anyBoolean());
    }

    @Test
    void enforcedFullSystemReadNeedsAdminOnThatEngineNotRoleAdminAnywhere() {
        when(gate.enforced()).thenReturn(true);
        when(gate.canRead(adminOfA, "engine-b")).thenReturn(true); // VIEWER on B, ADMIN on A only
        when(rbac.hasRoleOn(adminOfA, Role.ADMIN, "engine-b")).thenReturn(false);

        controller.system("engine-b", adminOfA);

        verify(systemInfo).load("engine-b", false);
    }

    @Test
    void enforcedAdminOnThatEngineGetsTheFullRead() {
        when(gate.enforced()).thenReturn(true);
        when(gate.canRead(adminOfA, "engine-a")).thenReturn(true);
        when(rbac.hasRoleOn(adminOfA, Role.ADMIN, "engine-a")).thenReturn(true);

        controller.system("engine-a", adminOfA);

        verify(systemInfo).load("engine-a", true);
    }

    @Test
    void unenforcedKeepsTheFullListButTheFullSystemReadNeedsAdminOnThatEngine() {
        // #747 item 4: ROLE_ADMIN from engine A no longer opens engine B's full system read, even off.
        when(gate.enforced()).thenReturn(false);

        controller.system("engine-b", adminOfA);

        verify(systemInfo).load("engine-b", false);
        assertThat(controller.list(adminOfA))
                .extracting(EngineDto::id)
                .containsExactly("engine-a", "engine-b", "engine-d");
    }
}
