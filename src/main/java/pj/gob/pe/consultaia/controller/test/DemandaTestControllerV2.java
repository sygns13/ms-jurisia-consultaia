package pj.gob.pe.consultaia.controller.test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.api.client.http.ByteArrayContent;
import com.google.api.client.http.GenericUrl;
import com.google.api.client.http.HttpHeaders;
import com.google.api.client.http.HttpRequest;
import com.google.api.client.http.HttpRequestFactory;
import com.google.api.client.http.HttpResponse;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.gax.core.FixedCredentialsProvider;
import com.google.api.gax.retrying.RetrySettings;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.cloud.vertexai.Transport;
import com.google.cloud.vertexai.VertexAI;
import com.google.cloud.vertexai.api.GenerationConfig;
import com.google.cloud.vertexai.api.PredictionServiceClient;
import com.google.cloud.vertexai.api.PredictionServiceSettings;
import com.google.cloud.vertexai.generativeai.ContentMaker;
import com.google.cloud.vertexai.generativeai.GenerativeModel;
import com.google.cloud.vertexai.generativeai.PartMaker;
import com.google.cloud.vertexai.generativeai.ResponseHandler;
import com.google.cloud.vertexai.api.GenerationConfig.ThinkingConfig;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.threeten.bp.Duration;
import pj.gob.pe.consultaia.configuration.ConfigProperties;
import pj.gob.pe.consultaia.service.business.ChunkStoreService;
import pj.gob.pe.consultaia.service.externals.GcsStorageService;
import pj.gob.pe.consultaia.utils.beans.responses.ApiResponse;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;


/**
 * RAG Orquestado (REST puro) para la calificación de demandas.
 *
 * Flujo:
 *  Fase 1 - Gemini extrae los conceptos jurídicos clave de la demanda (SDK Vertex AI, REST).
 *  Fase 2 - Se generan embeddings de esos conceptos (Vertex AI Prediction REST).
 *  Fase 3 - Búsqueda vectorial en el índice de normativa (Vector Search findNeighbors REST).
 *  Fase 4 - Gemini redacta la resolución de calificación con la normativa recuperada.
 *
 * Nota de transporte: las operaciones de Embeddings y Vector Search se invocan por REST
 * crudo (no GAPIC) porque, en google-cloud-aiplatform 3.93.0, MatchServiceSettings y
 * PredictionServiceSettings (com.google.cloud.aiplatform.v1) solo exponen transporte gRPC
 * (no existen newHttpJsonBuilder() ni defaultHttpJsonTransportProviderBuilder()). El REST
 * crudo, además, reutiliza el mismo NetHttpTransport con proxy que usa el SDK de Gemini,
 * manteniendo un manejo de proxy uniforme para la red del Poder Judicial.
 */
@RestController
public class DemandaTestControllerV2 {

    private static final Logger log = LoggerFactory.getLogger(DemandaTestControllerV2.class);

    private final String projectId = "apubot-v1";
    private final String geminiLocation = "global";
    private final String vectorSearchLocation = "us-central1"; // Región donde se creó el índice

    // IDs obtenidos de la consola de Google Cloud (Vector Search)
    private final String indexEndpointId = "4248471148284608512";
    private final String deployedIndexId = "pj_normativa_deploy_1_1780191746804";

    // Dominio público del Index Endpoint. Opcional: si se deja vacío, se resuelve y cachea
    // automáticamente en la primera consulta. Setearlo evita incluso ese primer GET de metadata.
    private final String publicEndpointDomain = "";

    // Modelos Gemini: Flash para la EXTRACCIÓN (Fase 1, rápido/barato, paso de recuperación)
    // y Pro para la REDACCIÓN final (Fase 4, máxima calidad). La Fase 4 NO cambia su capacidad.
    // NOTA: confirmar que el ID de Flash exista en el proyecto/región; ajustar si difiere.
    private final String extractionModel = "gemini-3.5-flash";
    private final String finalModel = "gemini-3.1-pro-preview";

    private final String embeddingModel = "text-multilingual-embedding-002";
    private final int neighborCount = 15; // Artículos más relevantes a recuperar

    /**
     * Cache en memoria del dominio público del Index Endpoint (resuelto vía metadata REST).
     * Se calcula una sola vez y se reutiliza; evita un GET por cada consulta. Solo se usa
     * cuando {@link #publicEndpointDomain} no está configurado.
     */
    private volatile String publicDomainCache;

