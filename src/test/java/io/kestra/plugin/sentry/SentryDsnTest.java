package io.kestra.plugin.sentry;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContext;
import io.kestra.core.runners.RunContextFactory;
import io.kestra.core.utils.IdUtils;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.micronaut.context.ApplicationContext;
import io.micronaut.runtime.server.EmbeddedServer;
import jakarta.inject.Inject;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertThrows;

// FakeSentryIngestController records into static fields, so these tests must not run in parallel
@KestraTest
@Execution(ExecutionMode.SAME_THREAD)
class SentryDsnTest {
    private static final String PUBLIC_KEY = "0123456789abcdef0123456789abcdef";
    private static final String SECRET_KEY = "fedcba9876543210fedcba9876543210";

    @Inject
    private ApplicationContext applicationContext;

    @Inject
    private RunContextFactory runContextFactory;

    private String hostPort;

    @BeforeEach
    void startServer() {
        var server = applicationContext.getBean(EmbeddedServer.class);
        server.start();
        hostPort = server.getURI().getHost() + ":" + server.getURI().getPort();
        FakeSentryIngestController.path = null;
        FakeSentryIngestController.sentryKey = null;
        FakeSentryIngestController.body = null;
    }

    private void send(String dsn) throws Exception {
        send(dsn, "{\"message\":{\"message\":\"Execution failed\"}}");
    }

    private void send(String dsn, String payload) throws Exception {
        send(dsn, payload, runContextFactory.of());
    }

    private void send(String dsn, String payload, RunContext runContext) throws Exception {
        SentryAlert.builder()
            .id(IdUtils.create())
            .type(SentryAlert.class.getName())
            .dsn(dsn)
            .endpointType(EndpointType.ENVELOPE)
            .payload(Property.ofValue(payload))
            .build()
            .run(runContext);
    }

    @Test
    @DisplayName("A DSN with a secret key reaches the ingest endpoint with only the public key")
    void dsnWithSecretKey() throws Exception {
        send("http://" + PUBLIC_KEY + ":" + SECRET_KEY + "@" + hostPort + "/42");

        assertThat(FakeSentryIngestController.path, is("/api/42/envelope/"));
        assertThat(FakeSentryIngestController.sentryKey, is(PUBLIC_KEY));
    }

    @Test
    @DisplayName("A self-hosted DSN on another host is turned into its ingest endpoint")
    void selfHostedDsn() throws Exception {
        send("http://" + PUBLIC_KEY + "@" + hostPort + "/42");

        assertThat(FakeSentryIngestController.path, is("/api/42/envelope/"));
        assertThat(FakeSentryIngestController.sentryKey, is(PUBLIC_KEY));
    }

    @Test
    @DisplayName("A DSN with a path prefix keeps the prefix in front of /api")
    void dsnWithPathPrefix() throws Exception {
        send("http://" + PUBLIC_KEY + "@" + hostPort + "/self-hosted/sentry/42");

        assertThat(FakeSentryIngestController.path, is("/self-hosted/sentry/api/42/envelope/"));
        assertThat(FakeSentryIngestController.sentryKey, is(PUBLIC_KEY));
    }

    @Test
    @DisplayName("A sentry.io DSN still maps to the same ingest URL")
    void sentryIoDsnIsUnchanged() {
        assertThat(
            EndpointType.ENVELOPE.getEnvelopeUrl("https://" + PUBLIC_KEY + "@o123.ingest.sentry.io/456"),
            is("https://o123.ingest.sentry.io/api/456/envelope/?sentry_version=7&sentry_client=java&sentry_key=" + PUBLIC_KEY)
        );
        assertThat(
            EndpointType.STORE.getEnvelopeUrl("https://" + PUBLIC_KEY + "@o123.ingest.sentry.io/456"),
            is("https://o123.ingest.sentry.io/api/456/store/?sentry_version=7&sentry_client=java&sentry_key=" + PUBLIC_KEY)
        );
    }

