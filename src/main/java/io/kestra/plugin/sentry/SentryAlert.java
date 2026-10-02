package io.kestra.plugin.sentry;

import java.io.ByteArrayOutputStream;
import java.io.StringReader;
import java.net.URI;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import io.sentry.JsonSerializer;
import io.sentry.SentryEnvelope;
import io.sentry.SentryEvent;
import io.sentry.SentryOptions;
import io.sentry.protocol.SentryId;

import io.kestra.core.http.HttpRequest;
import io.kestra.core.http.client.HttpClient;
import io.kestra.core.http.client.HttpClientResponseException;
import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.VoidOutput;
import io.kestra.core.runners.RunContext;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.*;
import lombok.experimental.SuperBuilder;

import static io.kestra.core.utils.Rethrow.throwSupplier;
import static java.nio.charset.StandardCharsets.UTF_8;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "Send Sentry alert for failed flow",
    description = "Use in `errors` tasks to notify Sentry when a flow or task fails. Requires a project DSN (see [Sentry DSN guide](https://docs.sentry.io/product/sentry-basics/concepts/dsn-explainer/#where-to-find-your-dsn)); default payload includes execution metadata and generates an event ID if omitted. Uses ENVELOPE ingest by default (STORE optional) and enforces Sentry limits of 1 MB payload / 100 MB envelope; payload format: [Sentry event payloads](https://develop.sentry.dev/sdk/event-payloads/)."
)
@Plugin(
    examples = {
        @Example(
            title = "Send a Sentry alert on a failed flow execution",
            full = true,
            code = """
                id: unreliable_flow
                namespace: company.team

                tasks:
                  - id: fail
                    type: io.kestra.plugin.scripts.shell.Commands
                    runner: PROCESS
                    commands:
                      - exit 1

                errors:
                  - id: alert_on_failure
                    type: io.kestra.plugin.sentry.SentryAlert
                    dsn: "{{ secret('SENTRY_DSN') }}" # format: https://{PUBLIC_KEY}@{HOST}/{PROJECT_ID}
                    endpointType: STORE   # use STORE if ENVELOPE is not enabled
                    options:
                      readTimeout: PT20S
                      headers:
                        X-Flow-Name: "{{ flow.id }}" """
        ),
        @Example(
            title = "Send a custom Sentry alert",
            full = true,
            code = """
                id: sentry_alert
                namespace: company.team

                tasks:
                  - id: send_sentry_message
                    type: io.kestra.plugin.sentry.SentryAlert
                    dsn: "{{ secret('SENTRY_DSN') }}"
                    endpointType: "ENVELOPE"
                    payload: |
                      {
                          "timestamp": "{{ execution.startDate }}",
                          "platform": "java",
                          "level": "error",
                          "transaction": "/execution/id/{{ execution.id }}",
                          "server_name": "localhost:8080",
                          "message": {
                            "message": "Execution {{ execution.id }} failed"
                          },
                          "extra": {
                            "Namespace": "{{ flow.namespace }}",
                            "Flow ID": "{{ flow.id }}",
                            "Execution ID": "{{ execution.id }}",
                            "Link": "http://localhost:8080/ui/executions/{{flow.namespace}}/{{flow.id}}/{{execution.id}}"
                          }
                      }"""
        ),
    },
    aliases = "io.kestra.plugin.notifications.sentry.SentryAlert"
)
public class SentryAlert extends AbstractSentryConnection {
    public static final String SENTRY_VERSION = "7";
    public static final String SENTRY_CLIENT = "java";
    public static final String SENTRY_DATA_MODEL = "event";
    public static final String SENTRY_FILE_NAME = "application.log";
    public static final String SENTRY_CONTENT_TYPE = "application/json";
    /** @deprecated no longer used, any DSN with a public key is turned into its ingest URL. */
    @Deprecated(forRemoval = true, since = "1.3.3")
    public static final String SENTRY_DSN_REGEXP = "^(https?://[a-f0-9]+@o[0-9]+\\.ingest\\.sentry\\.io/[0-9]+)$";
    public static final int PAYLOAD_SIZE_THRESHOLD = 1024 * 1024; // 1MB for events
    public static final int ENVELOP_SIZE_THRESHOLD = 100 * 1024 * 1024; // 100MB decompressed

