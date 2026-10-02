package io.kestra.plugin.sentry;

import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Consumes;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Post;

/** Stands in for Sentry's ingest endpoint, recording what a request built from a DSN carried. */
@Controller
public class FakeSentryIngestController {
    public static String path;
    public static String sentryKey;
    public static String body;

    @Post("/api/{projectId}/envelope/")
    @Consumes(MediaType.APPLICATION_JSON)
    public HttpResponse<String> envelope(HttpRequest<?> request, @Body String data) {
        return record(request, data);
    }

    @Post("/self-hosted/sentry/api/{projectId}/envelope/")
    @Consumes(MediaType.APPLICATION_JSON)
    public HttpResponse<String> prefixedEnvelope(HttpRequest<?> request, @Body String data) {
        return record(request, data);
    }

    private static HttpResponse<String> record(HttpRequest<?> request, String data) {
        path = request.getPath();
        body = data;
        sentryKey = request.getParameters().get("sentry_key");
        return HttpResponse.ok("{\"id\":\"ok\"}");
    }
}
