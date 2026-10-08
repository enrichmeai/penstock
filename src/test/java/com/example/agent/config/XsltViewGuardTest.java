package com.example.agent.config;

import com.example.agent.AgentApplication;
import com.example.agent.llm.CompletionResult;
import com.example.agent.llm.LlmProvider;
import com.example.agent.model.ChatMessage;
import com.example.agent.model.TokenUsage;
import com.example.agent.tools.ToolSpec;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Scope;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.servlet.view.ContentNegotiatingViewResolver;
import org.springframework.web.servlet.view.xslt.XsltView;
import org.springframework.web.servlet.view.xslt.XsltViewResolver;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR 0004, option B (#120): CVE-2026-47884 (spring-webmvc 6.2.19, critical) is in {@link XsltView},
 * and Penstock stays on Spring Boot 3.5, which has no fix. The CVE is reachable only if the application
 * renders an XSLT view, so this fails the build the moment one could be.
 *
 * <p>Two checks, because a bean check alone misses the usual ways in: a resolver registered through
 * {@code WebMvcConfigurer.configureViewResolvers} lives inside the {@code mvcViewResolver} composite,
 * a {@code UrlBasedViewResolver} can be given {@code XsltView} as its view class, and a handler can
 * return {@code new XsltView()} or a {@code ModelAndView} holding one. Every one of those names the
 * {@code org.springframework.web.servlet.view.xslt} package in Penstock's own classes or resources, so
 * the static scan catches them; the context check catches a bean that arrives some other way.
 *
 * <p>Neither bans view resolvers in general: Boot registers several by default
 * ({@code InternalResourceViewResolver}, {@code BeanNameViewResolver}, {@code ContentNegotiatingViewResolver},
 * the {@code mvcViewResolver} composite) and the Whitelabel error view.
 */
class XsltViewGuardTest {

    static final String XSLT_PACKAGE_INTERNAL = "org/springframework/web/servlet/view/xslt";
    static final String XSLT_PACKAGE = "org.springframework.web.servlet.view.xslt";

    /** XSLT view and view-resolver beans in the context, directly or inside the negotiating resolver. */
    static List<String> xsltBeans(ApplicationContext ctx) {
        List<String> found = new ArrayList<>();
        found.addAll(Arrays.asList(ctx.getBeanNamesForType(XsltView.class, true, true)));
        found.addAll(Arrays.asList(ctx.getBeanNamesForType(XsltViewResolver.class, true, true)));
        for (ContentNegotiatingViewResolver cnvr : ctx.getBeansOfType(ContentNegotiatingViewResolver.class).values()) {
            if (cnvr.getViewResolvers() == null) continue;
            cnvr.getViewResolvers().stream()
                    .filter(XsltViewResolver.class::isInstance)
                    .forEach(r -> found.add("contentNegotiatingViewResolver -> " + r.getClass().getName()));
        }
        return found;
    }

    /** Files under {@code root} (class files or resources) that name the XSLT view package. */
    static List<String> filesNamingXslt(Path root) throws IOException {
        if (!Files.isDirectory(root)) return List.of();
        try (Stream<Path> files = Files.walk(root)) {
            return files.filter(Files::isRegularFile).filter(f -> {
                try {
                    String text = new String(Files.readAllBytes(f), StandardCharsets.ISO_8859_1);
                    return text.contains(XSLT_PACKAGE_INTERNAL) || text.contains(XSLT_PACKAGE);
                } catch (IOException e) {
                    throw new IllegalStateException(e);
                }
            }).map(f -> root.relativize(f).toString()).toList();
        }
    }

    @Test
    void noMainClassOrResourceNamesTheXsltViewPackage() throws IOException, URISyntaxException {
        // build/classes/java/main, and its sibling build/resources/main
        Path classes = Path.of(AgentApplication.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        Path resources = classes.getParent().getParent().getParent().resolve("resources/main");
        assertThat(classes.resolve("com/example/agent/AgentApplication.class")).isRegularFile();
        assertThat(resources.resolve("application.yml")).isRegularFile();
        assertThat(filesNamingXslt(classes)).as("classes naming %s (ADR 0004)", XSLT_PACKAGE).isEmpty();
        assertThat(filesNamingXslt(resources)).as("resources naming %s (ADR 0004)", XSLT_PACKAGE).isEmpty();
    }

    @Test
    void theScanFindsAClassThatNamesIt(@TempDir Path dir) throws IOException {
        Files.write(dir.resolve("Uses.class"), ("Êþº¾..." + XSLT_PACKAGE_INTERNAL + "/XsltView...")
                .getBytes(StandardCharsets.ISO_8859_1));
        Files.writeString(dir.resolve("views.xml"), "<bean class=\"" + XSLT_PACKAGE + ".XsltViewResolver\"/>");
        Files.writeString(dir.resolve("Clean.class"), "org/springframework/web/servlet/view/InternalResourceView");
        assertThat(filesNamingXslt(dir)).containsExactlyInAnyOrder("Uses.class", "views.xml");
    }

    @TestConfiguration
    static class StubLlm {
        @Bean @Primary
        LlmProvider stub() {
            return new LlmProvider() {
                @Override public String name() { return "stub"; }
                @Override public CompletionResult complete(String sys, List<ChatMessage> h, List<ToolSpec> t, String sessionId) {
                    return new CompletionResult(ChatMessage.assistantText("ok"), TokenUsage.ZERO);
                }
            };
        }
    }

    @Nested
    @SpringBootTest
    @TestPropertySource(properties = {
            "agent.auth.enabled=false",
            "agent.llm.provider=stub",
            "agent.workspace=${java.io.tmpdir}/agent-test-xslt-guard",
            "agent.storage.type=memory"
    })
    @Import(StubLlm.class)
    class TheApplication {
        @Autowired ApplicationContext ctx;

        @Test
        void hasNoXsltViewOrResolverBean() {
            assertThat(xsltBeans(ctx))
                    .as("an XSLT view makes CVE-2026-47884 reachable on Spring Framework 6.2 (ADR 0004)")
                    .isEmpty();
        }
    }

    /** The context check is not vacuous: on a context that registers both, it finds each one. */
    @Nested
    @SpringBootTest
    @TestPropertySource(properties = {
            "agent.auth.enabled=false",
            "agent.llm.provider=stub",
            "agent.workspace=${java.io.tmpdir}/agent-test-xslt-guard-red",
            "agent.storage.type=memory"
    })
    @Import({StubLlm.class, AnApplicationThatAddsThem.WithXslt.class})
    class AnApplicationThatAddsThem {
        @TestConfiguration
        static class WithXslt {
            @Bean XsltViewResolver xsltViewResolver() { return new XsltViewResolver(); }
            @Bean @Scope("prototype") XsltView xsltView() { return new XsltView(); }
        }

        @Autowired ApplicationContext ctx;

        @Test
        void isCaught() {
            assertThat(xsltBeans(ctx)).containsExactlyInAnyOrder(
                    "xsltView",
                    "xsltViewResolver",
                    "contentNegotiatingViewResolver -> " + XsltViewResolver.class.getName());
        }
    }
}