    /**
     * Credenciales cacheadas a nivel de clase. El token OAuth (TTL ~1h) se reutiliza entre
     * requests; {@code refreshIfExpired()} solo va a la red cuando realmente expiró, evitando
     * el round-trip de firma JWT + intercambio de token en cada calificación.
     */
    private volatile GoogleCredentials cachedCredentials;

    private final ObjectMapper mapper = new ObjectMapper();

    private final ConfigProperties properties;
    private final ChunkStoreService chunkStoreService;
    private final GcsStorageService gcsStorageService;

    public DemandaTestControllerV2(ConfigProperties properties, ChunkStoreService chunkStoreService,
                                   GcsStorageService gcsStorageService) {
        this.properties = properties;
        this.chunkStoreService = chunkStoreService;
        this.gcsStorageService = gcsStorageService;
    }

    @PostMapping(value = "/analizar-demanda-v2")
    public Callable<ResponseEntity<ApiResponse<String>>> analizarDemanda(
            @RequestParam("file") MultipartFile file,
            @RequestParam("prompt") String promptInstruccionesDelUsuario) throws IOException {

        byte[] documentBytes = file.getBytes();

        return () -> {
            long start = System.nanoTime();
            try {
                // Diagnóstico: tamaño del PDF y estimación de bytes en el cable (inline base64 ~+33%, x2).
                double pdfMb = documentBytes.length / (1024.0 * 1024.0);
                log.info("[V2 diagnóstico] PDF={} bytes (~{} MB) | inline base64 ~{} MB por envío x2 (Fase1+Fase4) ~{} MB",
                        documentBytes.length, String.format("%.2f", pdfMb),
                        String.format("%.2f", pdfMb * 4.0 / 3.0), String.format("%.2f", pdfMb * 4.0 / 3.0 * 2));

                // 1. CREDENCIALES Y TRANSPORTE (proxy compartido por SDK Gemini y REST crudo)
                // Credenciales cacheadas: el token se reutiliza entre requests hasta que expira.
                GoogleCredentials credentials = getCredentials();

                NetHttpTransport httpTransport = buildHttpTransport();

                // Token de acceso para las llamadas REST crudas (Embeddings y Vector Search)
                credentials.refreshIfExpired();
                String accessToken = credentials.getAccessToken().getTokenValue();

                // Configuración del SDK de Vertex AI (Gemini) sobre REST
                PredictionServiceSettings.Builder geminiSettingsBuilder = PredictionServiceSettings.newHttpJsonBuilder();
                geminiSettingsBuilder.setEndpoint("aiplatform.googleapis.com:443");
                geminiSettingsBuilder.setCredentialsProvider(FixedCredentialsProvider.create(credentials));

                RetrySettings retry = geminiSettingsBuilder.generateContentSettings().getRetrySettings().toBuilder()
                        .setInitialRpcTimeout(Duration.ofMinutes(5))
                        .setMaxRpcTimeout(Duration.ofMinutes(5))
                        .setTotalTimeout(Duration.ofMinutes(10))
                        .build();
                geminiSettingsBuilder.generateContentSettings().setRetrySettings(retry);

                if (httpTransport != null) {
                    geminiSettingsBuilder.setTransportChannelProvider(
                            PredictionServiceSettings.defaultHttpJsonTransportProviderBuilder()
                                    .setHttpTransport(httpTransport).build());
                }

                PredictionServiceSettings geminiSettings = geminiSettingsBuilder.build();

                // El PDF se sube UNA sola vez a GCS; ambas fases lo referencian por URI (gs://) para que
                // Vertex lo lea server-side y no reenviar los bytes por el proxy. Se borra al finalizar.
                long tGcsIni = System.nanoTime();
                String gsUri = gcsStorageService.subir(documentBytes, "application/pdf");
                long tGcsFin = System.nanoTime();

                // 2. INICIO DEL FLUJO ORQUESTADO
                try {
                try (VertexAI vertexAI = new VertexAI.Builder()
                        .setProjectId(projectId)
                        .setLocation(geminiLocation)
                        .setCredentials(credentials)
                        .setTransport(Transport.REST)
                        .setApiEndpoint("aiplatform.googleapis.com")
                        .setPredictionClientSupplier(() -> {
                            try {
                                return PredictionServiceClient.create(geminiSettings);
                            } catch (IOException ex) {
                                throw new RuntimeException(ex);
                            }
                        })
                        .build()) {

                    var documentPart = PartMaker.fromMimeTypeAndData("application/pdf", gsUri);

                    // ============================================================
                    // FASE 1: EXTRACCIÓN DE INTENCIÓN (conceptos jurídicos clave)
                    // Modelo Flash + thinking desactivado: es un paso de RECUPERACIÓN, no el
                    // análisis final, por lo que se prioriza velocidad y costo.
                    // ============================================================
                    long tFase1Ini = System.nanoTime();
                    GenerativeModel modeloExtraccion = new GenerativeModel(extractionModel, vertexAI)
                            .withGenerationConfig(GenerationConfig.newBuilder()
                                    .setTemperature(0.0f)
                                    .setThinkingConfig(ThinkingConfig.newBuilder()
                                            .setThinkingBudget(0) // sin razonamiento: extracción simple de keywords
                                            .setIncludeThoughts(false)
                                            .build())
                                    .build());

                    String promptExtraccion = "Lee esta demanda adjunta. Extrae ÚNICAMENTE una lista de los conceptos " +
                            "jurídicos procesales involucrados, el tipo de proceso, y las posibles omisiones de forma. " +
                            "Responde solo con palabras clave separadas por comas, sin explicaciones.";

                    String terminosClave = ResponseHandler.getText(
                            modeloExtraccion.generateContent(ContentMaker.fromMultiModalData(documentPart, promptExtraccion))
                    );
                    long tFase1Fin = System.nanoTime();

                    // ============================================================
                    // FASE 2: GENERACIÓN DE EMBEDDINGS (REST)
                    // ============================================================
                    List<Double> vectorConsulta = generarEmbedding(httpTransport, accessToken, terminosClave);
                    long tFase2Fin = System.nanoTime();

                    // ============================================================
                    // FASE 3: BÚSQUEDA VECTORIAL (Vector Search findNeighbors REST)
                    // ============================================================
                    List<String> idsRecuperados = buscarVecinos(httpTransport, accessToken, vectorConsulta);
                    long tFase3Fin = System.nanoTime();

                    // ============================================================
                    // FASE 4: GENERACIÓN FINAL (resolución de calificación)
                    // Modelo Pro + PDF completo + normativa recuperada. Capacidad SIN cambios.
                    // ============================================================
                    String contextoLeyes = chunkStoreService.construirContextoLegal(idsRecuperados);

                    GenerativeModel modeloFinal = new GenerativeModel(finalModel, vertexAI)
                            .withSystemInstruction(ContentMaker.fromString(
                                    "Eres un Asistente Judicial Virtual experto en derecho peruano. " +
                                            "Realizas calificaciones de Demandas en base a la normativa peruana proporcionada, " +
                                            "generando automáticamente el borrador de la resolución de calificación."
                            ))
                            .withGenerationConfig(GenerationConfig.newBuilder()
                                    .setTemperature(0.0f)
                                    .setMaxOutputTokens(8192)
                                    .build());

                    String promptEnriquecido = String.format(
                            "INSTRUCCIONES DEL JUZGADO:%n%s%n%n" +
                                    "NORMATIVA LEGAL ESTRICTA A APLICAR:%n%s%n%n" +
                                    "TAREA:%nAnaliza el documento PDF adjunto basándote exclusivamente en la normativa " +
                                    "proporcionada y redacta la resolución de calificación de demanda.",
                            promptInstruccionesDelUsuario, contextoLeyes
                    );

                    String resolucionFinal = ResponseHandler.getText(
                            modeloFinal.generateContent(ContentMaker.fromMultiModalData(documentPart, promptEnriquecido))
                    );
                    long tFase4Fin = System.nanoTime();

                    log.info("[V2 tiempos] GCS-upload={}s | Fase1(extracción {})={}s | Fase2(embedding)={}s | " +
                                    "Fase3(vectorSearch)={}s | Fase4(redacción {})={}s | Total={}s",
                            seg(tGcsIni, tGcsFin), extractionModel, seg(tFase1Ini, tFase1Fin), seg(tFase1Fin, tFase2Fin),
                            seg(tFase2Fin, tFase3Fin), finalModel, seg(tFase3Fin, tFase4Fin),
                            seg(start, tFase4Fin));

                    double seconds = (System.nanoTime() - start) / 1_000_000_000.0;
                    return ResponseEntity.ok(ApiResponse.ok(resolucionFinal, seconds));
                }
                } finally {
                    try {
                        gcsStorageService.borrar(gsUri);
                    } catch (Exception ex) {
                        log.warn("[GCS] No se pudo borrar el objeto temporal {}: {}", gsUri, ex.getMessage());
                    }
                }
            } catch (Exception e) {
                Throwable cause = e;
                while (cause.getCause() != null) cause = cause.getCause();
                double seconds = (System.nanoTime() - start) / 1_000_000_000.0;
                return ResponseEntity.internalServerError()
                        .body(ApiResponse.error(cause.getMessage(), seconds));
            }
        };
    }

