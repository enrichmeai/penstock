package com.example.agent.tools;

import java.util.Locale;

/**
 * What happens when the model calls a tool (#179), set per tool by {@code agent.tools.policy.<tool>}.
 * A tool with no entry is {@link #ALLOW}, so an empty policy leaves every tool behaving exactly as
 * it did before the setting existed.
 *
 * <ul>
 *   <li>{@link #ALLOW}: the call runs.</li>
 *   <li>{@link #ASK}: the turn pauses until the user approves or denies this one call; no answer
 *       within {@code agent.tools.ask-timeout-seconds} counts as a denial.</li>
 *   <li>{@link #DENY}: the call never runs, and the model is told why.</li>
 * </ul>
 */
public enum ToolPolicy {
    ALLOW, ASK, DENY;

    public static ToolPolicy parse(String raw) {
        if (raw != null) {
            switch (raw.trim().toLowerCase(Locale.ROOT)) {
                case "allow": return ALLOW;
                case "ask": return ASK;
                case "deny": return DENY;
                default: break;
            }
        }
        throw new IllegalArgumentException("Tool policy must be allow, ask or deny, got: " + raw);
    }

    /**
     * Spring drops characters other than letters, digits, '-' and '.' from an unbracketed map key,
     * and an environment variable turns '_' into '.', so {@code write_file} can arrive as
     * {@code writefile} or {@code write.file}. Policy keys and tool names are compared in this form.
     */
    public static String normaliseKey(String key) {
        return key == null ? "" : key.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }
}
