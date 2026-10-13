package io.kestra.plugin.sentry;

import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContextFactory;
import io.kestra.core.serializers.JacksonMapper;
import io.kestra.core.utils.IdUtils;

import io.micronaut.context.ApplicationContext;
import io.micronaut.runtime.server.EmbeddedServer;
import jakarta.inject.Inject;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;

/**
 * Pins which envelope builder a payload selects, since the SDK models a narrower event than Sentry's ingest accepts.
 */
@KestraTest
class SentryEnvelopeSourceTest {
    @Inject
    private ApplicationContext applicationContext;

    @Inject
    private RunContextFactory runContextFactory;

    private String send(String payload) throws Exception {
        return send(payload, Map.of());
    }

    private String send(String payload, Map<String, Object> variables) throws Exception {
        EmbeddedServer server = applicationContext.getBean(EmbeddedServer.class);
        server.start();

        SentryAlert.builder()
            .id(IdUtils.create())
            .type(SentryAlert.class.getName())
            .dsn(server.getURI() + "/webhook-unit-test")
            .endpointType(EndpointType.ENVELOPE)
            .payload(Property.ofValue(payload))
            .build()
            .run(runContextFactory.of(variables));

        return FakeWebhookController.data;
    }

    @Test
    @DisplayName("A readable event is serialized by the SDK")
    void sdkBuildsTheEnvelopeForAReadableEvent() throws Exception {
        var body = send("{\"platform\":\"java\",\"level\":\"error\",\"message\":{\"message\":\"Execution failed\"}}");

        // the SDK stamps its own sdk name, the hand written header used "java"
        assertThat(body, containsString("sentry.java"));
        assertThat(body, containsString("\"type\":\"event\""));
        // the SDK never puts the DSN in the envelope header, so the secret stops travelling in the body
        assertThat(body, not(containsString("webhook-unit-test\"")));
        assertThat(body, startsWith("{\"event_id\""));
    }

    @Test
    @DisplayName("A payload the SDK cannot model still ships through the legacy envelope")
    void legacyEnvelopeCarriesWhatTheSdkCannotModel() throws Exception {
        // Sentry accepts a bare string message, the SDK's Message type does not
        var body = send("{\"message\":\"just a string\"}");

        assertThat(body, containsString("just a string"));
        assertThat(body, containsString("\"name\":\"java\""));
        assertThat(body, containsString("application.log"));
    }

    @Test
    @DisplayName("The legacy envelope declares the payload length in UTF-8 bytes")
    void shouldDeclareLengthInUtf8BytesWhenPayloadHasNonAsciiText() throws Exception {
        // accents and an emoji take more UTF-8 bytes than Java chars, a char count makes Sentry answer 400
        var payload = "{\"message\":\"Ejecución fallida en ñandú 🚀\"}";

        var body = send(payload);

        assertThat(readItemPayload(body), is(payload));
    }

    @Test
    @DisplayName("The legacy envelope separates its lines with a lone \\n on every OS")
    void shouldSeparateLinesWithLineFeedWhenEnvelopeIsBuiltByHand() throws Exception {
        var payload = "{\"message\":\"just a string\"}";

        var body = send(payload);

        // a Windows line separator (\r\n) makes Sentry answer 400 "missing newline after header or payload"
        assertThat(body, not(containsString("\r")));
        assertThat(readItemPayload(body), is(payload));
    }

    @Test
    @DisplayName("A well formed eventId is carried by the SDK envelope")
    void sdkEnvelopeCarriesAWellFormedEventId() throws Exception {
        var eventId = "fc6d8c0c43fc4630ad850ee518f1b9d0";

        var body = send("{\"message\":{\"message\":\"hi\"}}", Map.of("eventId", eventId));

        assertThat(body, containsString(eventId));
        assertThat(body, containsString("sentry.java"));
    }

    @Test
    @DisplayName("An eventId the SDK rejects falls back rather than failing the task")
    void malformedEventIdFallsBackInsteadOfThrowing() throws Exception {
        // Sentry ids must be 32 or 36 characters, the hand written header accepted any string
        var body = send("{\"message\":{\"message\":\"hi\"}}", Map.of("eventId", "not-a-valid-sentry-id"));

        assertThat(body, containsString("not-a-valid-sentry-id"));
        assertThat(body, containsString("\"name\":\"java\""));
    }

    /**
     * Reads the single item of an envelope the way Sentry does: two header lines ended by \n,
     * then exactly `length` bytes of payload, requiring the trailing \n this envelope always writes.
     */
    private static String readItemPayload(String envelope) throws Exception {
        var bytes = envelope.getBytes(UTF_8);
        var envelopeHeaderEnd = indexOf(bytes, 0);
        var itemHeaderEnd = indexOf(bytes, envelopeHeaderEnd + 1);
        var itemHeader = new String(bytes, envelopeHeaderEnd + 1, itemHeaderEnd - envelopeHeaderEnd - 1, UTF_8);

        var length = JacksonMapper.ofJson().readTree(itemHeader).get("length").asInt();
        var payloadStart = itemHeaderEnd + 1;
        assertThat("envelope ends before the declared length", payloadStart + length < bytes.length, is(true));
        assertThat("payload must be followed by \\n", bytes[payloadStart + length], is((byte) '\n'));

        return new String(bytes, payloadStart, length, UTF_8);
    }

    private static int indexOf(byte[] bytes, int from) {
        for (var i = from; i < bytes.length; i++) {
            if (bytes[i] == '\n') {
                return i;
            }
        }
        throw new AssertionError("no \\n after byte " + from);
    }
}
