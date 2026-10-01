package io.kestra.plugin.sentry;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.regex.Pattern;

public enum EndpointType {
    ENVELOPE {
        public String getEnvelopeUrl(String dsn) {
            ParsedDsn parsed = parse(dsn);

            return SENTRY_ENVELOPE_URL_TEMPLATE.formatted(parsed.scheme(), parsed.hostAndPath(), parsed.projectId(), SENTRY_VERSION, SENTRY_CLIENT, parsed.publicKey());
        }
    },
    STORE {
        public String getEnvelopeUrl(String dsn) {
            ParsedDsn parsed = parse(dsn);

            return SENTRY_STORE_URL_TEMPLATE.formatted(parsed.scheme(), parsed.hostAndPath(), parsed.projectId(), SENTRY_VERSION, SENTRY_CLIENT, parsed.publicKey());
        }
    };

    /** @deprecated no longer used, the DSN is parsed with {@link URI}. */
    @Deprecated
    public static final String SYMBOL_AT = "@";
    /** @deprecated no longer used, the DSN is parsed with {@link URI}. */
    @Deprecated
    public static final String SYMBOL_FORWARD_SLASH = "/";
    /** @deprecated no longer used, the DSN is parsed with {@link URI}. */
    @Deprecated
    public static final String SYMBOLS_COLON_DOUBLE_FORWARD_SLASH = "://";
    public static final String SENTRY_VERSION = "7";
    public static final String SENTRY_CLIENT = "java";
    public static final String SENTRY_STORE_URL_TEMPLATE = "%s://%s/api/%s/store/?sentry_version=%s&sentry_client=%s&sentry_key=%s";
    public static final String SENTRY_ENVELOPE_URL_TEMPLATE = "%s://%s/api/%s/envelope/?sentry_version=%s&sentry_client=%s&sentry_key=%s";

    // userinfo is anything before an @ ahead of the query or fragment, so a secret key with an unencoded / is still caught
    private static final Pattern DSN_WITH_USERINFO = Pattern.compile("^(?i:https?)://[^?#]*@");
    private static final Pattern INGEST_ENDPOINT_PATH = Pattern.compile(".*/api/[^/]+/(envelope|store)/?$");
    private static final String EXPECTED_FORMAT = "Expected {PROTOCOL}://{PUBLIC_KEY}@{HOST}{PATH}/{PROJECT_ID}.";

    public abstract String getEnvelopeUrl(String dsn);

    /**
     * A DSN carries its public key as userinfo: {@code {PROTOCOL}://{PUBLIC_KEY}[:{SECRET_KEY}]@{HOST}{PATH}/{PROJECT_ID}}.
     * A value without userinfo is not a DSN, it is the endpoint URL itself.
     */
    static boolean isDsn(String value) {
        // checked on the raw string, not with URI: a DSN whose host URI cannot parse must still fail as an invalid DSN
        return value != null && DSN_WITH_USERINFO.matcher(value).find();
    }

    private record ParsedDsn(String scheme, String hostAndPath, String projectId, String publicKey) {
    }

    private static ParsedDsn parse(String dsn) {
        // the DSN holds the secret key, so no message here repeats it, nor carries the URI parser's own message that would
        URI uri;
        try {
            uri = new URI(dsn);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("Invalid Sentry DSN: it is not a valid URI. " + EXPECTED_FORMAT);
        }

        String userInfo = uri.getRawUserInfo();
        if (uri.getHost() == null) {
            throw new IllegalArgumentException(
                "Invalid Sentry DSN: the host cannot be parsed (e.g. it contains an underscore, or the keys contain an unencoded '@' or '/'). " + EXPECTED_FORMAT
            );
        }
        if (userInfo == null) {
            throw new IllegalArgumentException("Invalid Sentry DSN: a public key is required. " + EXPECTED_FORMAT);
        }

        // the secret key is optional and effectively deprecated in the DSN format; it is not put in the URL, where it would be logged
        int colon = userInfo.indexOf(':');
        String publicKey = colon < 0 ? userInfo : userInfo.substring(0, colon);
        if (publicKey.isEmpty()) {
            throw new IllegalArgumentException("Invalid Sentry DSN: a public key is required. " + EXPECTED_FORMAT);
        }

        // the project id is the last path segment, anything before it is a path prefix (e.g. a self-hosted Sentry behind /sentry)
        String path = uri.getRawPath() == null ? "" : uri.getRawPath().replaceAll("/{2,}", "/").replaceAll("/+$", "");
        if (INGEST_ENDPOINT_PATH.matcher(path).matches()) {
            throw new IllegalArgumentException("Invalid Sentry DSN: it is an ingest endpoint URL, not a DSN. " + EXPECTED_FORMAT);
        }
        int lastSlash = path.lastIndexOf('/');
        String projectId = path.substring(lastSlash + 1);
        if (projectId.isEmpty()) {
            throw new IllegalArgumentException("Invalid Sentry DSN: a project id is required. " + EXPECTED_FORMAT);
        }
        String pathPrefix = lastSlash < 0 ? "" : path.substring(0, lastSlash);

        String hostAndPort = uri.getPort() == -1 ? uri.getHost() : uri.getHost() + ":" + uri.getPort();

        return new ParsedDsn(uri.getScheme(), hostAndPort + pathPrefix, projectId, publicKey);
    }
}
