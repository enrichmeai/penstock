package com.example.agent.config;

import com.example.agent.tools.CredentialMode;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;
import java.util.Map;

/**
 * Strongly-typed bindings for everything under "agent.*" in application.yml.
 */
@ConfigurationProperties(prefix = "agent")
public class AgentProperties {

    private String workspace;
    private Llm llm = new Llm();
    private Tools tools = new Tools();
    private Storage storage = new Storage();
    private Auth auth = new Auth();
    private Cors cors = new Cors();
    private Credentials credentials = new Credentials();
    private Sse sse = new Sse();
    private RateLimit rateLimit = new RateLimit();
    private Metrics metrics = new Metrics();

    public String getWorkspace() { return workspace; }
    public void setWorkspace(String workspace) { this.workspace = workspace; }

    public Llm getLlm() { return llm; }
    public void setLlm(Llm llm) { this.llm = llm; }

    public Tools getTools() { return tools; }
    public void setTools(Tools tools) { this.tools = tools; }

    public Storage getStorage() { return storage; }
    public void setStorage(Storage storage) { this.storage = storage; }

    public Metrics getMetrics() { return metrics; }
    public void setMetrics(Metrics metrics) { this.metrics = metrics; }

    public Auth getAuth() { return auth; }

    public Cors getCors() { return cors; }
    public void setCors(Cors cors) { this.cors = cors; }

    public Credentials getCredentials() { return credentials; }
    public void setCredentials(Credentials credentials) { this.credentials = credentials; }
    public void setAuth(Auth auth) { this.auth = auth; }

    public Sse getSse() { return sse; }
    public void setSse(Sse sse) { this.sse = sse; }

    public RateLimit getRateLimit() { return rateLimit; }
    public void setRateLimit(RateLimit rateLimit) { this.rateLimit = rateLimit; }

    // ---------- nested ----------

    public static class Llm {
        private String provider = "anthropic";
        /** @deprecated use maxTurnsPerRequest; kept for backwards compat. */
        @Deprecated private int maxIterations = 25;
        private int maxTurnsPerRequest = 10;
        private long maxTokensPerRequest = 50_000;
        private long maxTokensPerSession = 200_000;
        private String systemPrompt = "";
        private boolean streamingEnabled = true;
        private Context context = new Context();
        private Anthropic anthropic = new Anthropic();
        private OpenAi openai = new OpenAi();
        private Ollama ollama = new Ollama();
        private Copilot copilot = new Copilot();

        public String getProvider() { return provider; }
        public void setProvider(String provider) { this.provider = provider; }
        public int getMaxIterations() { return maxIterations; }
        public void setMaxIterations(int maxIterations) { this.maxIterations = maxIterations; }
        public int getMaxTurnsPerRequest() { return maxTurnsPerRequest; }
        public void setMaxTurnsPerRequest(int maxTurnsPerRequest) { this.maxTurnsPerRequest = maxTurnsPerRequest; }
        public long getMaxTokensPerRequest() { return maxTokensPerRequest; }
        public void setMaxTokensPerRequest(long maxTokensPerRequest) { this.maxTokensPerRequest = maxTokensPerRequest; }
        public long getMaxTokensPerSession() { return maxTokensPerSession; }
        public void setMaxTokensPerSession(long maxTokensPerSession) { this.maxTokensPerSession = maxTokensPerSession; }
        public String getSystemPrompt() { return systemPrompt; }
        public void setSystemPrompt(String systemPrompt) { this.systemPrompt = systemPrompt; }
        public boolean isStreamingEnabled() { return streamingEnabled; }
        public void setStreamingEnabled(boolean streamingEnabled) { this.streamingEnabled = streamingEnabled; }
        public Context getContext() { return context; }
        public void setContext(Context context) { this.context = context; }
        public Anthropic getAnthropic() { return anthropic; }
        public void setAnthropic(Anthropic anthropic) { this.anthropic = anthropic; }
        public OpenAi getOpenai() { return openai; }
        public void setOpenai(OpenAi openai) { this.openai = openai; }
        public Ollama getOllama() { return ollama; }
        public void setOllama(Ollama ollama) { this.ollama = ollama; }
        public Copilot getCopilot() { return copilot; }
        public void setCopilot(Copilot copilot) { this.copilot = copilot; }
    }

