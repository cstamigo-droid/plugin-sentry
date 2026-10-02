package io.kestra.plugin.sentry;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.regex.Pattern;

public enum EndpointType {
    ENVELOPE {
        public String getEnvelopeUrl(String dsn) {
            var parsed = parse(dsn);

            return SENTRY_ENVELOPE_URL_TEMPLATE.formatted(parsed.scheme(), parsed.hostAndPath(), parsed.projectId(), SENTRY_VERSION, SENTRY_CLIENT, parsed.publicKey());
        }
    },
    STORE {
        public String getEnvelopeUrl(String dsn) {
            var parsed = parse(dsn);

            return SENTRY_STORE_URL_TEMPLATE.formatted(parsed.scheme(), parsed.hostAndPath(), parsed.projectId(), SENTRY_VERSION, SENTRY_CLIENT, parsed.publicKey());
        }
    };

    /** @deprecated no longer used, the DSN is parsed with {@link URI}. */
    @Deprecated(forRemoval = true, since = "1.3.3")
    public static final String SYMBOL_AT = "@";
    /** @deprecated no longer used, the DSN is parsed with {@link URI}. */
    @Deprecated(forRemoval = true, since = "1.3.3")
    public static final String SYMBOL_FORWARD_SLASH = "/";
    /** @deprecated no longer used, the DSN is parsed with {@link URI}. */
    @Deprecated(forRemoval = true, since = "1.3.3")
    public static final String SYMBOLS_COLON_DOUBLE_FORWARD_SLASH = "://";
    public static final String SENTRY_VERSION = "7";
    public static final String SENTRY_CLIENT = "java";
    public static final String SENTRY_STORE_URL_TEMPLATE = "%s://%s/api/%s/store/?sentry_version=%s&sentry_client=%s&sentry_key=%s";
    public static final String SENTRY_ENVELOPE_URL_TEMPLATE = "%s://%s/api/%s/envelope/?sentry_version=%s&sentry_client=%s&sentry_key=%s";

    private static final Pattern DSN_WITH_USERINFO = Pattern.compile("^(?i:https?)://[^/?#]*@");
    private static final Pattern PUBLIC_KEY = Pattern.compile("[A-Za-z0-9._~-]+");
    private static final Pattern REPEATED_SLASHES = Pattern.compile("/{2,}");
    private static final Pattern TRAILING_SLASHES = Pattern.compile("/+$");
    private static final Pattern INGEST_ENDPOINT_PATH = Pattern.compile(".*/api/[^/]+/(envelope|store)/?$");
    private static final String EXPECTED_FORMAT = "Expected {PROTOCOL}://{PUBLIC_KEY}@{HOST}{PATH}/{PROJECT_ID}.";

    public abstract String getEnvelopeUrl(String dsn);

    static boolean isDsn(String value) {
        return value != null && DSN_WITH_USERINFO.matcher(value).find();
    }

    static String withoutSecretKey(String dsn) {
        if (!isDsn(dsn)) {
            return dsn;
        }

        var parsed = parse(dsn);

        return "%s://%s@%s/%s".formatted(parsed.scheme(), parsed.publicKey(), parsed.hostAndPath(), parsed.projectId());
    }

    private record ParsedDsn(String scheme, String hostAndPath, String projectId, String publicKey) {
    }

    private static ParsedDsn parse(String dsn) {
        // no message here repeats the DSN, nor carries the URI parser's own message that would leak the secret key
        URI uri;
        try {
            uri = new URI(dsn);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("Invalid Sentry DSN: it is not a valid URI. " + EXPECTED_FORMAT);
        }

        var userInfo = uri.getUserInfo();
        if (uri.getHost() == null) {
            throw new IllegalArgumentException(
                "Invalid Sentry DSN: the host cannot be parsed (e.g. it contains an underscore, or the keys contain an unencoded '@'). " + EXPECTED_FORMAT
            );
        }
        if (userInfo == null) {
            throw new IllegalArgumentException("Invalid Sentry DSN: a public key is required. " + EXPECTED_FORMAT);
        }

        // the secret key is kept out of the URL, which gets logged
        var colon = userInfo.indexOf(':');
        var publicKey = colon < 0 ? userInfo : userInfo.substring(0, colon);
        if (publicKey.isEmpty()) {
            throw new IllegalArgumentException("Invalid Sentry DSN: a public key is required. " + EXPECTED_FORMAT);
        }
        // getUserInfo() is decoded, so a key such as abc%26x%3D1 would add parameters to the query string
        if (!PUBLIC_KEY.matcher(publicKey).matches()) {
            throw new IllegalArgumentException("Invalid Sentry DSN: the public key may only contain letters, digits, '.', '_', '~' and '-'. " + EXPECTED_FORMAT);
        }

        var path = uri.getRawPath() == null ? "" : REPEATED_SLASHES.matcher(uri.getRawPath()).replaceAll("/");
        path = TRAILING_SLASHES.matcher(path).replaceAll("");
        if (INGEST_ENDPOINT_PATH.matcher(path).matches()) {
            throw new IllegalArgumentException("Invalid Sentry DSN: it is an ingest endpoint URL, not a DSN. " + EXPECTED_FORMAT);
        }
        var lastSlash = path.lastIndexOf('/');
        var projectId = path.substring(lastSlash + 1);
        if (projectId.isEmpty()) {
            throw new IllegalArgumentException("Invalid Sentry DSN: a project id is required. " + EXPECTED_FORMAT);
        }
        var pathPrefix = lastSlash < 0 ? "" : path.substring(0, lastSlash);

        var hostAndPort = uri.getPort() == -1 ? uri.getHost() : uri.getHost() + ":" + uri.getPort();

        return new ParsedDsn(uri.getScheme().toLowerCase(Locale.ROOT), hostAndPort + pathPrefix, projectId, publicKey);
    }
}
