package pj.gob.pe.consultaia.service.business.impl;

import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.gax.core.FixedCredentialsProvider;
import com.google.api.gax.retrying.RetrySettings;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.cloud.vertexai.Transport;
import com.google.cloud.vertexai.VertexAI;
import com.google.cloud.vertexai.api.Content;
import com.google.cloud.vertexai.api.GenerateContentResponse;
import com.google.cloud.vertexai.api.GenerationConfig;
import com.google.cloud.vertexai.api.PredictionServiceClient;
import com.google.cloud.vertexai.api.PredictionServiceSettings;
import com.google.cloud.vertexai.api.Retrieval;
import com.google.cloud.vertexai.api.Tool;
import com.google.cloud.vertexai.api.VertexAISearch;
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
import pj.gob.pe.consultaia.service.business.GeminiService;
import pj.gob.pe.consultaia.service.externals.FtpService;
import pj.gob.pe.consultaia.service.externals.SecurityService;
import pj.gob.pe.consultaia.utils.Constantes;
import pj.gob.pe.consultaia.utils.beans.inputs.InputCalificacionDemanda;
import pj.gob.pe.consultaia.utils.beans.responses.ResponseCalificacionDemanda;
import pj.gob.pe.consultaia.utils.beans.responses.ResponseLogin;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.HashMap;
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

    private static final String SERVICE_CODE = "geminy_demanda_1";
    private static final String KAFKA_TOPIC = "judicial-metrics-califications";

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

    private void publicarKafka(DemandasCalificadas demanda) {
        try {
            demanda.setConfigurationsId(demanda.getConfigurations() != null ? demanda.getConfigurations().getId() : null);
            kafkaTemplate.send(KAFKA_TOPIC, String.valueOf(demanda.getId()), demanda);
        } catch (Exception ex) {
            logger.warn("Error publicando en Kafka topic {}: {}", KAFKA_TOPIC, ex.getMessage());
        }
    }

    private String invocarGemini(byte[] pdfBytes, Configurations configurations) throws IOException {

        GoogleCredentials credentials = GoogleCredentials.fromStream(
                new ByteArrayInputStream(properties.getGcpCredentialsContent().getBytes(StandardCharsets.UTF_8))
        ).createScoped(Collections.singletonList(properties.getGcpScoped()));

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

        if (Boolean.TRUE.equals(properties.getProxyEnabled())) {
            Proxy proxy = new Proxy(Proxy.Type.HTTP,
                    new InetSocketAddress(properties.getProxyURL(), properties.getProxyPort()));
            settingsBuilder.setTransportChannelProvider(
                    PredictionServiceSettings.defaultHttpJsonTransportProviderBuilder()
                            .setHttpTransport(new NetHttpTransport.Builder().setProxy(proxy).build())
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

            String datastorePath = String.format(
                    properties.getGcpDatastorePath(),
                    properties.getGcpProjectId(),
                    properties.getGcpDataStoreId());

            VertexAISearch vertexAiSearch = VertexAISearch.newBuilder().setDatastore(datastorePath).build();
            Retrieval retrieval = Retrieval.newBuilder().setVertexAiSearch(vertexAiSearch).build();
            Tool herramientaLeyes = Tool.newBuilder().setRetrieval(retrieval).build();

            GenerativeModel model = new GenerativeModel(configurations.getModel(), vertexAI)
                    .withSystemInstruction(ContentMaker.fromString(configurations.getRoleSystem()))
                    .withTools(java.util.Arrays.asList(herramientaLeyes));

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

            GenerativeModel configuredModel = model.withGenerationConfig(generationConfig);

            var documentPart = PartMaker.fromMimeTypeAndData("application/pdf", pdfBytes);
            Content inputContent = ContentMaker.fromMultiModalData(documentPart, configurations.getPromptDefault());

            GenerateContentResponse response = configuredModel.generateContent(inputContent);
            return ResponseHandler.getText(response);
        }
    }
}
