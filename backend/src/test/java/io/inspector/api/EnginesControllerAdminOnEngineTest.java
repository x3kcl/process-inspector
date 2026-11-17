package io.inspector.api;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.inspector.config.InspectorProperties;
import io.inspector.config.InspectorProperties.Auth;
import io.inspector.config.InspectorProperties.EngineConfig;
import io.inspector.registry.EngineRegistry;
import io.inspector.registry.EngineSystemInfoService;
import io.inspector.security.RbacAuthorizer;
import io.inspector.security.ReadScopeGate;
import io.inspector.security.Role;
import io.inspector.support.TestEngines;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;

/**
 * #747 item 4 (R-SAFE-17): the FULL system read (properties, table counts) is an admin surface, so it
 * needs ADMIN on THAT engine whether or not read-scope enforcement is on. With enforcement off an
 * OIDC session that is ADMIN on engine A carries the engine-agnostic {@code ROLE_ADMIN} authority,
 * which used to open every engine's full system info. The version line stays readable to every
 * caller with enforcement off (unchanged), and {@code ROLE_REGISTRY_ADMIN} keeps the full read.
 */
class EnginesControllerAdminOnEngineTest {

    private final ReadScopeGate gate = mock(ReadScopeGate.class);
    private final RbacAuthorizer rbac = mock(RbacAuthorizer.class);
    private final EngineSystemInfoService systemInfo = mock(EngineSystemInfoService.class);
    private final Authentication adminOfA =
            new UsernamePasswordAuthenticationToken("ann", "n/a", AuthorityUtils.createAuthorityList("ROLE_ADMIN"));

    private static EngineConfig engine(String id) {
        return TestEngines.builder(id, "http://" + id).auth(Auth.none()).build();
    }

    private final EnginesController controller = new EnginesController(
            new EngineRegistry(
                    new InspectorProperties(4, 10, null, null, List.of(engine("engine-a"), engine("engine-b")))),
            systemInfo,
            gate,
            rbac);

    @BeforeEach
    void enforcementOff() {
        when(gate.enforced()).thenReturn(false);
        when(rbac.hasRoleOn(adminOfA, Role.ADMIN, "engine-a")).thenReturn(true);
        when(rbac.hasRoleOn(adminOfA, Role.ADMIN, "engine-b")).thenReturn(false);
    }

    @Test
    void unenforcedAdminOfAnotherEngineGetsOnlyTheVersionLine() {
        controller.system("engine-b", adminOfA);

        verify(systemInfo).load("engine-b", false);
    }

    @Test
    void unenforcedAdminOnThatEngineGetsTheFullRead() {
        controller.system("engine-a", adminOfA);

        verify(systemInfo).load("engine-a", true);
    }
}