    @Test
    @DisplayName("A sentry.io DSN on a regional ingest host maps to that host")
    void regionalSentryIoDsn() {
        assertThat(
            EndpointType.ENVELOPE.getEnvelopeUrl("https://" + PUBLIC_KEY + "@o123.ingest.us.sentry.io/456"),
            is("https://o123.ingest.us.sentry.io/api/456/envelope/?sentry_version=7&sentry_client=java&sentry_key=" + PUBLIC_KEY)
        );
    }

    @Test
    @DisplayName("Surrounding blanks, doubled and trailing slashes in a DSN are ignored")
    void dsnIsNormalized() throws Exception {
        send("  http://" + PUBLIC_KEY + "@" + hostPort + "//42/  ");

        assertThat(FakeSentryIngestController.path, is("/api/42/envelope/"));
        assertThat(FakeSentryIngestController.sentryKey, is(PUBLIC_KEY));
    }

    @Test
    @DisplayName("A DSN that cannot be parsed fails as an invalid DSN, without repeating the secret key")
    void invalidDsnFailsWithoutLeakingTheSecret() {
        String[] invalidDsns = {
            "http://" + PUBLIC_KEY + ":" + SECRET_KEY + "@sentry_web/42", // host that URI cannot parse
            "http://" + PUBLIC_KEY + ":" + SECRET_KEY + "@x@" + hostPort + "/42", // unencoded @ in the secret key
            "http://" + PUBLIC_KEY + ":" + SECRET_KEY + "@" + hostPort + "/api/42/envelope/", // an ingest URL, not a DSN
            "http://:" + SECRET_KEY + "@" + hostPort + "/42", // no public key
            "http://" + PUBLIC_KEY + ":" + SECRET_KEY + "@" + hostPort + "/", // no project id
            "http://" + PUBLIC_KEY + ":" + SECRET_KEY + "@" + hostPort + "/4 2" // not a URI
        };

        for (var dsn : invalidDsns) {
            var exception = assertThrows(IllegalArgumentException.class, () -> send(dsn), dsn);

            assertThat(dsn, exception.getMessage(), startsWith("Invalid Sentry DSN"));
            assertThat(dsn, exception.getMessage(), not(containsString(SECRET_KEY)));
            assertThat(dsn, exception.getCause(), is((Throwable) null));
        }
    }

    @Test
    @DisplayName("The secret key is not sent in the envelope, whichever builder writes it")
    void secretKeyIsNotInTheEnvelope() throws Exception {
        var dsn = "http://" + PUBLIC_KEY + ":" + SECRET_KEY + "@" + hostPort + "/42";

        // a bare string message is written by the legacy builder, which puts the DSN in the envelope header
        send(dsn, "{\"message\":\"just a string\"}");
        assertThat(FakeSentryIngestController.body, containsString("\"dsn\":\"http://" + PUBLIC_KEY + "@" + hostPort + "/42\""));
        assertThat(FakeSentryIngestController.body, not(containsString(SECRET_KEY)));

        send(dsn);
        assertThat(FakeSentryIngestController.body, containsString("sentry.java"));
        assertThat(FakeSentryIngestController.body, not(containsString(SECRET_KEY)));
    }

    @Test
    @DisplayName("An encoded colon between the keys still keeps the secret key out of the request")
    void encodedColonInUserinfo() throws Exception {
        send("http://" + PUBLIC_KEY + "%3A" + SECRET_KEY + "@" + hostPort + "/42");

        assertThat(FakeSentryIngestController.sentryKey, is(PUBLIC_KEY));
        assertThat(FakeSentryIngestController.body, not(containsString(SECRET_KEY)));
    }

    @Test
    @DisplayName("A secret key with an unencoded / is not read as a DSN, and the failure does not repeat it")
    void unencodedSlashInTheSecretKey() {
        var exception = assertThrows(Exception.class, () -> send("http://" + PUBLIC_KEY + ":" + SECRET_KEY + "/x@" + hostPort + "/42"));

        assertThat(exception.getMessage(), not(containsString(SECRET_KEY)));
    }