    private static final String DEFAULT_PAYLOAD = """
        {
          "timestamp": "{{ execution.startDate }}",
          "platform": "java",
          "level": "error",
          "message": {
            "message": "Execution {{ execution.id }} failed"
          },
          "extra": {
            "Namespace": "{{ flow.namespace }}",
            "Flow ID": "{{ flow.id }}",
            "Execution ID": "{{ execution.id }}"
          }
        }""";

    @Schema(
        title = "Sentry DSN",
        description = """
            Project DSN used to authenticate requests, in the Sentry DSN format
            `{PROTOCOL}://{PUBLIC_KEY}[:{SECRET_KEY}]@{HOST}{PATH}/{PROJECT_ID}`, from sentry.io or a self-hosted Sentry;
            the ingest URL is derived from it and the secret key is never sent.
            A value without a public key is used as the ingest URL as is. Keep it in secrets.
            """
    )
    @PluginProperty(dynamic = true, group = "main", secret = true)
    @ToString.Exclude
    @NotBlank
    protected String dsn;

    @Schema(
        title = "Sentry ingest endpoint",
        description = "Defaults to ENVELOPE; switch to STORE if the envelope endpoint is not supported by your Sentry project."
    )
    @PluginProperty(dynamic = true, group = "connection")
    @Builder.Default
    protected EndpointType endpointType = EndpointType.ENVELOPE;

    @Schema(
        title = "Sentry event payload",
        description = "JSON body sent to Sentry; defaults to execution-based template and must stay under Sentry size limits."
    )
    @PluginProperty(group = "main")
    protected Property<String> payload;

    @Override
    public VoidOutput run(RunContext runContext) throws Exception {
        var dsn = runContext.render(this.dsn).trim();

        var url = dsn;
        if (EndpointType.isDsn(dsn)) {
            /*
             * To make passing the correct API endpoint URL easier,
             * users only need to provide the Sentry DSN, and we parse the required attributes for the URL
             * using the following formats:
             * STORE_URL: https://{HOST}/api/{PROJECT_ID}/store/?sentry_version=7&sentry_client=java&sentry_key={PUBLIC_KEY}
             * ENVELOPE_URL: https://{HOST}/api/{PROJECT_ID}/envelope/?sentry_version=7&sentry_client=java&sentry_key={PUBLIC_KEY}
             */
            url = switch (endpointType) {
                case ENVELOPE -> EndpointType.ENVELOPE.getEnvelopeUrl(dsn);
                case STORE -> EndpointType.STORE.getEnvelopeUrl(dsn);
            };
        }

        try (var client = new HttpClient(runContext, super.httpClientConfigurationWithOptions())) {
            var payload = runContext.render(this.payload).as(String.class)
                .orElseGet(throwSupplier(() -> runContext.render(DEFAULT_PAYLOAD.strip())));

            // Constructing the envelope payload
            var envelope = constructEnvelope((String) runContext.getVariables().get("eventId"), payload, dsn);

            // Trying to send to /envelope endpoint
            try {
                runContext.logger().debug("Attempting to send the following Sentry event envelope: {}", redactDsn(redactDsn(envelope, dsn), EndpointType.withoutSecretKey(dsn)));
                var requestBuilder = createRequestBuilder(runContext)
                    .addHeader("Content-Type", "application/json")
                    .uri(URI.create(url))
                    .method("POST")
                    .body(
                        HttpRequest.StringRequestBody.builder()
                            .content(envelope)
                            .build()
                    );

                var request = requestBuilder.build();

                var response = client.request(request, String.class);

                runContext.logger().debug("Response: {}", response.getBody());

                var statusCode = response.getStatus().getCode();
                // The underlying HTTP client only throws on status >= 400; a non-2xx status below
                // that (e.g. a 3xx redirect Sentry doesn't expect) must still fail the task.
                if (statusCode < 200 || statusCode >= 300) {
                    throw new IllegalStateException("Sentry request failed with HTTP status " + statusCode + ": " + response.getBody());
                }

                runContext.logger().info("Request succeeded");
            } catch (HttpClientResponseException exception) {
                var errorCode = Objects.requireNonNull(exception.getResponse()).getStatus().getCode();
                if ((errorCode == 401 || errorCode == 404) && endpointType.equals(EndpointType.ENVELOPE)) {
                    // If the /envelope endpoint is Not Found or Unauthorized ("missing authorization information"), request UI to configure endpointType: store to send the request to /store endpoint.
                    runContext.logger().error("Envelope endpoint not supported; Please try to configure the store endpoint instead: endpointType: store");
                }
                throw exception;
            }
        }

        return null;
    }