    // ====================================================================
    // FASE 2 - Embeddings vía REST
    // ====================================================================
    private List<Double> generarEmbedding(NetHttpTransport httpTransport, String accessToken, String texto)
            throws IOException {
        String url = String.format(
                "https://%s-aiplatform.googleapis.com/v1/projects/%s/locations/%s/publishers/google/models/%s:predict",
                vectorSearchLocation, projectId, vectorSearchLocation, embeddingModel);

        ObjectNode instance = mapper.createObjectNode();
        instance.put("content", texto);
        instance.put("task_type", "RETRIEVAL_QUERY"); // embedding del lado de la consulta

        ObjectNode body = mapper.createObjectNode();
        body.putArray("instances").add(instance);

        JsonNode resp = ejecutarPost(httpTransport, accessToken, url, mapper.writeValueAsString(body));

        JsonNode values = resp.path("predictions").path(0).path("embeddings").path("values");
        if (!values.isArray() || values.isEmpty()) {
            throw new IOException("Respuesta de embeddings sin valores: " + resp.toString());
        }
        List<Double> vector = new ArrayList<>(values.size());
        for (JsonNode v : values) {
            vector.add(v.asDouble());
        }
        return vector;
    }

    // ====================================================================
    // FASE 3 - Vector Search (findNeighbors) vía REST
    // ====================================================================
    private List<String> buscarVecinos(NetHttpTransport httpTransport, String accessToken, List<Double> vectorConsulta)
            throws IOException {
        String indexEndpointResource = String.format(
                "projects/%s/locations/%s/indexEndpoints/%s", projectId, vectorSearchLocation, indexEndpointId);

        // findNeighbors debe llamarse contra el dominio público del index endpoint (si existe).
        String base = resolverDominioFindNeighbors(httpTransport, accessToken, indexEndpointResource);
        String url = base + "/v1/" + indexEndpointResource + ":findNeighbors";

        ObjectNode datapoint = mapper.createObjectNode();
        ArrayNode featureVector = datapoint.putArray("featureVector");
        for (Double d : vectorConsulta) {
            featureVector.add(d);
        }

        ObjectNode query = mapper.createObjectNode();
        query.set("datapoint", datapoint);
        query.put("neighborCount", neighborCount);

        ObjectNode body = mapper.createObjectNode();
        body.put("deployedIndexId", deployedIndexId);
        body.putArray("queries").add(query);

        JsonNode resp = ejecutarPost(httpTransport, accessToken, url, mapper.writeValueAsString(body));

        List<String> ids = new ArrayList<>();
        for (JsonNode grupo : resp.path("nearestNeighbors")) {
            for (JsonNode vecino : grupo.path("neighbors")) {
                String id = vecino.path("datapoint").path("datapointId").asText("");
                if (!id.isEmpty()) {
                    ids.add(id);
                }
            }
        }
        return ids;
    }

