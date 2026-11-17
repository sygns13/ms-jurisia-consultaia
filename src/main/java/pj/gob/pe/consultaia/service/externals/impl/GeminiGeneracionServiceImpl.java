package pj.gob.pe.consultaia.service.externals.impl;

import com.google.api.client.http.ByteArrayContent;
import com.google.api.client.http.GenericUrl;
import com.google.api.client.http.HttpHeaders;
import com.google.api.client.http.HttpRequest;
import com.google.api.client.http.HttpResponse;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.cloud.aiplatform.v1.Candidate;
import com.google.cloud.aiplatform.v1.Content;
import com.google.cloud.aiplatform.v1.FileData;
import com.google.cloud.aiplatform.v1.GenerateContentRequest;
import com.google.cloud.aiplatform.v1.GenerateContentResponse;
import com.google.cloud.aiplatform.v1.GenerationConfig;
import com.google.cloud.aiplatform.v1.GenerationConfig.ThinkingConfig;
import com.google.cloud.aiplatform.v1.Part;
import com.google.protobuf.util.JsonFormat;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import pj.gob.pe.consultaia.configuration.ConfigProperties;
import pj.gob.pe.consultaia.configuration.RetryProperties;
import pj.gob.pe.consultaia.exception.HttpEstadoException;
import pj.gob.pe.consultaia.service.externals.GeminiGeneracionService;
import pj.gob.pe.consultaia.service.externals.ProxyPacService;
import pj.gob.pe.consultaia.utils.ReintentoUtil;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Implementación REST de {@link GeminiGeneracionService}: {@code generateContent} sobre el endpoint
 * global de Vertex AI, con el transporte del PAC y las credenciales del service account.
 */
@Service
@RequiredArgsConstructor
public class GeminiGeneracionServiceImpl implements GeminiGeneracionService {

    private static final Logger logger = LoggerFactory.getLogger(GeminiGeneracionServiceImpl.class);

    private final ConfigProperties properties;
    private final RetryProperties retryProperties;
    private final ProxyPacService proxyPacService;

    /** Credenciales cacheadas: el token OAuth (~1h) se reutiliza entre llamadas. */
    private volatile GoogleCredentials cachedCredentials;

    @Override
    public String generarTexto(String contexto, String modelo, String instruccionSistema, List<String> gsUrisPdf,
                               String prompt, float temperatura, int maxOutputTokens, int timeoutLecturaMs)
            throws IOException {

        String url = String.format("https://%s/v1/projects/%s/locations/%s/publishers/google/models/%s:generateContent",
                properties.getGcpEndpointAPI(), properties.getGcpProjectId(), properties.getGcpLocationGlobal(), modelo);
        String cuerpo = JsonFormat.printer().print(
                construirPeticion(instruccionSistema, gsUrisPdf, prompt, temperatura, maxOutputTokens));
        NetHttpTransport transport = proxyPacService.crearTransporteGoogle();

        // La petición se reconstruye en cada intento para que tome una conexión limpia del pool.
        GenerateContentResponse respuesta = ReintentoUtil.ejecutar(retryProperties, contexto, () -> {
            HttpRequest request = transport.createRequestFactory().buildPostRequest(
                    new GenericUrl(url), new ByteArrayContent("application/json", cuerpo.getBytes(StandardCharsets.UTF_8)));

            HttpHeaders headers = new HttpHeaders();
            headers.setAuthorization("Bearer " + obtenerAccessToken());
            request.setHeaders(headers);
            request.setConnectTimeout(60_000);
            request.setReadTimeout(timeoutLecturaMs);
            request.setThrowExceptionOnExecuteError(false);

            HttpResponse response = request.execute();
            try {
                String json = response.parseAsString();
                if (response.getStatusCode() >= 300) {
                    throw new HttpEstadoException(response.getStatusCode(), "Error REST "
                            + response.getStatusCode() + " en Gemini (" + modelo + "): " + json);
                }
                GenerateContentResponse.Builder builder = GenerateContentResponse.newBuilder();
                JsonFormat.parser().ignoringUnknownFields().merge(json, builder);
                return builder.build();
            } finally {
                response.disconnect();
            }
        });

        return extraerTexto(contexto, modelo, respuesta);
    }

