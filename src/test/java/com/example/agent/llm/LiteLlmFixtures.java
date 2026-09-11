package com.example.agent.llm;

import okhttp3.mockwebserver.MockResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * The gateway responses captured under {@code src/test/resources/fixtures/litellm/}. Each file
 * is a comment header (what was captured, how, and with which command) followed by the HTTP
 * response verbatim: status line, headers, blank line, body. Fixtures are captured from a
 * running LiteLLM, never written by hand — if a test needs a response this directory lacks,
 * capture it and record the command in its header.
 */
public final class LiteLlmFixtures {

    /** HTTP 429, {@code error.type: budget_exceeded}, no Retry-After. */
    public static final String BUDGET_EXCEEDED = "budget_exceeded";
    /** HTTP 429, {@code error.type: throttling_error}, {@code Retry-After: 60}. */
    public static final String RATE_LIMITED = "rate_limited";

    private static final String DIRECTORY = "/fixtures/litellm/";
    private static final String EXTENSION = ".http";
    private static final String COMMENT_PREFIX = "#";
    private static final String STATUS_LINE_SEPARATOR = " ";
    private static final int STATUS_LINE_PARTS = 3;
    private static final char HEADER_SEPARATOR = ':';

    private LiteLlmFixtures() {}

    /** One captured response, as the pieces a test needs. */
    public record Captured(int status, String reasonPhrase, HttpHeaders headers, String body) {

        /** What WebClient's {@code retrieve()} throws for this response. */
        public WebClientResponseException asException() {
            return WebClientResponseException.create(status, reasonPhrase, headers, body.getBytes(UTF_8), UTF_8);
        }

        /** This response served by a MockWebServer (Content-Length is the server's to set). */
        public MockResponse asMockResponse() {
            MockResponse response = new MockResponse().setResponseCode(status).setBody(body);
            headers.forEach((name, values) -> {
                if (!HttpHeaders.CONTENT_LENGTH.equalsIgnoreCase(name)) {
                    values.forEach(value -> response.addHeader(name, value));
                }
            });
            return response;
        }
    }

    public static Captured load(String name) throws IOException {
        String path = DIRECTORY + name + EXTENSION;
        try (InputStream in = LiteLlmFixtures.class.getResourceAsStream(path)) {
            Objects.requireNonNull(in, () -> "no fixture at " + path);
            List<String> lines = new ArrayList<>(new String(in.readAllBytes(), UTF_8).lines().toList());
            int i = 0;
            while (lines.get(i).startsWith(COMMENT_PREFIX)) {
                i++;
            }
            String[] statusLine = lines.get(i++).split(STATUS_LINE_SEPARATOR, STATUS_LINE_PARTS);
            int status = Integer.parseInt(statusLine[1]);
            String reason = statusLine.length == STATUS_LINE_PARTS ? statusLine[2] : "";
            HttpHeaders headers = new HttpHeaders();
            while (i < lines.size() && !lines.get(i).isEmpty()) {
                String header = lines.get(i++);
                int separator = header.indexOf(HEADER_SEPARATOR);
                headers.add(header.substring(0, separator).trim(), header.substring(separator + 1).trim());
            }
            i++;   // the blank line between headers and body
            String body = String.join("\n", lines.subList(i, lines.size())).strip();
            return new Captured(status, reason, headers, body);
        }
    }
}