    /**
     * Resuelve el dominio contra el que debe invocarse findNeighbors. Para endpoints públicos,
     * la búsqueda debe ir al publicEndpointDomainName dedicado; si no hay (endpoint privado/VPC),
     * se cae al endpoint regional estándar.
     *
     * Estrategia híbrida para evitar un GET de metadata por cada consulta:
     *  1. Si {@link #publicEndpointDomain} está configurado, se usa directo (cero round-trips).
     *  2. Si no, se resuelve vía metadata una sola vez y se cachea en memoria ({@link #publicDomainCache}).
     */
    private String resolverDominioFindNeighbors(NetHttpTransport httpTransport, String accessToken,
                                                String indexEndpointResource) throws IOException {
        // 1) Dominio configurado explícitamente: se usa sin llamar a GCP.
        if (publicEndpointDomain != null && !publicEndpointDomain.isBlank()) {
            return normalizarDominio(publicEndpointDomain);
        }

        // 2) Cache en memoria: solo la primera consulta hace el GET de metadata.
        if (publicDomainCache != null) {
            return publicDomainCache;
        }
        synchronized (this) {
            if (publicDomainCache == null) {
                String regionalBase = "https://" + vectorSearchLocation + "-aiplatform.googleapis.com";
                JsonNode resp = ejecutarGet(httpTransport, accessToken, regionalBase + "/v1/" + indexEndpointResource);
                String publicDomain = resp.path("publicEndpointDomainName").asText("");
                publicDomainCache = publicDomain.isEmpty() ? regionalBase : "https://" + publicDomain;
            }
        }
        return publicDomainCache;
    }

