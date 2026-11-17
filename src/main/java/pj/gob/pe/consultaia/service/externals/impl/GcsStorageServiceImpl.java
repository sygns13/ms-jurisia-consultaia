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
import pj.gob.pe.consultaia.configuration.RetryProperties;
import pj.gob.pe.consultaia.exception.HttpEstadoException;
import pj.gob.pe.consultaia.service.externals.GcsStorageService;
import pj.gob.pe.consultaia.service.externals.ProxyPacService;
import pj.gob.pe.consultaia.utils.ReintentoUtil;

import java.io.ByteArrayInputStream;
import java.io.IOException;
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

    /** 5 min: la subida del PDF por proxy puede ser lenta (~1.4 Mbit/s medidos). */
    private static final int TIMEOUT_SUBIDA_MS = 300_000;

    /**
     * 1 min: el borrado es una petición diminuta y además es limpieza best-effort que corre en el
     * {@code finally} del flujo. Con un timeout de 5 min, un corte de red al final haría esperar al
     * usuario varios minutos por un resultado que ya está listo.
     */
    private static final int TIMEOUT_BORRADO_MS = 60_000;

    private final ConfigProperties properties;
    private final RetryProperties retryProperties;
    private final ProxyPacService proxyPacService;

    /** Credenciales cacheadas: el token OAuth (~1h) se reutiliza entre subidas. */
    private volatile GoogleCredentials cachedCredentials;

    @Override
    public String subir(byte[] contenido, String contentType) throws IOException {
        String objectName = properties.getGcpGcsObjectPrefix() + "/" + UUID.randomUUID() + extensionDe(contentType);
        String bucket = properties.getGcpGcsBucket();

        String url = GCS_BASE + "/upload/storage/v1/b/" + bucket + "/o?uploadType=media&name="
                + URLEncoder.encode(objectName, StandardCharsets.UTF_8);

        NetHttpTransport transport = buildHttpTransport();
        String gsUri = "gs://" + bucket + "/" + objectName;

        // Es la operación más larga y frágil del flujo (sube los bytes del PDF por el proxy), así que
        // se protege con reintentos. El nombre del objeto se fija ANTES del primer intento: todos los
        // intentos escriben sobre el MISMO objeto, de modo que un reintento no deja copias huérfanas
        // en el bucket. La petición se reconstruye en cada intento para tomar una conexión limpia.
        ReintentoUtil.ejecutarAccion(retryProperties, "GCS subida de " + gsUri, () -> {
            HttpRequestFactory factory = transport.createRequestFactory();
            HttpRequest request = factory.buildPostRequest(
                    new GenericUrl(url), new ByteArrayContent(contentType, contenido));

            HttpResponse response = ejecutar(request, TIMEOUT_SUBIDA_MS);
            try {
                if (response.getStatusCode() >= 300) {
                    throw new HttpEstadoException(response.getStatusCode(),
                            "Error subiendo a GCS (" + response.getStatusCode() + "): "
                                    + response.parseAsString());
                }
            } finally {
                response.disconnect();
            }
        });

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

        ReintentoUtil.ejecutarAccion(retryProperties, "GCS borrado de " + gsUri, () -> {
            HttpRequestFactory factory = transport.createRequestFactory();
            HttpRequest request = factory.buildDeleteRequest(new GenericUrl(url));

            HttpResponse response = ejecutar(request, TIMEOUT_BORRADO_MS);
            try {
                // 204/200 = borrado; 404 = ya no existe (idempotente, lo tratamos como éxito).
                int status = response.getStatusCode();
                if (status >= 300 && status != 404) {
                    throw new HttpEstadoException(status,
                            "Error borrando objeto GCS (" + status + "): " + response.parseAsString());
                }
                logger.info("[GCS] PDF borrado: {} (HTTP {})", gsUri, status);
            } finally {
                response.disconnect();
            }
        });
    }

    // ====================================================================
    // Utilidades
    // ====================================================================
    private HttpResponse ejecutar(HttpRequest request, int readTimeoutMs) throws IOException {
        HttpHeaders headers = new HttpHeaders();
        headers.setAuthorization("Bearer " + obtenerAccessToken());
        request.setHeaders(headers);
        request.setConnectTimeout(60_000);
        request.setReadTimeout(readTimeoutMs);
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
                    // El refresh del token OAuth (oauth2.googleapis.com) debe salir por el mismo proxy que
                    // Vertex/GCS: sin HttpTransportFactory, google-auth usa un transporte directo sin proxy.
                    c = GoogleCredentials.fromStream(
                            new ByteArrayInputStream(properties.getGcpCredentialsContent().getBytes(StandardCharsets.UTF_8)),
                            this::buildHttpTransport
                    ).createScoped(Collections.singletonList(properties.getGcpScoped()));
                    cachedCredentials = c;
                }
            }
        }
        return c;
    }

    /** Transporte hacia Google con el proxy que dicte el PAC institucional (ver {@link ProxyPacService}). */
    private NetHttpTransport buildHttpTransport() {
        return proxyPacService.crearTransporteGoogle();
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
