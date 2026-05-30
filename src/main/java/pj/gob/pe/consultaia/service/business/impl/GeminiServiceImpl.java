package pj.gob.pe.consultaia.service.business.impl;

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
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.threeten.bp.Duration;
import pj.gob.pe.consultaia.configuration.ConfigProperties;
import pj.gob.pe.consultaia.dao.mysql.ConfigurationDAO;
import pj.gob.pe.consultaia.dao.mysql.DemandasCalificadasDAO;
import pj.gob.pe.consultaia.exception.ValidationServiceException;
import pj.gob.pe.consultaia.exception.ValidationSessionServiceException;
import pj.gob.pe.consultaia.model.entities.Configurations;
import pj.gob.pe.consultaia.model.entities.DemandasCalificadas;
import pj.gob.pe.consultaia.service.business.ChunkStoreService;
import pj.gob.pe.consultaia.service.business.GeminiService;
import pj.gob.pe.consultaia.service.externals.FtpService;
import pj.gob.pe.consultaia.service.externals.SecurityService;
import pj.gob.pe.consultaia.utils.Constantes;
import pj.gob.pe.consultaia.utils.DocxGeneratorUtil;
import pj.gob.pe.consultaia.utils.beans.inputs.InputCalificacionDemanda;
import pj.gob.pe.consultaia.utils.beans.responses.ResponseCalificacionDemanda;
import pj.gob.pe.consultaia.utils.beans.responses.ResponseCalificacionDemandaDocx;
import pj.gob.pe.consultaia.utils.beans.responses.ResponseLogin;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class GeminiServiceImpl implements GeminiService {

    private static final Logger logger = LoggerFactory.getLogger(GeminiServiceImpl.class);

    private final SecurityService securityService;
    private final ConfigurationDAO configurationDAO;
    private final DemandasCalificadasDAO demandasCalificadasDAO;
    private final FtpService ftpService;
    private final ConfigProperties properties;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final ChunkStoreService chunkStoreService;

    private final ObjectMapper mapper = new ObjectMapper();

    private static final String SERVICE_CODE = "geminy_demanda_1";
    private static final String KAFKA_TOPIC = "judicial-metrics-califications";

    /**
     * Prompt interno (Fase 1) para que Gemini extraiga los conceptos jurídicos clave de la
     * demanda. Estos términos alimentan la búsqueda vectorial; no proviene de la BD porque
     * es parte del mecanismo RAG, no de la configuración funcional de la calificación.
     */
    private static final String PROMPT_EXTRACCION_CONCEPTOS =
            "Lee esta demanda adjunta. Extrae ÚNICAMENTE una lista de los conceptos " +
            "jurídicos procesales involucrados, el tipo de proceso, y las posibles omisiones de forma. " +
            "Responde solo con palabras clave separadas por comas, sin explicaciones.";

    @Override
    public ResponseCalificacionDemanda calificarDemanda(InputCalificacionDemanda input, String sessionId) throws Exception {

        long start = System.nanoTime();

        if (sessionId == null || sessionId.isEmpty()) {
            throw new ValidationSessionServiceException("La sessión remitida es inválida");
        }

        ResponseLogin responseLogin = securityService.GetSessionData(sessionId);

        if (responseLogin == null || !responseLogin.isSuccess() || !responseLogin.isItemFound() || responseLogin.getUser() == null) {
            throw new ValidationSessionServiceException("La sessión remitida es inválida");
        }

        Map<String, Object> filters = new HashMap<>();
        filters.put("borrado", Constantes.REGISTRO_NO_BORRADO);
        filters.put("activo", Constantes.REGISTRO_ACTIVO);
        filters.put("serviceCode", SERVICE_CODE);

        Configurations configurations = configurationDAO.getConfigurationsByFilters(filters);

        if (configurations == null || configurations.getId() == null) {
            throw new ValidationServiceException("La Configuración de Comunicación con la IA no está realizada adecuadamente, comunicarlo a un administrador");
        }

        DemandasCalificadas demanda = new DemandasCalificadas();
        LocalDateTime fechaSend = LocalDateTime.now();

        demanda.setUserId(responseLogin.getUser().getIdUser());
        demanda.setNUnico(input.getNunico());
        demanda.setModel(configurations.getModel());
        demanda.setRoleSystem(configurations.getRoleSystem());
        BigDecimal temperature = configurations.getTemperature() != null
                ? configurations.getTemperature().setScale(1, RoundingMode.HALF_UP)
                : BigDecimal.ZERO.setScale(1, RoundingMode.HALF_UP);
        demanda.setTemperature(temperature);
        demanda.setFechaSend(fechaSend);
        demanda.setConfigurations(configurations);
        demanda.setAnio(input.getAnio());
        demanda.setExpNro(input.getExpNro());
        demanda.setTipoExpediente(input.getTipoExpediente());
        demanda.setRutaCompleta(input.getRutaCompleta());
        demanda.setXformato(input.getXformato());
        demanda.setCclave(input.getCclave());
        demanda.setXnomInstancia(input.getXnomInstancia());
        demanda.setCubicacion(input.getCubicacion());
        demanda.setCinstancia(input.getCinstancia());
        demanda.setXdescEstado(input.getXdescEstado());
        demanda.setCusuario(input.getCusuario());
        demanda.setCmateria(input.getCmateria());
        demanda.setCespecialidad(input.getCespecialidad());
        demanda.setXdescUbicacion(input.getXdescUbicacion());
        demanda.setXnombreArchivo(input.getXnombreArchivo());
        demanda.setNincidente(input.getNincidente());
        demanda.setXrutaArchivo(input.getXrutaArchivo());
        demanda.setXdescMateria(input.getXdescMateria());
        demanda.setFinicio(input.getFinicio());
        demanda.setStatus(Constantes.CALIFICACION_DEMANDA_INICIADA);

        demanda = demandasCalificadasDAO.registrar(demanda);

        byte[] pdfBytes;
        try {
            pdfBytes = ftpService.descargarArchivo(input.getRutaCompleta(), input.getXnombreArchivo());
        } catch (Exception ex) {
            logger.error("Error descargando archivo FTP: {}", ex.getMessage(), ex);
            demanda.setStatus(Constantes.CALIFICACION_DEMANDA_ERROR_FILE_NOT_FOUND);
            demanda.setResponse("Error FTP: " + ex.getMessage());
            try {
                demanda = demandasCalificadasDAO.modificar(demanda);
            } catch (Exception modErr) {
                logger.error("Error actualizando DemandasCalificadas tras fallo FTP: {}", modErr.getMessage());
            }
            //publicarKafka(demanda);
            double seconds = (System.nanoTime() - start) / 1_000_000_000.0;
            demanda.setTimeSeconds(seconds);
            throw new ValidationServiceException("No se pudo recuperar el archivo de la demanda desde FTP: " + ex.getMessage());
        }

        String fullText;
        try {
            fullText = invocarGemini(pdfBytes, configurations);
        } catch (Exception ex) {
            logger.error("Error invocando Gemini: {}", ex.getMessage(), ex);
            demanda.setStatus(Constantes.CALIFICACION_DEMANDA_ERROR_GEMINY);
            demanda.setResponse("Error Gemini: " + ex.getMessage());
            try {
                demanda = demandasCalificadasDAO.modificar(demanda);
            } catch (Exception modErr) {
                logger.error("Error actualizando DemandasCalificadas tras fallo Gemini: {}", modErr.getMessage());
            }
            //publicarKafka(demanda);
            throw new ValidationServiceException("Error en la calificación con Gemini: " + ex.getMessage());
        }

        LocalDateTime fechaResponse = LocalDateTime.now();
        double seconds = (System.nanoTime() - start) / 1_000_000_000.0;

        demanda.setFechaResponse(fechaResponse);
        demanda.setResponse(fullText);
        demanda.setTimeSeconds(seconds);
        demanda.setStatus(Constantes.CALIFICACION_DEMANDA_EXITOSA);

        demanda = demandasCalificadasDAO.modificar(demanda);

        //publicarKafka(demanda);

        ResponseCalificacionDemanda response = new ResponseCalificacionDemanda();
        response.setId(demanda.getId());
        response.setStatus(demanda.getStatus());
        response.setModel(demanda.getModel());
        response.setResponse(demanda.getResponse());
        response.setTimeSeconds(demanda.getTimeSeconds());
        response.setFechaSend(demanda.getFechaSend());
        response.setFechaResponse(demanda.getFechaResponse());

        return response;
    }

    @Override
    public ResponseCalificacionDemandaDocx calificarDemandaDocx(InputCalificacionDemanda input, String sessionId) throws Exception {

        ResponseCalificacionDemanda calificacion = calificarDemanda(input, sessionId);

        byte[] docxBytes = DocxGeneratorUtil.textToDocx(calificacion.getResponse());

        String anio = input.getAnio() != null ? input.getAnio() : "0";
        String expNro = input.getExpNro() != null ? input.getExpNro() : "0";
        String id = calificacion.getId() != null ? String.valueOf(calificacion.getId()) : "0";

        String nombreArchivo = String.format("calificacion_demanda_%s_%s_%s.docx", anio, expNro, id);

        ResponseCalificacionDemandaDocx response = new ResponseCalificacionDemandaDocx();
        response.setDocumento(docxBytes);
        response.setNombreArchivo(nombreArchivo);

        return response;
    }

    private void publicarKafka(DemandasCalificadas demanda) {
        try {
            demanda.setConfigurationsId(demanda.getConfigurations() != null ? demanda.getConfigurations().getId() : null);
            kafkaTemplate.send(KAFKA_TOPIC, String.valueOf(demanda.getId()), demanda);
        } catch (Exception ex) {
            logger.warn("Error publicando en Kafka topic {}: {}", KAFKA_TOPIC, ex.getMessage());
        }
    }

    /**
     * Calificación de la demanda mediante RAG Orquestado (REST puro), sin Data Store.
     *
     * Flujo:
     *  Fase 1 - Gemini extrae los conceptos jurídicos clave de la demanda (SDK Vertex AI, REST).
     *  Fase 2 - Se generan embeddings de esos conceptos (Vertex AI Prediction REST).
     *  Fase 3 - Búsqueda vectorial en el índice de normativa (Vector Search findNeighbors REST).
     *  Fase 4 - Gemini redacta la resolución de calificación con la normativa recuperada, usando
     *           el modelo, roleSystem, promptDefault, temperature y maxOutputTokens de la BD.
     *
     * Nota de transporte: Embeddings y Vector Search se invocan por REST crudo (no GAPIC) porque
     * en google-cloud-aiplatform MatchServiceSettings/PredictionServiceSettings (v1) solo exponen
     * transporte gRPC. El REST crudo reutiliza el mismo NetHttpTransport con proxy del SDK de Gemini.
     */
    private String invocarGemini(byte[] pdfBytes, Configurations configurations) throws IOException {

        GoogleCredentials credentials = GoogleCredentials.fromStream(
                new ByteArrayInputStream(properties.getGcpCredentialsContent().getBytes(StandardCharsets.UTF_8))
        ).createScoped(Collections.singletonList(properties.getGcpScoped()));

        // Transporte compartido (proxy del Poder Judicial) entre el SDK de Gemini y el REST crudo.
        NetHttpTransport httpTransport = buildHttpTransport();

        // Token de acceso para las llamadas REST crudas (Embeddings y Vector Search).
        credentials.refreshIfExpired();
        String accessToken = credentials.getAccessToken().getTokenValue();

        PredictionServiceSettings.Builder settingsBuilder = PredictionServiceSettings.newHttpJsonBuilder();
        settingsBuilder.setEndpoint(properties.getGcpEndpoint());
        settingsBuilder.setCredentialsProvider(FixedCredentialsProvider.create(credentials));

        RetrySettings retry = settingsBuilder
                .generateContentSettings()
                .getRetrySettings()
                .toBuilder()
                .setInitialRpcTimeout(Duration.ofMinutes(5))
                .setMaxRpcTimeout(Duration.ofMinutes(5))
                .setTotalTimeout(Duration.ofMinutes(10))
                .build();
        settingsBuilder.generateContentSettings().setRetrySettings(retry);

        if (httpTransport != null) {
            settingsBuilder.setTransportChannelProvider(
                    PredictionServiceSettings.defaultHttpJsonTransportProviderBuilder()
                            .setHttpTransport(httpTransport)
                            .build()
            );
        }

        PredictionServiceSettings predictionSettings = settingsBuilder.build();

        try (VertexAI vertexAI = new VertexAI.Builder()
                .setProjectId(properties.getGcpProjectId())
                .setLocation(properties.getGcpLocationGlobal())
                .setCredentials(credentials)
                .setTransport(Transport.REST)
                .setApiEndpoint(properties.getGcpEndpointAPI())
                .setPredictionClientSupplier(() -> {
                    try {
                        return PredictionServiceClient.create(predictionSettings);
                    } catch (IOException ex) {
                        throw new RuntimeException(ex);
                    }
                })
                .build()) {

            var documentPart = PartMaker.fromMimeTypeAndData("application/pdf", pdfBytes);

            // ============================================================
            // FASE 1: EXTRACCIÓN DE INTENCIÓN (conceptos jurídicos clave)
            // ============================================================
            GenerativeModel modeloExtraccion = new GenerativeModel(configurations.getModel(), vertexAI)
                    .withGenerationConfig(GenerationConfig.newBuilder().setTemperature(0.0f).build());

            String terminosClave = ResponseHandler.getText(
                    modeloExtraccion.generateContent(
                            ContentMaker.fromMultiModalData(documentPart, PROMPT_EXTRACCION_CONCEPTOS))
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
            // FASE 4: GENERACIÓN FINAL (resolución de calificación, configurada desde BD)
            // ============================================================
            String contextoLeyes = chunkStoreService.construirContextoLegal(idsRecuperados);

            float temperatureValue = configurations.getTemperature() != null
                    ? configurations.getTemperature().floatValue()
                    : 0.0f;
            int maxOutputTokens = configurations.getMaxOutputTokens() != null
                    ? configurations.getMaxOutputTokens()
                    : 8192;

            GenerationConfig generationConfig = GenerationConfig.newBuilder()
                    .setTemperature(temperatureValue)
                    .setMaxOutputTokens(maxOutputTokens)
                    .build();

            GenerativeModel modeloFinal = new GenerativeModel(configurations.getModel(), vertexAI)
                    .withSystemInstruction(ContentMaker.fromString(configurations.getRoleSystem()))
                    .withGenerationConfig(generationConfig);

            String promptEnriquecido = String.format(
                    "INSTRUCCIONES DEL JUZGADO:%n%s%n%n" +
                            "NORMATIVA LEGAL ESTRICTA A APLICAR:%n%s%n%n" +
                            "TAREA:%nAnaliza el documento PDF adjunto basándote exclusivamente en la normativa " +
                            "proporcionada y redacta la resolución de calificación de demanda.",
                    configurations.getPromptDefault(), contextoLeyes
            );

            return ResponseHandler.getText(
                    modeloFinal.generateContent(ContentMaker.fromMultiModalData(documentPart, promptEnriquecido))
            );
        }
    }

    // ====================================================================
    // FASE 2 - Embeddings vía REST
    // ====================================================================
    private List<Double> generarEmbedding(NetHttpTransport httpTransport, String accessToken, String texto)
            throws IOException {
        String location = properties.getGcpVectorSearchLocation();
        String url = String.format(
                "https://%s-aiplatform.googleapis.com/v1/projects/%s/locations/%s/publishers/google/models/%s:predict",
                location, properties.getGcpProjectId(), location, properties.getGcpEmbeddingModel());

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
                "projects/%s/locations/%s/indexEndpoints/%s",
                properties.getGcpProjectId(), properties.getGcpVectorSearchLocation(), properties.getGcpIndexEndpointId());

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
        query.put("neighborCount", properties.getGcpNeighborCount());

        ObjectNode body = mapper.createObjectNode();
        body.put("deployedIndexId", properties.getGcpDeployedIndexId());
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
        String regionalBase = "https://" + properties.getGcpVectorSearchLocation() + "-aiplatform.googleapis.com";
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
