package com.example.agent.acp;

import com.example.agent.tools.Tool;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link AcpGatedTool} wraps the real tool in ACP mode, so every method of {@link Tool} must be
 * declared on it: a default method it inherits instead of forwarding silently drops the wrapped
 * tool's behaviour in that profile only. That is how {@code web_fetch}'s system-prompt note went
 * missing in ACP mode during review of #185. A new {@code Tool} method fails this test until the
 * wrapper forwards it.
 */
class AcpGatedToolDelegationTest {

    @Test
    void theWrapperDeclaresEveryToolMethod() {
        List<String> missing = new ArrayList<>();
        for (Method m : Tool.class.getMethods()) {
            if (Modifier.isStatic(m.getModifiers())) continue;
            try {
                AcpGatedTool.class.getDeclaredMethod(m.getName(), m.getParameterTypes());
            } catch (NoSuchMethodException e) {
                missing.add(m.getName());
            }
        }
        assertTrue(missing.isEmpty(), "AcpGatedTool inherits instead of forwarding: " + missing);
    }
}