    @Test
    @DisplayName("An @ in the path of an ingest URL does not make it a DSN")
    void atSignInThePathIsNotUserinfo() {
        assertThat(EndpointType.isDsn("https://relay/api/1/envelope/@x"), is(false));
        assertThat(EndpointType.isDsn("https://" + PUBLIC_KEY + "@relay/1"), is(true));
    }

    @Test
    @DisplayName("Trailing slash, uppercase scheme, port, path prefix and IPv6 host all map to the ingest URL")
    void ingestUrlEdgeCases() {
        var query = "?sentry_version=7&sentry_client=java&sentry_key=" + PUBLIC_KEY;

        assertThat(EndpointType.ENVELOPE.getEnvelopeUrl("https://" + PUBLIC_KEY + "@sentry.example.com/42/"), is("https://sentry.example.com/api/42/envelope/" + query));
        assertThat(EndpointType.ENVELOPE.getEnvelopeUrl("HTTPS://" + PUBLIC_KEY + "@sentry.example.com/42"), is("https://sentry.example.com/api/42/envelope/" + query));
        assertThat(EndpointType.ENVELOPE.getEnvelopeUrl("https://" + PUBLIC_KEY + "@sentry.example.com:9000/sentry/42"), is("https://sentry.example.com:9000/sentry/api/42/envelope/" + query));
        assertThat(EndpointType.ENVELOPE.getEnvelopeUrl("http://" + PUBLIC_KEY + "@[::1]:9000/42"), is("http://[::1]:9000/api/42/envelope/" + query));
        assertThat(EndpointType.STORE.getEnvelopeUrl("https://" + PUBLIC_KEY + "@sentry.example.com/sentry/42"), is("https://sentry.example.com/sentry/api/42/store/" + query));
    }

    @Test
    @DisplayName("A public key that would need percent-encoding in the query string is rejected as an invalid DSN")
    void keyThatWouldInjectQueryParameters() {
        String[] invalidDsns = {
            "https://abc%26sentry_version%3D1@sentry.example.com/42", // & and = once decoded
            "https://abc%26sentry_version%3D1:" + SECRET_KEY + "@sentry.example.com/42",
            "https://abc%23x@sentry.example.com/42" // # once decoded
        };

        for (var dsn : invalidDsns) {
            var exception = assertThrows(IllegalArgumentException.class, () -> EndpointType.ENVELOPE.getEnvelopeUrl(dsn), dsn);
            assertThat(dsn, exception.getMessage(), startsWith("Invalid Sentry DSN"));

            exception = assertThrows(IllegalArgumentException.class, () -> EndpointType.withoutSecretKey(dsn), dsn);
            assertThat(dsn, exception.getMessage(), startsWith("Invalid Sentry DSN"));
        }
    }

    @Test
    @DisplayName("The DEBUG envelope log redacts the DSN without its secret key")
    void debugLogRedactsTheDsnWithoutTheSecretKey() throws Exception {
        var runContext = runContextFactory.of();
        var logger = (Logger) runContext.logger();
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        var previousLevel = logger.getLevel();
        logger.setLevel(Level.DEBUG);

        var dsn = "http://" + PUBLIC_KEY + ":" + SECRET_KEY + "@" + hostPort + "/42";
        try {
            // a bare string message is written by the legacy builder, which puts the DSN without the secret key in the header
            send(dsn, "{\"message\":\"just a string\"}", runContext);
            // a structured message is written by the SDK, whose header carries no DSN
            send(dsn, "{\"message\":{\"message\":\"Execution failed\"}}", runContext);
        } finally {
            logger.detachAppender(appender);
            logger.setLevel(previousLevel);
        }

        var envelopeLogs = appender.list.stream()
            .map(ILoggingEvent::getFormattedMessage)
            .filter(message -> message.startsWith("Attempting to send"))
            .toList();
        assertThat(envelopeLogs.size(), is(2));
        assertThat(envelopeLogs.getFirst(), containsString("***REDACTED***"));
        for (var log : envelopeLogs) {
            assertThat(log, not(containsString(PUBLIC_KEY)));
            assertThat(log, not(containsString(SECRET_KEY)));
        }
    }
}
