// Skeleton: the CLI/env switch that picks stdio-agent mode over the normal entry point.
// Plain Java, no framework imports — compiles standalone so this file alone proves the
// *decision logic* parses; wiring it to your framework's "run with no web server, this
// profile" call is the one line marked APPLY below, left out here on purpose.
//
// What varies per use (see manifest.yaml `inputs`):
//   <your-mode-flag>   the CLI flag/env var that selects stdio mode (Penstock: --acp / AGENT_MODE=acp)
//   <your-profile-name> the name you give this mode internally (Penstock: "acp")
public final class ModeSwitchSkeleton {

    private ModeSwitchSkeleton() {
    }

    public static boolean isStdioAgentMode(String[] args) {
        for (String a : args) {
            if ("<your-mode-flag>".equals(a)) {
                return true;
            }
        }
        return "<your-profile-name>".equalsIgnoreCase(resolve("<YOUR_MODE_ENV_VAR>", args));
    }

    /** Read a config value from CLI (`--key=value`) or env var, falling back to "". */
    private static String resolve(String envName, String[] args) {
        String cliPrefix = "--<your-mode-prop-name>=";
        for (String a : args) {
            if (a.startsWith(cliPrefix)) {
                return a.substring(cliPrefix.length());
            }
        }
        String fromEnv = System.getenv(envName);
        return fromEnv == null ? "" : fromEnv;
    }

    public static void apply(String[] args) {
        if (!isStdioAgentMode(args)) {
            return;
        }
        // APPLY: wire this into your framework's startup before the context/server starts —
        // e.g. force "no web server" and add this mode's profile/flag, the same way
        // AgentApplication.main does for Spring Boot (setWebApplicationType(NONE),
        // setAdditionalProfiles("<your-profile-name>")). Must run before any component that
        // reads the profile is constructed — a @PostConstruct check is too late.
    }
}
