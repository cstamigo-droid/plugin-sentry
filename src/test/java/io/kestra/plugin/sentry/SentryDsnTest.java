package io.kestra.plugin.sentry;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContextFactory;
import io.kestra.core.utils.IdUtils;

import io.micronaut.context.ApplicationContext;
import io.micronaut.runtime.server.EmbeddedServer;
import jakarta.inject.Inject;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * DSNs other than the {@code https://key@oN.ingest.sentry.io/N} shape used to be posted as-is, userinfo included.
 */
@KestraTest
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
        EmbeddedServer server = applicationContext.getBean(EmbeddedServer.class);
        server.start();
        hostPort = server.getURI().getHost() + ":" + server.getURI().getPort();
        FakeSentryIngestController.path = null;
        FakeSentryIngestController.sentryKey = null;
    }

    private void send(String dsn) throws Exception {
        SentryAlert.builder()
            .id(IdUtils.create())
            .type(SentryAlert.class.getName())
            .dsn(dsn)
            .endpointType(EndpointType.ENVELOPE)
            .payload(Property.ofValue("{\"message\":{\"message\":\"Execution failed\"}}"))
            .build()
            .run(runContextFactory.of());
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
            "http://" + PUBLIC_KEY + ":" + SECRET_KEY + "/x@" + hostPort + "/42", // unencoded / in the secret key
            "http://" + PUBLIC_KEY + ":" + SECRET_KEY + "@" + hostPort + "/api/42/envelope/", // an ingest URL, not a DSN
            "http://:" + SECRET_KEY + "@" + hostPort + "/42", // no public key
            "http://" + PUBLIC_KEY + ":" + SECRET_KEY + "@" + hostPort + "/", // no project id
            "http://" + PUBLIC_KEY + ":" + SECRET_KEY + "@" + hostPort + "/4 2" // not a URI
        };

        for (String dsn : invalidDsns) {
            IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> send(dsn), dsn);

            assertThat(dsn, exception.getMessage(), startsWith("Invalid Sentry DSN"));
            assertThat(dsn, exception.getMessage(), not(containsString(SECRET_KEY)));
            assertThat(dsn, exception.getCause(), is((Throwable) null));
        }
    }
}
