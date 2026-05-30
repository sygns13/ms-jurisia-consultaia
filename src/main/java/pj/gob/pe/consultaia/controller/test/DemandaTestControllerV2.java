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

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.threeten.bp.Duration;
import pj.gob.pe.consultaia.configuration.ConfigProperties;
import pj.gob.pe.consultaia.service.business.ChunkStoreService;
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

    private final String projectId = "apubot-v1";
    private final String geminiLocation = "global";
    private final String vectorSearchLocation = "us-central1"; // Región donde se creó el índice

    // IDs obtenidos de la consola de Google Cloud (Vector Search)
    private final String indexEndpointId = "4248471148284608512";
    private final String deployedIndexId = "pj_normativa_deploy_1_1780024396444";

    private final String embeddingModel = "text-multilingual-embedding-002";
    private final int neighborCount = 20; // Artículos más relevantes a recuperar

    private final ObjectMapper mapper = new ObjectMapper();

    private final ConfigProperties properties;
    private final ChunkStoreService chunkStoreService;

    public DemandaTestControllerV2(ConfigProperties properties, ChunkStoreService chunkStoreService) {
        this.properties = properties;
        this.chunkStoreService = chunkStoreService;
    }

    @PostMapping(value = "/analizar-demanda-v2")
    public Callable<ResponseEntity<ApiResponse<String>>> analizarDemanda(
            @RequestParam("file") MultipartFile file,
            @RequestParam("prompt") String promptInstruccionesDelUsuario) throws IOException {

        byte[] documentBytes = file.getBytes();

        return () -> {
            long start = System.nanoTime();
            try {
                // 1. CREDENCIALES Y TRANSPORTE (proxy compartido por SDK Gemini y REST crudo)
                GoogleCredentials credentials = GoogleCredentials.fromStream(
                        new ByteArrayInputStream(properties.getGcpCredentialsContent().getBytes(StandardCharsets.UTF_8))
                ).createScoped(Collections.singletonList("https://www.googleapis.com/auth/cloud-platform"));

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

                // 2. INICIO DEL FLUJO ORQUESTADO
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

                    var documentPart = PartMaker.fromMimeTypeAndData("application/pdf", documentBytes);

                    // ============================================================
                    // FASE 1: EXTRACCIÓN DE INTENCIÓN (conceptos jurídicos clave)
                    // ============================================================
                    GenerativeModel modeloExtraccion = new GenerativeModel("gemini-3.1-pro-preview", vertexAI)
                            .withGenerationConfig(GenerationConfig.newBuilder().setTemperature(0.0f)
                                    //.setThinkingConfig(ThinkingConfig.newBuilder()
                                      //      .setThinkingBudget(4096) // Forzar pensamiento profundo asignando tokens de análisis
                                        //    .setIncludeThoughts(false)
                                          //  .build())
                                    .build());

                    String promptExtraccion = "Lee esta demanda adjunta. Extrae ÚNICAMENTE una lista de los conceptos " +
                            "jurídicos procesales involucrados, el tipo de proceso, y las posibles omisiones de forma. " +
                            "Responde solo con palabras clave separadas por comas, sin explicaciones.";

                    String terminosClave = ResponseHandler.getText(
                            modeloExtraccion.generateContent(ContentMaker.fromMultiModalData(documentPart, promptExtraccion))
                    );

                    // ============================================================
                    // FASE 2: GENERACIÓN DE EMBEDDINGS (REST)
                    // ============================================================
                    List<Double> vectorConsulta = generarEmbedding(httpTransport, accessToken, terminosClave);

                    // ============================================================
                    // FASE 3: BÚSQUEDA VECTORIAL (Vector Search findNeighbors REST)
                    // ============================================================
                    List<String> idsRecuperados = buscarVecinos(httpTransport, accessToken, vectorConsulta);

                    // ============================================================
                    // FASE 4: GENERACIÓN FINAL (resolución de calificación)
                    // ============================================================
                    String contextoLeyes = chunkStoreService.construirContextoLegal(idsRecuperados);

                    GenerativeModel modeloFinal = new GenerativeModel("gemini-3.1-pro-preview", vertexAI)
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

                    double seconds = (System.nanoTime() - start) / 1_000_000_000.0;
                    return ResponseEntity.ok(ApiResponse.ok(resolucionFinal, seconds));
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
     * Obtiene el publicEndpointDomainName del index endpoint. Para endpoints públicos,
     * findNeighbors debe invocarse contra ese dominio; si no hay (endpoint privado/VPC),
     * se cae al endpoint regional estándar.
     */
    private String resolverDominioFindNeighbors(NetHttpTransport httpTransport, String accessToken,
                                                String indexEndpointResource) throws IOException {
        String regionalBase = "https://" + vectorSearchLocation + "-aiplatform.googleapis.com";
        JsonNode resp = ejecutarGet(httpTransport, accessToken, regionalBase + "/v1/" + indexEndpointResource);
        String publicDomain = resp.path("publicEndpointDomainName").asText("");
        return publicDomain.isEmpty() ? regionalBase : "https://" + publicDomain;
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
