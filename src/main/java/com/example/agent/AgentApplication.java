package com.example.agent;

import com.example.agent.acp.AcpStdioRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Entry point for the AI coding agent.
 *
 * Run: ./gradlew bootRun
 *      (or) java -jar build/libs/penstock-0.1.0.jar
 *      (or) java -jar build/libs/penstock-0.1.0.jar --acp   (ACP stdio mode, for an IDE)
 *
 * The storage backend is selected before Spring auto-config runs:
 *   - memory  (default / unknown): exclude JPA + DataSource auto-config so
 *     Hibernate doesn't try to start without a datasource.
 *   - sqlite : activate the storage-sqlite profile (datasource + Flyway).
 *   - postgres: activate the storage-postgres profile (datasource + Flyway).
 *
 * {@code --acp} (or {@code AGENT_MODE=acp}) activates the {@code acp} profile and forces
 * {@code web-application-type=none} before the context starts — the same timing trick as
 * the storage switch above, since by the time a {@code @Profile}-gated bean could read it,
 * Spring Boot has already chosen the application context type. See
 * {@code SecurityConfig}'s {@code @Profile("!acp")} for why: {@code HttpSecurity} only
 * exists under a web application context, and ACP mode has none to secure.
 */
@SpringBootApplication
public class AgentApplication {

    public static void main(String[] args) {
        SpringApplication app = new SpringApplication(AgentApplication.class);

        List<String> profiles = new ArrayList<>();
        Map<String, Object> defaults = new HashMap<>();

        String storage = resolve("AGENT_STORAGE_TYPE", "agent.storage.type", "memory", args);
        switch (storage == null ? "memory" : storage.toLowerCase()) {
            case "sqlite" -> profiles.add("storage-sqlite");
            case "postgres" -> profiles.add("storage-postgres");
            default -> defaults.put(
                    "spring.autoconfigure.exclude",
                    "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration," +
                    "org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration," +
                    "org.springframework.boot.autoconfigure.data.jpa.JpaRepositoriesAutoConfiguration," +
                    "org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration"
            );
        }

        boolean acp = isAcpMode(args);
        if (acp) {
            profiles.add("acp");
            defaults.put("spring.main.banner-mode", "off");
        }

        if (!profiles.isEmpty()) app.setAdditionalProfiles(profiles.toArray(new String[0]));
        if (!defaults.isEmpty()) app.setDefaultProperties(defaults);
        if (acp) app.setWebApplicationType(WebApplicationType.NONE);

        ConfigurableApplicationContext ctx = app.run(args);
        if (acp) {
            ctx.getBean(AcpStdioRunner.class).run();
        }
    }

    private static boolean isAcpMode(String[] args) {
        for (String a : args) {
            if ("--acp".equals(a)) return true;
        }
        return "acp".equalsIgnoreCase(resolve("AGENT_MODE", "agent.mode", "", args));
    }

    /** Read a config value from CLI (`--key=value`), env var, system property, or default. */
    private static String resolve(String envName, String propName, String def, String[] args) {
        String cliPrefix = "--" + propName + "=";
        for (String a : args) if (a.startsWith(cliPrefix)) return a.substring(cliPrefix.length());
        String fromEnv = System.getenv(envName);
        if (fromEnv != null && !fromEnv.isBlank()) return fromEnv;
        String fromProp = System.getProperty(propName);
        if (fromProp != null && !fromProp.isBlank()) return fromProp;
        return def;
    }
}
