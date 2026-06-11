package pj.gob.pe.consultaia.service.externals.impl;

import com.google.api.client.http.ByteArrayContent;
import com.google.api.client.http.GenericUrl;
import com.google.api.client.http.HttpHeaders;
import com.google.api.client.http.HttpRequest;
import com.google.api.client.http.HttpRequestFactory;
import com.google.api.client.http.HttpResponse;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.auth.oauth2.GoogleCredentials;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import pj.gob.pe.consultaia.configuration.ConfigProperties;
import pj.gob.pe.consultaia.service.externals.GcsStorageService;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.UUID;

/**
 * Implementación REST cruda (JSON API de Cloud Storage) para subir y borrar el PDF de la demanda,
 * reutilizando el transporte con proxy y las credenciales del service account. Ver
 * {@link GcsStorageService} para el porqué de la estrategia.
 */
@Service
@RequiredArgsConstructor
public class GcsStorageServiceImpl implements GcsStorageService {

    private static final Logger logger = LoggerFactory.getLogger(GcsStorageServiceImpl.class);

    private static final String GCS_BASE = "https://storage.googleapis.com";

    private final ConfigProperties properties;

    /** Credenciales cacheadas: el token OAuth (~1h) se reutiliza entre subidas. */
    private volatile GoogleCredentials cachedCredentials;

    @Override
    public String subir(byte[] contenido, String contentType) throws IOException {
        String objectName = properties.getGcpGcsObjectPrefix() + "/" + UUID.randomUUID() + extensionDe(contentType);
        String bucket = properties.getGcpGcsBucket();

        String url = GCS_BASE + "/upload/storage/v1/b/" + bucket + "/o?uploadType=media&name="
                + URLEncoder.encode(objectName, StandardCharsets.UTF_8);

        NetHttpTransport transport = buildHttpTransport();
        HttpRequestFactory factory = transport.createRequestFactory();
        HttpRequest request = factory.buildPostRequest(
                new GenericUrl(url), new ByteArrayContent(contentType, contenido));

        HttpResponse response = ejecutar(request, "subida a gs://" + bucket + "/" + objectName);
        try {
            if (response.getStatusCode() >= 300) {
                throw new IOException("Error subiendo a GCS (" + response.getStatusCode() + "): "
                        + response.parseAsString());
            }
        } finally {
            response.disconnect();
        }

        String gsUri = "gs://" + bucket + "/" + objectName;
        logger.info("[GCS] PDF subido: {} ({} bytes)", gsUri, contenido.length);
        return gsUri;
    }

    @Override
    public void borrar(String gsUri) throws IOException {
        if (gsUri == null || !gsUri.startsWith("gs://")) {
            return;
        }
        String sinEsquema = gsUri.substring("gs://".length());
        int barra = sinEsquema.indexOf('/');
        if (barra < 0) {
            return;
        }
        String bucket = sinEsquema.substring(0, barra);
        String objectName = sinEsquema.substring(barra + 1);

        String url = GCS_BASE + "/storage/v1/b/" + bucket + "/o/"
                + URLEncoder.encode(objectName, StandardCharsets.UTF_8);

        NetHttpTransport transport = buildHttpTransport();
        HttpRequestFactory factory = transport.createRequestFactory();
        HttpRequest request = factory.buildDeleteRequest(new GenericUrl(url));

        HttpResponse response = ejecutar(request, "borrado de " + gsUri);
        try {
            // 204/200 = borrado; 404 = ya no existe (idempotente, lo tratamos como éxito).
            int status = response.getStatusCode();
            if (status >= 300 && status != 404) {
                throw new IOException("Error borrando objeto GCS (" + status + "): " + response.parseAsString());
            }
            logger.info("[GCS] PDF borrado: {} (HTTP {})", gsUri, status);
        } finally {
            response.disconnect();
        }
    }

    // ====================================================================
    // Utilidades
    // ====================================================================
    private HttpResponse ejecutar(HttpRequest request, String contexto) throws IOException {
        HttpHeaders headers = new HttpHeaders();
        headers.setAuthorization("Bearer " + obtenerAccessToken());
        request.setHeaders(headers);
        request.setConnectTimeout(60_000);
        request.setReadTimeout(300_000); // 5 min: la subida del PDF por proxy puede ser lenta
        request.setThrowExceptionOnExecuteError(false);
        return request.execute();
    }

    private String obtenerAccessToken() throws IOException {
        GoogleCredentials credentials = getCredentials();
        credentials.refreshIfExpired();
        return credentials.getAccessToken().getTokenValue();
    }

    private GoogleCredentials getCredentials() throws IOException {
        GoogleCredentials c = cachedCredentials;
        if (c == null) {
            synchronized (this) {
                c = cachedCredentials;
                if (c == null) {
                    c = GoogleCredentials.fromStream(
                            new ByteArrayInputStream(properties.getGcpCredentialsContent().getBytes(StandardCharsets.UTF_8))
                    ).createScoped(Collections.singletonList(properties.getGcpScoped()));
                    cachedCredentials = c;
                }
            }
        }
        return c;
    }

    private NetHttpTransport buildHttpTransport() {
        // --- Proxy ANTERIOR (general SIJ 172.17.16.213:1598). Se mantiene comentado como referencia:
        //     el tráfico hacia GCS/Vertex pasa ahora por el proxy PAC de Google (ver abajo).
        // if (Boolean.TRUE.equals(properties.getProxyEnabled())) {
        //     Proxy proxy = new Proxy(Proxy.Type.HTTP,
        //             new InetSocketAddress(properties.getProxyURL(), properties.getProxyPort()));
        //     return new NetHttpTransport.Builder().setProxy(proxy).build();
        // }

        // --- Proxy NUEVO (PAC ADcsjan) para Google Cloud / Vertex / Gemini.
        //     El PAC enruta *.googleapis.com por proxycsjan(2).pj.gob.pe:3128 (ver application.yml).
        if (Boolean.TRUE.equals(properties.getProxyGoogleEnabled())) {
            Proxy proxy = new Proxy(Proxy.Type.HTTP,
                    new InetSocketAddress(properties.getProxyGoogleHost(), properties.getProxyGooglePort()));
            return new NetHttpTransport.Builder().setProxy(proxy).build();
        }
        return new NetHttpTransport.Builder().build();
    }

    private String extensionDe(String contentType) {
        if (contentType == null) return "";
        return switch (contentType) {
            case "application/pdf" -> ".pdf";
            case "image/png" -> ".png";
            case "image/jpeg" -> ".jpg";
            default -> "";
        };
    }
}
