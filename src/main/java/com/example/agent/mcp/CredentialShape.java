package com.example.agent.mcp;

import java.util.List;
import java.util.regex.Pattern;

/**
 * The credential shapes {@code scripts/lib/credential-grep.sh} refuses, as Java, for text the MCP
 * {@code draft_episode} tool (#116) is about to write as YAML. Kept in step with that script: its
 * three passes, the third applying because a draft is a YAML file. A match is reported by line
 * number only, never by value.
 */
final class CredentialShape {

    private static final List<Pattern> PATTERNS = List.of(
            // 1. well-known prefixes, anywhere, case-sensitive
            Pattern.compile("(sk-[A-Za-z0-9]{10,}|ghp_[A-Za-z0-9]{10,}|AKIA[0-9A-Z]{10,}|-----BEGIN [A-Z ]*PRIVATE KEY-----)"),
            // 2. a quoted literal of real length assigned to a password/token/secret
            Pattern.compile("(password|token|secret)\\s*[:=]\\s*\"[^\"\\s]{6,}\"", Pattern.CASE_INSENSITIVE),
            // 3. a bare value assigned to one, as it would appear in a YAML file
            Pattern.compile("(password|token|secret)\\s*[:=]\\s*[^<$\"{\\s]", Pattern.CASE_INSENSITIVE));

    private CredentialShape() {
    }

    /** The 1-based number of the first line that looks like a credential, or 0 when none does. */
    static int firstMatchingLine(String text) {
        String[] lines = text.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            for (Pattern p : PATTERNS) {
                if (p.matcher(lines[i]).find()) {
                    return i + 1;
                }
            }
        }
        return 0;
    }
}