    public static class Copilot {
        /** GitHub token with the `copilot` scope, or enterprise Copilot API token. */
        private String apiKey;
        /** Defaults to api.githubcopilot.com; override for enterprise endpoints (e.g. GitHub Models). */
        private String baseUrl = "https://api.githubcopilot.com";
        /** Model identifier as exposed by the Copilot API — gpt-4o, claude-3-5-sonnet, o1-mini, etc. */
        private String model = "gpt-4o";
        private int maxTokens = 4096;
        /** Optional: sent as X-GitHub-Api-Version. Leave null for provider default. */
        private String apiVersion;

        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }
        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }
        public int getMaxTokens() { return maxTokens; }
        public void setMaxTokens(int maxTokens) { this.maxTokens = maxTokens; }
        public String getApiVersion() { return apiVersion; }
        public void setApiVersion(String apiVersion) { this.apiVersion = apiVersion; }
    }

    public static class Context {
        /** none | last-n */
        private String policy = "last-n";
        private int lastN = 50;
        public String getPolicy() { return policy; }
        public void setPolicy(String policy) { this.policy = policy; }
        public int getLastN() { return lastN; }
        public void setLastN(int lastN) { this.lastN = lastN; }
    }

    public static class Sse {
        private int corePoolSize = 4;
        private int maxPoolSize = 16;
        private int queueCapacity = 32;
        /**
         * How often a comment-frame "heartbeat" is written to every live SSE emitter
         * so idle proxies/LBs don't time out long-lived streams (the LLM call or a
         * shell tool can easily exceed a 30-60s proxy idle window). {@link
         * java.time.Duration#ZERO} disables the feature.
         *
         * <p>The default is intentionally well under the 30s idle limit that
         * most cloud load balancers ship with.
         */
        private java.time.Duration heartbeatInterval = java.time.Duration.ofSeconds(15);
        public int getCorePoolSize() { return corePoolSize; }
        public void setCorePoolSize(int corePoolSize) { this.corePoolSize = corePoolSize; }
        public int getMaxPoolSize() { return maxPoolSize; }
        public void setMaxPoolSize(int maxPoolSize) { this.maxPoolSize = maxPoolSize; }
        public int getQueueCapacity() { return queueCapacity; }
        public void setQueueCapacity(int queueCapacity) { this.queueCapacity = queueCapacity; }
        public java.time.Duration getHeartbeatInterval() { return heartbeatInterval; }
        public void setHeartbeatInterval(java.time.Duration heartbeatInterval) { this.heartbeatInterval = heartbeatInterval; }
    }

    public static class RateLimit {
        private boolean enabled = true;
        private Bucket chat = new Bucket(30, 30, java.time.Duration.ofMinutes(1));
        private Bucket api  = new Bucket(300, 300, java.time.Duration.ofMinutes(1));
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public Bucket getChat() { return chat; }
        public void setChat(Bucket chat) { this.chat = chat; }
        public Bucket getApi() { return api; }
        public void setApi(Bucket api) { this.api = api; }
    }

    public static class Bucket {
        private long capacity;
        private long refillTokens;
        private java.time.Duration refillPeriod;
        public Bucket() {}
        public Bucket(long capacity, long refillTokens, java.time.Duration refillPeriod) {
            this.capacity = capacity; this.refillTokens = refillTokens; this.refillPeriod = refillPeriod;
        }
        public long getCapacity() { return capacity; }
        public void setCapacity(long capacity) { this.capacity = capacity; }
        public long getRefillTokens() { return refillTokens; }
        public void setRefillTokens(long refillTokens) { this.refillTokens = refillTokens; }
        public java.time.Duration getRefillPeriod() { return refillPeriod; }
        public void setRefillPeriod(java.time.Duration refillPeriod) { this.refillPeriod = refillPeriod; }
    }

    public static class Anthropic {
        private String apiKey;
        private String baseUrl = "https://api.anthropic.com";
        private String model = "claude-sonnet-4-5";
        private int maxTokens = 4096;

        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }
        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }
        public int getMaxTokens() { return maxTokens; }
        public void setMaxTokens(int maxTokens) { this.maxTokens = maxTokens; }
    }

    /**
     * OpenAI Chat Completions, or anything that speaks it — a LiteLLM gateway, for one.
     *
     * <p>{@link #apiKey} is the service key, used for any user without a key of their own.
     * A per-user gateway key ({@code agent.credentials.per-user.openai.<userId>}) takes
     * precedence for that user's calls; see {@link Credentials}.
     */
    public static class OpenAi {
        private String apiKey;
        /**
         * Origin only, no path: the provider appends {@code /v1/chat/completions}. A
         * LiteLLM gateway is {@code http://litellm:4000}; a trailing slash is tolerated.
         */
        private String baseUrl = "https://api.openai.com";
        private String model = "gpt-4o";
        private int maxTokens = 4096;

        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }
        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }
        public int getMaxTokens() { return maxTokens; }
        public void setMaxTokens(int maxTokens) { this.maxTokens = maxTokens; }
    }

    public static class Ollama {
        private String baseUrl = "http://localhost:11434";
        /**
         * Must be a model that emits Ollama's structured tool_calls; the qwen2.5-coder
         * family never does, which leaves the agent loop silently doing nothing.
         */
        private String model = "llama3.2:3b";
        /**
         * Context window sent as options.num_ctx. Ollama otherwise applies its own
         * default (measured: 2050 in the bundled container, 4096 on host 0.12.11) and
         * silently drops whatever does not fit — the system prompt and tool schemas
         * go first. 0 omits the option so a server-side setting wins.
         */
        private int numCtx = 16_384;

        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }
        public int getNumCtx() { return numCtx; }
        public void setNumCtx(int numCtx) { this.numCtx = numCtx; }
    }

    public static class Tools {
        private Shell shell = new Shell();
        private File file = new File();
        private Jira jira = new Jira();
        private Cistern cistern = new Cistern();
        private int maxOutputBytes = 16_384;

        public Shell getShell() { return shell; }
        public void setShell(Shell shell) { this.shell = shell; }
        public File getFile() { return file; }
        public void setFile(File file) { this.file = file; }
        public Jira getJira() { return jira; }
        public Cistern getCistern() { return cistern; }
        public void setJira(Jira jira) { this.jira = jira; }
        public int getMaxOutputBytes() { return maxOutputBytes; }
        public void setMaxOutputBytes(int maxOutputBytes) { this.maxOutputBytes = maxOutputBytes; }
    }

    public static class Shell {
        private boolean enabled = true;
        private int timeoutSeconds = 60;
        private List<String> blockedPatterns = List.of();
        /** Optional allow-list: if non-empty, commands whose first token isn't in it are rejected. */
        private List<String> allowedCommands = List.of();

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public int getTimeoutSeconds() { return timeoutSeconds; }
        public void setTimeoutSeconds(int timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }
        public List<String> getBlockedPatterns() { return blockedPatterns; }
        public void setBlockedPatterns(List<String> blockedPatterns) { this.blockedPatterns = blockedPatterns; }
        public List<String> getAllowedCommands() { return allowedCommands; }
        public void setAllowedCommands(List<String> allowedCommands) { this.allowedCommands = allowedCommands; }
    }

    public static class File {
        private boolean enabled = true;
        private long maxBytes = 1_048_576;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public long getMaxBytes() { return maxBytes; }
        public void setMaxBytes(long maxBytes) { this.maxBytes = maxBytes; }
    }

    public static class Jira {
        private String baseUrl = "";
        private String token = "";

        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
        public String getToken() { return token; }
        public void setToken(String token) { this.token = token; }
    }

    /**
     * The Cistern pod this agent may reach, and the credential it reaches it with.
     *
     * <p>By default the credential is the <em>agent's own</em>, not its user's. Cistern
     * resolves it to a WebID belonging to this application, so a grant written for this agent
     * is a grant to it alone — and revoking it stops this agent without touching anyone else.
     * That is the point of pointing the agent at a pod rather than at a directory.
     * {@link #credentialMode} changes whose credential is presented; see {@link CredentialMode}.
     */
    public static class Cistern {
        /** e.g. http://localhost:3737 — the pod's base URL. */
        private String baseUrl = "";
        /** This agent's own service credential, as configured in the pod. */
        private String token = "";
        /**
         * Whose credential the pod tool presents. Environment form:
         * {@code AGENT_TOOLS_CISTERN_CREDENTIALMODE} (relaxed binding removes the hyphen);
         * the legacy spelling {@code AGENT_TOOLS_CISTERN_CREDENTIAL_MODE} binds as well, as
         * it does for any bean property. {@code RelaxedEnvBindingTest} pins both.
         */
        private CredentialMode credentialMode = CredentialMode.SERVICE;

        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
        public String getToken() { return token; }
        public void setToken(String token) { this.token = token; }
        public CredentialMode getCredentialMode() { return credentialMode; }
        public void setCredentialMode(CredentialMode credentialMode) {
            this.credentialMode = credentialMode == null ? CredentialMode.SERVICE : credentialMode;
        }
    }

    public static class Storage {
        /** One of: memory, sqlite, postgres */
        private String type = "memory";
        /** Only used when type=sqlite. */
        private String sqlitePath = "./data/agent.db";

        public String getType() { return type; }
        public void setType(String type) { this.type = type; }
        public String getSqlitePath() { return sqlitePath; }
        public void setSqlitePath(String sqlitePath) { this.sqlitePath = sqlitePath; }
    }

    /**
     * Authentication configuration.
     *
     *  - {@code enabled=false}: all endpoints permitAll.
     *  - {@code enabled=true, mode=basic}: HTTP Basic against the in-memory
     *    {@link #username}/{@link #password} (legacy / transitional).
     *  - {@code enabled=true, mode=oidc}: JWT bearer auth, validated against
     *    {@link Oidc#issuerUri} via Spring Security's resource-server support.
     *  - {@code enabled=true, mode=disabled}: equivalent to {@code enabled=false}.
     */
    public static class Auth {
        // Secure by default: an agent that executes shell commands never ships
        // open — opting OUT (dev/demo surfaces) is the explicit act.
        private boolean enabled = true;
        /** basic | oidc | disabled */
        private String mode = "basic";
        // ---- Basic-mode legacy settings ----
        private String username = "admin";
        /**
         * Password in plain text; for production, use a secret manager. No
         * shipped default: when unset (or left at the historical "change-me"),
         * SecurityConfig generates a random password at startup and logs it —
         * a documented constant must never authenticate.
         */
        private String password;
        // ---- OIDC-mode settings ----
        private Oidc oidc = new Oidc();

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getMode() { return mode; }
        public void setMode(String mode) { this.mode = mode; }
        public String getUsername() { return username; }
        public void setUsername(String username) { this.username = username; }
        public String getPassword() { return password; }
        public void setPassword(String password) { this.password = password; }
        public Oidc getOidc() { return oidc; }
        public void setOidc(Oidc oidc) { this.oidc = oidc; }
    }

    /**
     * Per-user outbound credentials for tools and providers that talk to external
     * systems: {@code agent.credentials.per-user.<service>.<userId> = token}. A user
     * with no entry falls back to the service credential (the default, documented v1
     * posture). See {@code CredentialResolver}. Services in use: {@code cistern} (when
     * {@link Cistern#credentialMode} is {@code per-user}) and {@code openai} (a per-user
     * gateway key for the OpenAI-compatible provider).
     *
     * <p>The user ID is the principal name the session was stamped with — the
     * {@code agent.auth.oidc.principal-claim} value under OIDC. As a map key it is
     * matched exactly, and how it is bound depends on where it came from:
     * <ul>
     *   <li>Properties and YAML keep the key verbatim — upper case, digits, {@code -} and
     *       {@code .} included: {@code agent.credentials.per-user.openai.Bob=sk-x} binds user
     *       {@code Bob}. Any other character ({@code @}, say) is dropped from an unbracketed
     *       key, so such a key is written in brackets:
     *       {@code agent.credentials.per-user.openai.[alice@example.com]=sk-x}.</li>
     *   <li>The environment lower-cases it: {@code AGENT_CREDENTIALS_PERUSER_OPENAI_BOB=sk-x}
     *       binds user {@code bob}, never {@code BOB} or {@code Bob}. An underscore inside the
     *       user segment becomes a dot ({@code ..._OPENAI_BOB_SMITH} binds {@code bob.smith}),
     *       so a principal containing {@code @}, {@code -} or upper case cannot be expressed
     *       from the environment at all — configure those in properties or YAML. And unlike a
     *       bean property, the prefix has exactly one spelling: {@code AGENT_CREDENTIALS_PER_USER_...}
     *       binds nothing, silently.</li>
     * </ul>
     * {@code RelaxedEnvBindingTest} pins all of this.
     */
    public static class Credentials {
        private Map<String, Map<String, String>> perUser = new java.util.HashMap<>();

        public Map<String, Map<String, String>> getPerUser() { return perUser; }
        public void setPerUser(Map<String, Map<String, String>> perUser) {
            this.perUser = perUser == null ? new java.util.HashMap<>() : perUser;
        }
    }

    /**
     * Cross-origin policy for browser clients. Default is locked: no
     * cross-origin grants at all — the bundled UI is served same-origin and
     * needs none. Listing origins enables them with credentials; the single
     * entry "*" allows any origin but with credentials disabled (never both).
     */
    public static class Metrics {
        /**
         * Whether /actuator/prometheus may be scraped without authenticating while
         * auth is enabled. Default false: the metric labels carry tool names,
         * provider names, token counts and request rates, which is operational
         * detail about a private deployment. Set true where the scraper cannot
         * authenticate and the port is already network-restricted.
         *
         * /actuator/health/** is unaffected and always open — load balancers need it.
         */
        private boolean publicScrape = false;

        public boolean isPublicScrape() { return publicScrape; }
        public void setPublicScrape(boolean publicScrape) { this.publicScrape = publicScrape; }
    }

    public static class Cors {
        private List<String> allowedOrigins = List.of();

        public List<String> getAllowedOrigins() { return allowedOrigins; }
        public void setAllowedOrigins(List<String> allowedOrigins) {
            this.allowedOrigins = allowedOrigins == null ? List.of() : allowedOrigins;
        }
    }

    /**
     * OIDC / JWT bearer auth settings. Used only when {@code agent.auth.mode=oidc}.
     */
    public static class Oidc {
        /** OIDC issuer (e.g. https://accounts.google.com or https://your-keycloak/realms/x). */
        private String issuerUri;
        /** Optional audience claim — if set, tokens missing it are rejected. */
        private String audience;
        /** Which JWT claim to use as the principal name. One of: sub, preferred_username, email. */
        private String principalClaim = "sub";
        /** Allowed clock skew when validating exp/nbf, in seconds. */
        private long clockSkewSeconds = 30;

        public String getIssuerUri() { return issuerUri; }
        public void setIssuerUri(String issuerUri) { this.issuerUri = issuerUri; }
        public String getAudience() { return audience; }
        public void setAudience(String audience) { this.audience = audience; }
        public String getPrincipalClaim() { return principalClaim; }
        public void setPrincipalClaim(String principalClaim) { this.principalClaim = principalClaim; }
        public long getClockSkewSeconds() { return clockSkewSeconds; }
        public void setClockSkewSeconds(long clockSkewSeconds) { this.clockSkewSeconds = clockSkewSeconds; }
    }
}
