package com.example.agent.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Scope;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.reactive.function.client.WebClient;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * Core wiring: workspace path and WebClient builder. ObjectMapper is
 * auto-configured by Spring Boot (with JavaTimeModule + ParameterNamesModule,
 * which is required for record deserialisation).
 */
@Configuration
@EnableConfigurationProperties(AgentProperties.class)
@EnableAsync
public class AppConfig {

    /**
     * Resolves and ensures the workspace dir exists. The agent is restricted to this folder.
     */
    @Bean
    public Path agentWorkspace(AgentProperties props) throws Exception {
        Path p = Paths.get(props.getWorkspace()).toAbsolutePath().normalize();
        Files.createDirectories(p);
        return p;
    }

    /**
     * A fresh builder for each injection point, as Spring Boot's own auto-configured builder is:
     * the LLM providers add their credentials as default headers, and a shared builder carried
     * them into every client built after it (the Anthropic key reached the pod and Jira).
     */
    @Bean
    @Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
    public WebClient.Builder webClientBuilder() {
        return WebClient.builder();
    }

    /**
     * Bounded thread pool for SSE streaming. Configured from agent.sse.*
     * properties with CallerRunsPolicy for graceful degradation under load.
     */
    @Bean(name = "sseTaskExecutor")
    public AsyncTaskExecutor sseTaskExecutor(AgentProperties props) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(props.getSse().getCorePoolSize());
        executor.setMaxPoolSize(props.getSse().getMaxPoolSize());
        executor.setQueueCapacity(props.getSse().getQueueCapacity());
        executor.setThreadNamePrefix("agent-sse-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }
}
