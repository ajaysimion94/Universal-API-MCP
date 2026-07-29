package com.mcpserver.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Builds outbound HTTP clients with the normal JVM roots plus enterprise trust sources.
 *
 * <p>Java does not use the Windows Trusted Root Certification Authorities store by default.
 * Browsers and Postman commonly do, which produces the confusing "works in Postman, PKIX in Java"
 * failure. On Windows we add that store automatically. Administrators can also supply one or more
 * PEM/DER CA files without modifying the JDK's global {@code cacerts} file.
 *
 * <p>An explicit emergency switch can bypass certificate-chain validation for parity with
 * Postman's "SSL certificate verification" toggle. It is disabled by default, emits a prominent
 * warning, and must never be used outside a controlled troubleshooting session. Hostname
 * verification remains enabled.
 */
@Component
public class TlsHttpClientFactory {

    private static final Logger log = LoggerFactory.getLogger(TlsHttpClientFactory.class);

    private final SSLContext sslContext;

    public TlsHttpClientFactory(
            @Value("${http.tls.ca-certificate-paths:}") String caCertificatePaths,
            @Value("${http.tls.use-windows-root-store:true}") boolean useWindowsRootStore,
            @Value("${http.tls.disable-certificate-validation:false}")
            boolean disableCertificateValidation) {
        this.sslContext = disableCertificateValidation
                ? buildTrustAllSslContext()
                : buildSslContext(caCertificatePaths, useWindowsRootStore);
    }

    public HttpClient.Builder builder() {
        return HttpClient.newBuilder().sslContext(sslContext);
    }

    private SSLContext buildTrustAllSslContext() {
        try {
            log.warn("SECURITY WARNING: outbound TLS certificate-chain validation is DISABLED. "
                    + "Use MCP_TLS_DISABLE_VERIFY only for controlled troubleshooting.");
            X509TrustManager trustAll = new X509TrustManager() {
                @Override
                public void checkClientTrusted(X509Certificate[] chain, String authType) {
                }

                @Override
                public void checkServerTrusted(X509Certificate[] chain, String authType) {
                }

                @Override
                public X509Certificate[] getAcceptedIssuers() {
                    return new X509Certificate[0];
                }
            };
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, new TrustManager[]{trustAll}, null);
            return context;
        } catch (Exception e) {
            throw new IllegalStateException("Could not initialize TLS bypass: " + e.getMessage(), e);
        }
    }

    private SSLContext buildSslContext(String configuredPaths, boolean useWindowsRootStore) {
        try {
            List<X509TrustManager> managers = new ArrayList<>();
            managers.add(trustManager(null));

            if (useWindowsRootStore && isWindows()) {
                try {
                    KeyStore windowsRoots = KeyStore.getInstance("Windows-ROOT");
                    windowsRoots.load(null, null);
                    managers.add(trustManager(windowsRoots));
                    log.info("Outbound TLS trusts the Windows root certificate store");
                } catch (Exception e) {
                    log.warn("Windows root certificate store is unavailable; JVM and explicitly "
                            + "configured CA roots remain active: {}", e.getMessage());
                }
            }

            List<Path> paths = certificatePaths(configuredPaths);
            if (!paths.isEmpty()) {
                KeyStore additionalRoots = KeyStore.getInstance(KeyStore.getDefaultType());
                additionalRoots.load(null, null);
                CertificateFactory certificates = CertificateFactory.getInstance("X.509");
                int index = 0;
                for (Path path : paths) {
                    if (!Files.isRegularFile(path)) {
                        throw new IllegalArgumentException("TLS CA certificate file does not exist: " + path);
                    }
                    try (InputStream input = Files.newInputStream(path)) {
                        Collection<? extends Certificate> parsed = certificates.generateCertificates(input);
                        if (parsed.isEmpty()) {
                            throw new IllegalArgumentException("No X.509 certificate found in " + path);
                        }
                        for (Certificate certificate : parsed) {
                            additionalRoots.setCertificateEntry("configured-ca-" + index++, certificate);
                        }
                    }
                }
                managers.add(trustManager(additionalRoots));
                log.info("Outbound TLS loaded {} additional CA certificate(s) from {} file(s)",
                        index, paths.size());
            }

            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, new TrustManager[]{new CompositeTrustManager(managers)}, null);
            return context;
        } catch (Exception e) {
            throw new IllegalStateException("Could not initialize outbound TLS trust: " + e.getMessage(), e);
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private static List<Path> certificatePaths(String configured) {
        if (configured == null || configured.isBlank()) return List.of();
        return Arrays.stream(configured.split(","))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .map(Path::of)
                .toList();
    }

    private static X509TrustManager trustManager(KeyStore store) throws Exception {
        TrustManagerFactory factory =
                TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        factory.init(store);
        return Arrays.stream(factory.getTrustManagers())
                .filter(X509TrustManager.class::isInstance)
                .map(X509TrustManager.class::cast)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No X.509 trust manager available"));
    }

    private static final class CompositeTrustManager implements X509TrustManager {
        private final List<X509TrustManager> delegates;

        private CompositeTrustManager(List<X509TrustManager> delegates) {
            this.delegates = List.copyOf(delegates);
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType)
                throws CertificateException {
            check(chain, authType, true);
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType)
                throws CertificateException {
            check(chain, authType, false);
        }

        private void check(X509Certificate[] chain, String authType, boolean client)
                throws CertificateException {
            CertificateException failure = null;
            for (X509TrustManager delegate : delegates) {
                try {
                    if (client) delegate.checkClientTrusted(chain, authType);
                    else delegate.checkServerTrusted(chain, authType);
                    return;
                } catch (CertificateException e) {
                    failure = e;
                }
            }
            throw failure != null ? failure : new CertificateException("Certificate chain is not trusted");
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            Set<X509Certificate> issuers = new LinkedHashSet<>();
            for (X509TrustManager delegate : delegates) {
                issuers.addAll(Arrays.asList(delegate.getAcceptedIssuers()));
            }
            return issuers.toArray(X509Certificate[]::new);
        }
    }
}
