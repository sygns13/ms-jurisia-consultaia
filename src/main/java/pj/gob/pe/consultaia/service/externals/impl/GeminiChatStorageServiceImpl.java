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
import pj.gob.pe.consultaia.configuration.GeminiChatProperties;
import pj.gob.pe.consultaia.configuration.RetryProperties;
import pj.gob.pe.consultaia.exception.HttpEstadoException;
import pj.gob.pe.consultaia.service.externals.GeminiChatStorageService;
import pj.gob.pe.consultaia.utils.ReintentoUtil;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.UUID;

/**
 * Implementación REST cruda (JSON API de Cloud Storage) para subir los adjuntos del chat con
 * Gemini a su bucket propio. Mismo transporte con proxy y credenciales del service account que
 * {@link GcsStorageServiceImpl}; a diferencia de aquel, los objetos NO se borran (los turnos
 * posteriores de la conversación los reinyectan como contexto por URI gs://).
 */
@Service
@RequiredArgsConstructor
public class GeminiChatStorageServiceImpl implements GeminiChatStorageService {

    private static final Logger logger = LoggerFactory.getLogger(GeminiChatStorageServiceImpl.class);

    private static final String GCS_BASE = "https://storage.googleapis.com";

    private final ConfigProperties properties;
    private final GeminiChatProperties chatProperties;
    private final RetryProperties retryProperties;

    /** Credenciales cacheadas: el token OAuth (~1h) se reutiliza entre subidas. */
    private volatile GoogleCredentials cachedCredentials;

    @Override
    public String subir(String sessionUID, String fileName, String contentType, byte[] contenido) throws IOException {
        String objectName = chatProperties.getChatGcsObjectPrefix() + "/" + sessionUID + "/"
                + UUID.randomUUID() + "_" + sanitizar(fileName);
        String bucket = chatProperties.getChatGcsBucket();

        String url = GCS_BASE + "/upload/storage/v1/b/" + bucket + "/o?uploadType=media&name="
                + URLEncoder.encode(objectName, StandardCharsets.UTF_8);

        NetHttpTransport transport = buildHttpTransport();
        String gsUri = "gs://" + bucket + "/" + objectName;

        // Reintentos ante cortes del enlace con el proxy (ver ReintentoUtil). El nombre del objeto se
        // fija ANTES del primer intento, así todos los intentos escriben sobre el MISMO objeto y un
        // reintento no deja adjuntos huérfanos en el bucket (que además no se borran nunca).
        ReintentoUtil.ejecutarAccion(retryProperties, "GCS-chat subida de " + gsUri, () -> {
            HttpRequestFactory factory = transport.createRequestFactory();
            HttpRequest request = factory.buildPostRequest(
                    new GenericUrl(url), new ByteArrayContent(contentType, contenido));

            HttpHeaders headers = new HttpHeaders();
            headers.setAuthorization("Bearer " + obtenerAccessToken());
            request.setHeaders(headers);
            request.setConnectTimeout(60_000);
            request.setReadTimeout(300_000); // 5 min: la subida por proxy puede ser lenta
            request.setThrowExceptionOnExecuteError(false);

            HttpResponse response = request.execute();
            try {
                if (response.getStatusCode() >= 300) {
                    throw new HttpEstadoException(response.getStatusCode(),
                            "Error subiendo adjunto a GCS (" + response.getStatusCode() + "): "
                                    + response.parseAsString());
                }
            } finally {
                response.disconnect();
            }
        });

        logger.info("[GCS-chat] Adjunto subido: {} ({} bytes)", gsUri, contenido.length);
        return gsUri;
    }

    // ====================================================================
    // Utilidades
    // ====================================================================
    /** Conserva el nombre original de forma segura para un objeto GCS. */
    private String sanitizar(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return "adjunto";
        }
        return fileName.trim().replaceAll("[^a-zA-Z0-9áéíóúÁÉÍÓÚñÑ._-]", "_");
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

    /**
     * Punto único de conmutación del proxy para el tráfico hacia Google / Vertex / GCS.
     * Misma estrategia que {@link GcsStorageServiceImpl}: proxy nuevo (PAC ADcsjan) cuando esté
     * habilitado en properties.
     */
    private NetHttpTransport buildHttpTransport() {
        if (Boolean.TRUE.equals(properties.getProxyGoogleEnabled())) {
            Proxy proxy = new Proxy(Proxy.Type.HTTP,
                    new InetSocketAddress(properties.getProxyGoogleHost(), properties.getProxyGooglePort()));
            return new NetHttpTransport.Builder().setProxy(proxy).build();
        }
        return new NetHttpTransport.Builder().build();
    }
}
