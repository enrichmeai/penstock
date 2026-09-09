package com.example.agent.config;

import com.example.agent.tools.CredentialMode;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Environment variables bind through Spring Boot's relaxed rules. For a bean property the
 * mapper tries two spellings — the canonical one with hyphens removed
 * ({@code AGENT_TOOLS_CISTERN_CREDENTIALMODE}) and a legacy one with hyphens turned into
 * underscores ({@code AGENT_TOOLS_CISTERN_CREDENTIAL_MODE}) — so both bind. For a
 * <em>map</em>, the source's names are enumerated and read back the other way, so only the
 * hyphen-removed prefix matches ({@code AGENT_CREDENTIALS_PERUSER_...});
 * {@code AGENT_CREDENTIALS_PER_USER_...} binds nothing, silently, and the map keys arrive
 * lower-cased. These tests pin the exact names the demo and the README use, and the
 * near-misses, so the documentation stays a description of measured behaviour.
 *
 * <p>The environment is simulated the way Boot reads it — a
 * {@link SystemEnvironmentPropertySource} named {@code systemEnvironment} — so the same
 * {@code SystemEnvironmentPropertyMapper} that handles real {@code os.environ} entries
 * handles these.
 */
class RelaxedEnvBindingTest {

    @Configuration
    @EnableConfigurationProperties(AgentProperties.class)
    static class Cfg {}

    private static ApplicationContextRunner withEnvironment(Map<String, String> env) {
        return new ApplicationContextRunner()
                .withInitializer(ctx -> {
                    MutablePropertySources sources = ctx.getEnvironment().getPropertySources();
                    sources.remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
                    sources.addFirst(new SystemEnvironmentPropertySource(
                            StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, new HashMap<>(env)));
                })
                .withUserConfiguration(Cfg.class);
    }

    private static void bound(Map<String, String> env, Consumer<AgentProperties> assertions) {
        withEnvironment(env).run(ctx -> assertions.accept(ctx.getBean(AgentProperties.class)));
    }

    // ---------- agent.tools.cistern.credential-mode ----------

    @Test
    void credentialModeBindsFromTheHyphenlessEnvName() {
        bound(Map.of("AGENT_TOOLS_CISTERN_CREDENTIALMODE", "forward"),
                props -> assertThat(props.getTools().getCistern().getCredentialMode()).isEqualTo(CredentialMode.FORWARD));
    }

    @Test
    void credentialModeAcceptsEveryModeInLowerCase() {
        bound(Map.of("AGENT_TOOLS_CISTERN_CREDENTIALMODE", "per-user"),
                props -> assertThat(props.getTools().getCistern().getCredentialMode()).isEqualTo(CredentialMode.PER_USER));
        bound(Map.of("AGENT_TOOLS_CISTERN_CREDENTIALMODE", "service"),
                props -> assertThat(props.getTools().getCistern().getCredentialMode()).isEqualTo(CredentialMode.SERVICE));
    }

    @Test
    void theUnderscoredLegacyNameBindsTooForABeanProperty() {
        // SystemEnvironmentPropertyMapper offers a legacy candidate with '-' → '_', so the
        // intuitive spelling works here — unlike for map keys (below).
        bound(Map.of("AGENT_TOOLS_CISTERN_CREDENTIAL_MODE", "forward"),
                props -> assertThat(props.getTools().getCistern().getCredentialMode()).isEqualTo(CredentialMode.FORWARD));
    }

    @Test
    void credentialModeDefaultsToService() {
        bound(Map.of(), props -> assertThat(props.getTools().getCistern().getCredentialMode()).isEqualTo(CredentialMode.SERVICE));
    }

    // ---------- agent.credentials.per-user.openai.<userId> ----------

    @Test
    void perUserOpenAiKeyBindsFromTheEnvToTheLowerCasedUser() {
        bound(Map.of("AGENT_CREDENTIALS_PERUSER_OPENAI_BOB", "sk-x"), props -> {
            Map<String, String> openai = props.getCredentials().getPerUser().get("openai");
            assertThat(openai).containsOnlyKeys("bob");
            assertThat(openai.get("bob")).isEqualTo("sk-x");
        });
    }

    @Test
    void theUnderscoredPrefixBindsNothingForTheMap() {
        // Map entries are found by enumerating the environment and mapping each name back to
        // a property name: AGENT_CREDENTIALS_PER_USER_... becomes agent.credentials.per.user...,
        // which is not under agent.credentials.per-user. Silent — the map just stays empty.
        bound(Map.of("AGENT_CREDENTIALS_PER_USER_OPENAI_BOB", "sk-x"),
                props -> assertThat(props.getCredentials().getPerUser()).doesNotContainKey("openai"));
    }

    @Test
    void anUnderscoreInsideTheUserSegmentBecomesADot() {
        // So an env-provisioned user is limited to lower-case letters and digits; anything
        // else (preferred_username with '_', an email, a UUID with '-') goes in properties.
        bound(Map.of("AGENT_CREDENTIALS_PERUSER_OPENAI_BOB_SMITH", "sk-y"),
                props -> assertThat(props.getCredentials().getPerUser().get("openai")).containsOnlyKeys("bob.smith"));
    }

    @Test
    void propertiesKeepTheUserIdVerbatimWithBracketsForSpecialCharacters() {
        // Upper case, digits, '-' and '.' survive as they are; anything else ('@' here) is
        // dropped from an unbracketed key, so such a key is written [in brackets].
        new ApplicationContextRunner()
                .withInitializer(ctx -> ctx.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test",
                        Map.of("agent.credentials.per-user.openai.Bob", "sk-z",
                               "agent.credentials.per-user.openai.3f2a-9c1d", "sk-u",
                               "agent.credentials.per-user.openai.[alice@example.com]", "sk-a"))))
                .withUserConfiguration(Cfg.class)
                .run(ctx -> {
                    Map<String, String> openai = ctx.getBean(AgentProperties.class).getCredentials().getPerUser().get("openai");
                    assertThat(openai).containsOnlyKeys("Bob", "3f2a-9c1d", "alice@example.com");
                });
    }

    @Test
    void anUnbracketedKeyWithASpecialCharacterSilentlyLosesIt() {
        new ApplicationContextRunner()
                .withInitializer(ctx -> ctx.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test",
                        Map.of("agent.credentials.per-user.openai.alice@example.com", "sk-a"))))
                .withUserConfiguration(Cfg.class)
                .run(ctx -> assertThat(ctx.getBean(AgentProperties.class).getCredentials().getPerUser().get("openai"))
                        .containsOnlyKeys("aliceexample.com"));
    }
}