    private GenerateContentRequest construirPeticion(String instruccionSistema, List<String> gsUrisPdf, String prompt,
                                                     float temperatura, int maxOutputTokens) {
        Content.Builder usuario = Content.newBuilder().setRole("user");
        for (String uri : gsUrisPdf) {
            usuario.addParts(Part.newBuilder().setFileData(
                    FileData.newBuilder().setMimeType("application/pdf").setFileUri(uri)));
        }
        usuario.addParts(Part.newBuilder().setText(prompt));

        GenerationConfig.Builder config = GenerationConfig.newBuilder()
                .setTemperature(temperatura)
                .setMaxOutputTokens(maxOutputTokens);
        String nivel = properties.getGcpFinalThinkingLevel();
        if (nivel != null && !nivel.isBlank()) {
            config.setThinkingConfig(ThinkingConfig.newBuilder()
                    .setThinkingLevel(ThinkingConfig.ThinkingLevel.valueOf(nivel.trim().toUpperCase(Locale.ROOT))));
        }

        // El modelo va en la URL, no en el cuerpo.
        return GenerateContentRequest.newBuilder()
                .setSystemInstruction(Content.newBuilder().addParts(Part.newBuilder().setText(instruccionSistema)))
                .addContents(usuario)
                .setGenerationConfig(config)
                .build();
    }

    /**
     * Texto de la primera candidata, sin las partes de razonamiento. Si se alcanzó maxOutputTokens se
     * devuelve lo generado (truncado) con una advertencia; cualquier otro corte se considera error.
     */
    private String extraerTexto(String contexto, String modelo, GenerateContentResponse respuesta) {
        if (respuesta.getCandidatesCount() == 0) {
            String bloqueo = respuesta.hasPromptFeedback()
                    ? " (bloqueo: " + respuesta.getPromptFeedback().getBlockReason() + " "
                    + respuesta.getPromptFeedback().getBlockReasonMessage() + ")"
                    : "";
            throw new IllegalStateException(contexto + ": Gemini no devolvió ninguna respuesta" + bloqueo);
        }

        Candidate candidata = respuesta.getCandidates(0);
        StringBuilder texto = new StringBuilder();
        for (Part parte : candidata.getContent().getPartsList()) {
            if (!parte.getThought() && !parte.getText().isEmpty()) {
                texto.append(parte.getText());
            }
        }

        Candidate.FinishReason fin = candidata.getFinishReason();
        if (fin == Candidate.FinishReason.MAX_TOKENS) {
            logger.warn("[{}] Respuesta truncada: se alcanzó maxOutputTokens (los tokens de razonamiento cuentan dentro)",
                    contexto);
        } else if (fin != Candidate.FinishReason.STOP && fin != Candidate.FinishReason.FINISH_REASON_UNSPECIFIED) {
            throw new IllegalStateException(contexto + ": Gemini detuvo la generación (" + fin + ") "
                    + candidata.getFinishMessage());
        }
        if (texto.isEmpty()) {
            throw new IllegalStateException(contexto + ": Gemini devolvió una respuesta sin texto (" + fin + ")");
        }

        GenerateContentResponse.UsageMetadata uso = respuesta.getUsageMetadata();
        logger.info("[{}] modelo={} razonamiento={} | tokens entrada={} razonamiento={} salida={}",
                contexto, modelo, properties.getGcpFinalThinkingLevel(), uso.getPromptTokenCount(),
                uso.getThoughtsTokenCount(), uso.getCandidatesTokenCount());
        return texto.toString();
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
                            proxyPacService::crearTransporteGoogle
                    ).createScoped(Collections.singletonList(properties.getGcpScoped()));
                    cachedCredentials = c;
                }
            }
        }
        return c;
    }
}
