package com.example.agent.tools;

import com.example.agent.config.AgentProperties;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Property-backed {@link CredentialResolver}:
 *
 * <pre>
 * agent.credentials.per-user.cistern.alice: ${ALICE_CISTERN_TOKEN}
 * agent.credentials.per-user.openai.alice:  ${ALICE_GATEWAY_KEY}
 * </pre>
 *
 * Plain-text properties are the v1 seam, not the end state — production
 * deployments should feed these from a secret manager. The interface is the
 * contract; swap the implementation without touching tools.
 *
 * <p>User IDs are matched exactly as the map key was bound; see
 * {@link AgentProperties.Credentials} for how the environment form cases them.
 */
@Component
public class ConfiguredCredentialResolver implements CredentialResolver {

    private final AgentProperties props;

    public ConfiguredCredentialResolver(AgentProperties props) {
        this.props = props;
    }

    @Override
    public String resolve(String service, String userId) {
        if (service == null || userId == null) return null;
        Map<String, String> forService = props.getCredentials().getPerUser().get(service);
        if (forService == null) return null;
        String token = forService.get(userId);
        return (token == null || token.isBlank()) ? null : token;
    }
}