    /**
     * Antepone el esquema https:// al dominio si viene sin él, de modo que se pueda configurar
     * tanto "xxxx.us-central1-123.vdb.vertexai.goog" como la URL completa.
     */
    private String normalizarDominio(String dominio) {
        String d = dominio.trim();
        return (d.startsWith("http://") || d.startsWith("https://")) ? d : "https://" + d;
    }

    /**
     * Devuelve las credenciales (cacheadas a nivel de clase). Se construyen una sola vez a
     * partir del JSON del service account; el token OAuth se refresca bajo demanda en el
     * llamador vía {@code refreshIfExpired()}, reutilizándose entre requests.
     */
    private GoogleCredentials getCredentials() throws IOException {
        GoogleCredentials c = cachedCredentials;
        if (c == null) {
            synchronized (this) {
                c = cachedCredentials;
                if (c == null) {
                    c = GoogleCredentials.fromStream(
                            new ByteArrayInputStream(properties.getGcpCredentialsContent().getBytes(StandardCharsets.UTF_8))
                    ).createScoped(Collections.singletonList("https://www.googleapis.com/auth/cloud-platform"));
                    cachedCredentials = c;
                }
            }
        }
        return c;
    }

    /** Formatea el lapso entre dos marcas de System.nanoTime() en segundos con 2 decimales. */
    private String seg(long fromNanos, long toNanos) {
        return String.format("%.2f", (toNanos - fromNanos) / 1_000_000_000.0);
    }

    // ====================================================================
    // Utilidades REST (HTTP crudo con NetHttpTransport + proxy + Bearer token)
    // ====================================================================
    private NetHttpTransport buildHttpTransport() {
        if (Boolean.TRUE.equals(properties.getProxyEnabled())) {
            Proxy proxy = new Proxy(Proxy.Type.HTTP,
                    new InetSocketAddress(properties.getProxyURL(), properties.getProxyPort()));
            return new NetHttpTransport.Builder().setProxy(proxy).build();
        }
        return new NetHttpTransport.Builder().build();
    }

    private JsonNode ejecutarPost(NetHttpTransport httpTransport, String accessToken, String url, String jsonBody)
            throws IOException {
        HttpRequestFactory factory = httpTransport.createRequestFactory();
        HttpRequest request = factory.buildPostRequest(
                new GenericUrl(url),
                new ByteArrayContent("application/json", jsonBody.getBytes(StandardCharsets.UTF_8)));
        return ejecutar(request, accessToken, url);
    }

    private JsonNode ejecutarGet(NetHttpTransport httpTransport, String accessToken, String url) throws IOException {
        HttpRequestFactory factory = httpTransport.createRequestFactory();
        HttpRequest request = factory.buildGetRequest(new GenericUrl(url));
        return ejecutar(request, accessToken, url);
    }

    private JsonNode ejecutar(HttpRequest request, String accessToken, String url) throws IOException {
        HttpHeaders headers = new HttpHeaders();
        headers.setAuthorization("Bearer " + accessToken);
        request.setHeaders(headers);
        request.setConnectTimeout(60_000);
        request.setReadTimeout(120_000);
        request.setThrowExceptionOnExecuteError(false);

        HttpResponse response = request.execute();
        try {
            if (response.getStatusCode() >= 300) {
                throw new IOException("Error REST " + response.getStatusCode()
                        + " en " + url + ": " + response.parseAsString());
            }
            return mapper.readTree(response.getContent());
        } finally {
            response.disconnect();
        }
    }
}