    /**
     * Helper method to construct the Envelope formatted payload, headers now coming from the SDK's serializer.
     */
    private String constructEnvelope(String eventId, String payload, String dsn) throws Exception {
        return switch (endpointType) {
            case ENVELOPE -> {
                var sdkBuilt = sdkEnvelope(eventId, payload, dsn);

                // the SDK models a narrower event than Sentry's ingest accepts, so what it rejects still ships as before
                var envelope = Objects.isNull(sdkBuilt) ? legacyEnvelope(eventId, payload, dsn) : sdkBuilt;

                // Check envelope and payload against threshold sizes
                checkEnvelopeAndPayloadThresholds(envelope, payload);

                yield envelope;
            }
            case STORE -> payload;
        };
    }

    /**
     * Helper method to build the envelope through the Sentry SDK, null whenever it cannot carry the payload as is.
     */
    private static String sdkEnvelope(String eventId, String payload, String dsn) {
        var options = new SentryOptions();
        options.setDsn(dsn);

        var serializer = new JsonSerializer(options);

        try {
            var event = serializer.deserialize(new StringReader(payload), SentryEvent.class);
            if (Objects.isNull(event)) {
                return null;
            }

            if (Objects.nonNull(eventId)) {
                // Sentry ids are 32 or 36 characters, the hand written header accepted any string
                event.setEventId(new SentryId(eventId));
            }

            var envelope = SentryEnvelope.from(serializer, event, options.getSdkVersion());

            // the serializer drops an item it cannot write instead of failing, so force each one where it is catchable
            for (var item : envelope.getItems()) {
                if (item.getData().length == 0) {
                    return null;
                }
            }

            try (var out = new ByteArrayOutputStream()) {
                serializer.serialize(envelope, out);

                return out.toString(UTF_8);
            }
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Helper method to build the envelope by hand, for payloads the SDK cannot model.
     */
    private static String legacyEnvelope(String eventId, String payload, String dsn) {
        return "%s%n%s%n%s%n".formatted(getEnvelopeHeaders(eventId, EndpointType.withoutSecretKey(dsn)), getItemHeaders(payload.length()), payload);
    }

    /**
     * Helper method to build envelope headers
     */
    private static String getEnvelopeHeaders(String eventId, String dsn) {
        eventId = Objects.isNull(eventId) ? UUID.randomUUID().toString().toLowerCase().replace("-", "") : eventId;
        String sentAt = Instant.now().toString();
        return "{\"event_id\":\"%s\",\"dsn\":\"%s\",\"sdk\":{\"name\":\"%s\",\"version\":\"%s\"},\"sent_at\":\"%s\"}".formatted(eventId, dsn, SENTRY_CLIENT, SENTRY_VERSION, sentAt);
    }

    /**
     * Helper method to build item headers
     */
    private static String getItemHeaders(int payloadLength) {
        return "{\"type\":\"%s\",\"length\":%d,\"content_type\":\"%s\",\"filename\":\"%s\"}".formatted(SENTRY_DATA_MODEL, payloadLength, SENTRY_CONTENT_TYPE, SENTRY_FILE_NAME);
    }

    /**
     * Helper method to redact the DSN (which may contain the Sentry secret API key) from a string
     * before it is written to logs. Never log the raw envelope/DSN at any level.
     */
    private static String redactDsn(String value, String dsn) {
        if (value == null || dsn == null || dsn.isEmpty()) {
            return value;
        }
        return value.replace(dsn, "***REDACTED***");
    }

    /**
     * Helper method to Check envelope and payload against threshold sizes.
     */
    private static void checkEnvelopeAndPayloadThresholds(String envelope, String payload) {
        // Calculate the size of the envelope
        int envelopeSize = envelope.getBytes(UTF_8).length;
        int payloadSize = payload.getBytes(UTF_8).length;

        // Enforce size limits based on Sentry's documentation
        if (envelopeSize > ENVELOP_SIZE_THRESHOLD) {
            throw new IllegalArgumentException("Envelope size exceeds 100MB limit for decompressed data");
        }

        if (payloadSize > PAYLOAD_SIZE_THRESHOLD) {
            throw new IllegalArgumentException("Event payload size exceeds 1MB limit");
        }
    }
}
