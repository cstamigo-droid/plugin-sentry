# How to use the Sentry plugin

Capture errors and send execution summaries to Sentry from Kestra flows.

## Authentication

Set `dsn` to your Sentry project's DSN (found in Settings → Projects → Client Keys). Store it in a [secret](https://kestra.io/docs/concepts/secret).

Any DSN in the `{PROTOCOL}://{PUBLIC_KEY}[:{SECRET_KEY}]@{HOST}{PATH}/{PROJECT_ID}` format works, from sentry.io or a self-hosted Sentry (a path prefix and a port are kept). Only the public key is sent, in the ingest URL and in the envelope; the secret key is never sent. A DSN with a public key that cannot be parsed fails the task with an `Invalid Sentry DSN` error.

## Tasks

`SentryAlert` sends an event to Sentry as a step within a flow — set `payload` to a JSON body in the Sentry event format, or omit it to send a default alert. Set `endpointType` to `ENVELOPE` (the default) or `STORE` to match your Sentry SDK configuration.

`SentryExecution` sends a structured execution summary including status, duration, and an execution link, and is designed for use with a [Flow trigger](https://kestra.io/docs/workflow-components/triggers) in a dedicated monitoring namespace that watches other namespaces for failures.

## Envelope framing

On `ENVELOPE`, the event body is framed by the Sentry SDK when your `payload` parses as a Sentry event, and by the plugin's own framing when it does not. A bare string `message` is the common case the SDK will not model, since it expects an object.

The two differ on the wire, which matters when reading a request capture or a proxy log:

| | SDK framing | plugin framing |
| --- | --- | --- |
| `sdk.name` in the envelope header | `sentry.java` | `java` |
| DSN in the envelope header | absent | present |

Either way the event reaches Sentry identically. `STORE` posts the payload unframed and is unaffected.
