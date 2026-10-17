package pj.gob.pe.consultaia.service.business.impl;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.gax.core.FixedCredentialsProvider;
import com.google.api.gax.retrying.RetrySettings;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.cloud.vertexai.Transport;
import com.google.cloud.vertexai.VertexAI;
import com.google.cloud.vertexai.api.GenerateContentResponse;
import com.google.cloud.vertexai.api.GenerationConfig;
import com.google.cloud.vertexai.api.PredictionServiceClient;
import com.google.cloud.vertexai.api.PredictionServiceSettings;
import com.google.cloud.vertexai.api.Schema;
import com.google.cloud.vertexai.api.Type;
import com.google.cloud.vertexai.generativeai.ContentMaker;
import com.google.cloud.vertexai.generativeai.GenerativeModel;
import com.google.cloud.vertexai.generativeai.ResponseHandler;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.NoArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.threeten.bp.Duration;
import pj.gob.pe.consultaia.configuration.ConfigProperties;
import pj.gob.pe.consultaia.configuration.RetryProperties;
import pj.gob.pe.consultaia.dao.mysql.ConfigurationDAO;
import pj.gob.pe.consultaia.dao.mysql.GeminiDocumentoPlantillaDAO;
import pj.gob.pe.consultaia.exception.ValidationServiceException;
import pj.gob.pe.consultaia.exception.ValidationSessionServiceException;
import pj.gob.pe.consultaia.model.entities.Configurations;
import pj.gob.pe.consultaia.model.entities.GeminiDocumentoPlantilla;
import pj.gob.pe.consultaia.service.business.GeminiDocumentoPlantillaService;
import pj.gob.pe.consultaia.service.externals.SecurityService;
import pj.gob.pe.consultaia.utils.Constantes;
import pj.gob.pe.consultaia.utils.ReintentoUtil;
import pj.gob.pe.consultaia.utils.beans.BloquePlantilla;
import pj.gob.pe.consultaia.utils.beans.ParrafoPlantilla;
import pj.gob.pe.consultaia.utils.beans.inputs.InputDocumentoPlantilla;
import pj.gob.pe.consultaia.utils.beans.responses.ResponseDocumentoPlantilla;
import pj.gob.pe.consultaia.utils.beans.responses.ResponseLogin;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Procesamiento de documentos del flujo por plantilla completa (ms-jurisia-judicial): en UNA sola
 * llamada a Gemini corrige la concordancia de los párrafos que recibieron datos del expediente y
 * redacta los bloques de origen IA. La respuesta es JSON con esquema fijo (responseSchema).
 *
 * Toda corrección se valida antes de devolverla: misma cantidad de segmentos (para conservar el
 * formato del Word), mismos marcadores ${...} y mismos "..." (la IA no puede inventar datos que
 * faltan) y sin cambios de longitud desproporcionados. Lo que no pasa la validación se descarta y
 * el párrafo queda con su texto original.
 */
@Service
@RequiredArgsConstructor
public class GeminiDocumentoPlantillaServiceImpl implements GeminiDocumentoPlantillaService {

    private static final Logger logger = LoggerFactory.getLogger(GeminiDocumentoPlantillaServiceImpl.class);

    private static final String SERVICE_CODE = "gemini_document_2";

    private static final Pattern PATRON_MARCADOR = Pattern.compile("\\$\\{[^}]*\\}");
    private static final String SIN_DATO = "...";

    /** Variación máxima de longitud aceptada en una corrección: max(20 caracteres, 30 %). */
    private static final int VARIACION_MINIMA = 20;
    private static final double VARIACION_RELATIVA = 0.30;

    private static final String INSTRUCCIONES = """
            Recibirás un JSON con partes de un documento judicial del Poder Judicial del Perú ya armado a partir \
            de una plantilla con datos del expediente. Realiza dos tareas:

            1) "parrafos": cada párrafo viene partido en "segmentos" (tramos con distinto formato). Corrige solo \
            ortografía, gramática, puntuación y concordancia de género y número que se hayan roto al insertar los \
            datos (por ejemplo "el demandante" frente a una demandante mujer o a varios demandantes). No cambies el \
            sentido ni el estilo, no agregues ni quites información y no modifiques nombres propios, números de \
            expediente, fechas, montos, artículos ni citas legales. Conserva literalmente cada "..." y cada \
            marcador ${...}. Devuelve EXACTAMENTE la misma cantidad de segmentos y en el mismo orden; un segmento \
            vacío debe seguir vacío. Incluye en la respuesta SOLO los párrafos en los que hiciste algún cambio.

            2) "bloques": para cada variable redacta el texto que pide su "instruccion", usando únicamente los \
            datos de "contextoExpediente". Registro formal judicial, sin títulos, sin viñetas y sin formato \
            markdown. No inventes datos: si falta alguno escribe "...". Separa párrafos con un salto de línea.

            Responde únicamente con JSON válido con la forma \
            {"parrafos":[{"id":1,"segmentos":["..."]}],"bloques":[{"variable":"nombre","contenido":"texto"}]}. \
            Si no hay nada que devolver en una sección, envía el arreglo vacío.

            JSON de entrada:
            """;

    private final SecurityService securityService;
    private final ConfigurationDAO configurationDAO;
    private final GeminiDocumentoPlantillaDAO geminiDocumentoPlantillaDAO;
    private final ConfigProperties properties;
    private final RetryProperties retryProperties;
    private final ObjectMapper objectMapper;

    /** Credenciales cacheadas (mismo esquema que GeminiChatServiceImpl): el token OAuth se reutiliza. */
    private volatile GoogleCredentials cachedCredentials;

    @Override
    public ResponseDocumentoPlantilla procesarDocumentoPlantilla(InputDocumentoPlantilla input, String SessionId) throws Exception {

        validarSesion(SessionId);

        List<ParrafoPlantilla> parrafos = input.getParrafos() != null ? input.getParrafos() : Collections.emptyList();
        List<BloquePlantilla> bloques = input.getBloques() != null ? input.getBloques() : Collections.emptyList();

        if (parrafos.isEmpty() && bloques.isEmpty()) {
            throw new ValidationServiceException("No se remitieron párrafos ni bloques para procesar");
        }

        Map<String, Object> filters = new HashMap<>();
        filters.put("borrado", Constantes.REGISTRO_NO_BORRADO);
        filters.put("activo", Constantes.REGISTRO_ACTIVO);
        filters.put("serviceCode", SERVICE_CODE);

        Configurations configurations = configurationDAO.getConfigurationsByFilters(filters);

        if (configurations == null || configurations.getId() == null) {
            throw new ValidationServiceException("La Configuración de Comunicación con la IA no está realizada adecuadamente, comunicarlo a un administrador");
        }

        BigDecimal temperature = configurations.getTemperature() != null
                ? configurations.getTemperature().setScale(1, RoundingMode.HALF_UP)
                : BigDecimal.ZERO.setScale(1, RoundingMode.HALF_UP);

        // Solo viaja a la IA lo necesario para cada tarea
        Map<String, Object> entrada = new LinkedHashMap<>();
        if (!bloques.isEmpty()) {
            entrada.put("contextoExpediente", input.getContextoExpediente());
        }
        entrada.put("parrafos", parrafos);
        entrada.put("bloques", bloques.stream()
                .map(b -> Map.of("variable", b.getVariable(), "instruccion", b.getInstruccion() == null ? "" : b.getInstruccion()))
                .toList());

        String prompt = INSTRUCCIONES + objectMapper.writeValueAsString(entrada);

        GeminiDocumentoPlantilla registro = new GeminiDocumentoPlantilla();
        registro.setSessionUID(input.getSessionUID());
        registro.setNUnico(input.getNUnico());
        registro.setIdDocumento(input.getIdDocumento());
        registro.setIdPlantilla(input.getIdPlantilla());
        registro.setCodigoPlantilla(input.getCodigoPlantilla());
        registro.setVersionPlantilla(input.getVersionPlantilla());
        registro.setUserId(input.getIdUser());
        registro.setModel(configurations.getModel());
        registro.setRoleSystem(configurations.getRoleSystem());
        registro.setTemperature(temperature);
        registro.setRequest(prompt);
        registro.setParrafosEnviados(parrafos.size());
        registro.setBloquesSolicitados(bloques.size());
        registro.setFechaSend(LocalDateTime.now());
        registro.setConfigurations(configurations);
        registro.setStatus(Constantes.COMPLETION_INICIADO);

        try {
            registro = geminiDocumentoPlantillaDAO.registrar(registro);
        } catch (Exception e) {
            logger.error("Error registrando GeminiDocumentoPlantilla: {}", e.getMessage());
            throw new ValidationServiceException("El registro de Data no se ha realizado correctamente, por favor comunicarlo a un administrador");
        }

        // ---- Llamada a Gemini ----
        long inicio = System.nanoTime();
        GenerateContentResponse geminiResponse;
        String textoRespuesta;
        try {
            GoogleCredentials credentials = getCredentials();
            credentials.refreshIfExpired();

            GenerationConfig generationConfig = GenerationConfig.newBuilder()
                    .setTemperature(configurations.getTemperature() != null ? configurations.getTemperature().floatValue() : 0.2f)
                    .setMaxOutputTokens(configurations.getMaxOutputTokens() != null ? configurations.getMaxOutputTokens() : 16384)
                    .setResponseMimeType("application/json")
                    .setResponseSchema(esquemaRespuesta())
                    .build();

            try (VertexAI vertexAI = crearClienteVertex(credentials)) {
                GenerativeModel model = new GenerativeModel(configurations.getModel(), vertexAI)
                        .withSystemInstruction(ContentMaker.fromString(configurations.getRoleSystem()))
                        .withGenerationConfig(generationConfig);

                geminiResponse = ReintentoUtil.ejecutar(retryProperties,
                        "GeminiDocumentoPlantilla " + input.getSessionUID(),
                        () -> model.generateContent(ContentMaker.fromString(prompt)));
            }
            textoRespuesta = ResponseHandler.getText(geminiResponse);
        } catch (Exception ex) {
            logger.error("Error invocando Gemini para la plantilla sessionUID={}: {}", input.getSessionUID(), ex.getMessage(), ex);
            marcarError(registro, "Error Gemini: " + ex.getMessage(), inicio);
            throw new ValidationServiceException("Error en el procesamiento del documento con Gemini: " + ex.getMessage());
        }
        double segundos = (System.nanoTime() - inicio) / 1_000_000_000.0;

        // ---- Interpretación y validación ----
        RespuestaModelo respuestaModelo;
        try {
            respuestaModelo = objectMapper.copy()
                    .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
                    .readValue(limpiarJson(textoRespuesta), RespuestaModelo.class);
        } catch (Exception ex) {
            logger.error("Respuesta de Gemini no es JSON válido sessionUID={}: {}", input.getSessionUID(), ex.getMessage());
            registro.setResponse(textoRespuesta);
            marcarError(registro, null, inicio);
            throw new ValidationServiceException("La respuesta de la IA no tiene el formato esperado");
        }

        List<ParrafoPlantilla> corregidos = validarParrafos(parrafos, respuestaModelo.getParrafos(), input.getSessionUID());
        List<BloquePlantilla> redactados = validarBloques(bloques, respuestaModelo.getBloques());

        GenerateContentResponse.UsageMetadata uso = geminiResponse.getUsageMetadata();
        String finishReason = geminiResponse.getCandidatesCount() > 0
                ? geminiResponse.getCandidates(0).getFinishReason().name()
                : null;

        registro.setResponse(textoRespuesta);
        registro.setParrafosCorregidos(corregidos.size());
        registro.setBloquesGenerados(redactados.size());
        registro.setPromptTokens(uso.getPromptTokenCount());
        registro.setCandidatesTokens(uso.getCandidatesTokenCount());
        registro.setThoughtsTokens(uso.getThoughtsTokenCount());
        registro.setCachedTokens(uso.getCachedContentTokenCount());
        registro.setTotalTokens(uso.getTotalTokenCount());
        registro.setFinishReason(finishReason);
        registro.setFechaResponse(LocalDateTime.now());
        registro.setTimeSeconds(segundos);
        registro.setStatus(Constantes.COMPLETION_EXITOSO);

        try {
            geminiDocumentoPlantillaDAO.modificar(registro);
        } catch (Exception e) {
            logger.error("Error actualizando GeminiDocumentoPlantilla: {}", e.getMessage());
        }

        logger.info("[GeminiDocumentoPlantilla] sessionUID={} plantilla={} parrafos={}/{} bloques={}/{} tokens={} tiempo={}s",
                input.getSessionUID(), input.getCodigoPlantilla(), corregidos.size(), parrafos.size(),
                redactados.size(), bloques.size(), uso.getTotalTokenCount(), String.format("%.2f", segundos));

        ResponseDocumentoPlantilla response = new ResponseDocumentoPlantilla();
        response.setSessionUID(input.getSessionUID());
        response.setParrafos(corregidos);
        response.setBloques(redactados);
        response.setParrafosCorregidos(corregidos.size());
        response.setBloquesGenerados(redactados.size());
        response.setModel(configurations.getModel());
        response.setRoleSystem(configurations.getRoleSystem());
        response.setTemperature(temperature);
        response.setConfigurationsId(configurations.getId());
        response.setFinishReason(finishReason);
        response.setPromptTokens(uso.getPromptTokenCount());
        response.setCandidatesTokens(uso.getCandidatesTokenCount());
        response.setThoughtsTokens(uso.getThoughtsTokenCount());
        response.setCachedTokens(uso.getCachedContentTokenCount());
        response.setTotalTokens(uso.getTotalTokenCount());
        response.setTimeSeconds(segundos);
        return response;
    }

    // ====================================================================
    // Validación de la respuesta
    // ====================================================================

    private List<ParrafoPlantilla> validarParrafos(List<ParrafoPlantilla> originales, List<ParrafoPlantilla> propuestos, String sessionUID) {

        List<ParrafoPlantilla> aceptados = new ArrayList<>();
        if (propuestos == null || propuestos.isEmpty()) return aceptados;

        Map<Integer, ParrafoPlantilla> porId = new HashMap<>();
        originales.forEach(p -> porId.put(p.getId(), p));

        for (ParrafoPlantilla propuesto : propuestos) {
            ParrafoPlantilla original = propuesto.getId() != null ? porId.remove(propuesto.getId()) : null;
            String motivo = motivoRechazo(original, propuesto);
            if (motivo != null) {
                if (!"sin cambios".equals(motivo)) {
                    logger.warn("[GeminiDocumentoPlantilla] sessionUID={} corrección del párrafo {} descartada: {}",
                            sessionUID, propuesto.getId(), motivo);
                }
                continue;
            }
            aceptados.add(propuesto);
        }
        return aceptados;
    }

    private String motivoRechazo(ParrafoPlantilla original, ParrafoPlantilla propuesto) {
        if (original == null) return "id inexistente o repetido";
        if (propuesto.getSegmentos() == null || original.getSegmentos() == null
                || propuesto.getSegmentos().size() != original.getSegmentos().size()) {
            return "cantidad de segmentos distinta";
        }
        for (int i = 0; i < propuesto.getSegmentos().size(); i++) {
            if (propuesto.getSegmentos().get(i) == null) return "segmento nulo";
        }

        String textoOriginal = String.join("", original.getSegmentos());
        String textoPropuesto = String.join("", propuesto.getSegmentos());

        if (textoOriginal.equals(textoPropuesto)) return "sin cambios";
        if (!marcadores(textoOriginal).equals(marcadores(textoPropuesto))) return "se alteraron marcadores ${...}";
        if (contar(textoOriginal, SIN_DATO) != contar(textoPropuesto, SIN_DATO)) return "se alteraron los '...' sin dato";

        int variacion = Math.abs(textoPropuesto.length() - textoOriginal.length());
        if (variacion > Math.max(VARIACION_MINIMA, textoOriginal.length() * VARIACION_RELATIVA)) {
            return "cambio de longitud desproporcionado";
        }
        return null;
    }

    private List<BloquePlantilla> validarBloques(List<BloquePlantilla> solicitados, List<BloquePlantilla> propuestos) {

        List<BloquePlantilla> aceptados = new ArrayList<>();
        if (propuestos == null || propuestos.isEmpty()) return aceptados;

        Map<String, BloquePlantilla> pendientes = new HashMap<>();
        solicitados.forEach(b -> pendientes.put(b.getVariable(), b));

        for (BloquePlantilla propuesto : propuestos) {
            if (propuesto.getVariable() == null || pendientes.remove(propuesto.getVariable()) == null) continue;
            if (propuesto.getContenido() == null || propuesto.getContenido().isBlank()) continue;
            aceptados.add(new BloquePlantilla(propuesto.getVariable(), null, propuesto.getContenido().trim()));
        }
        return aceptados;
    }

    private static List<String> marcadores(String texto) {
        List<String> encontrados = new ArrayList<>();
        Matcher m = PATRON_MARCADOR.matcher(texto);
        while (m.find()) encontrados.add(m.group());
        return encontrados;
    }

    private static int contar(String texto, String patron) {
        int total = 0;
        for (int i = texto.indexOf(patron); i >= 0; i = texto.indexOf(patron, i + patron.length())) total++;
        return total;
    }

    /** Tolera respuestas envueltas en ```json ... ``` aunque se pida JSON puro. */
    private static String limpiarJson(String texto) {
        String t = texto == null ? "" : texto.trim();
        if (t.startsWith("```")) {
            int inicio = t.indexOf('\n');
            int fin = t.lastIndexOf("```");
            if (inicio > 0 && fin > inicio) {
                t = t.substring(inicio + 1, fin).trim();
            }
        }
        return t;
    }

    private static Schema esquemaRespuesta() {
        Schema texto = Schema.newBuilder().setType(Type.STRING).build();

        Schema parrafo = Schema.newBuilder()
                .setType(Type.OBJECT)
                .putProperties("id", Schema.newBuilder().setType(Type.INTEGER).build())
                .putProperties("segmentos", Schema.newBuilder().setType(Type.ARRAY).setItems(texto).build())
                .addRequired("id")
                .addRequired("segmentos")
                .build();

        Schema bloque = Schema.newBuilder()
                .setType(Type.OBJECT)
                .putProperties("variable", texto)
                .putProperties("contenido", texto)
                .addRequired("variable")
                .addRequired("contenido")
                .build();

        return Schema.newBuilder()
                .setType(Type.OBJECT)
                .putProperties("parrafos", Schema.newBuilder().setType(Type.ARRAY).setItems(parrafo).build())
                .putProperties("bloques", Schema.newBuilder().setType(Type.ARRAY).setItems(bloque).build())
                .addRequired("parrafos")
                .addRequired("bloques")
                .build();
    }

    /** Marca el registro con error y persiste (best-effort: no debe tapar el error original). */
    private void marcarError(GeminiDocumentoPlantilla registro, String mensaje, long inicioNano) {
        registro.setStatus(Constantes.COMPLETION_ERROR);
        if (mensaje != null) {
            registro.setResponse(mensaje);
        }
        registro.setFechaResponse(LocalDateTime.now());
        registro.setTimeSeconds((System.nanoTime() - inicioNano) / 1_000_000_000.0);
        try {
            geminiDocumentoPlantillaDAO.modificar(registro);
        } catch (Exception ex) {
            logger.error("Error actualizando GeminiDocumentoPlantilla tras fallo: {}", ex.getMessage());
        }
    }

    // ====================================================================
    // Infraestructura Vertex AI (mismo patrón que GeminiChatServiceImpl)
    // ====================================================================

    private ResponseLogin validarSesion(String sessionId) {
        if (sessionId == null || sessionId.isEmpty()) {
            throw new ValidationSessionServiceException("La sessión remitida es inválida");
        }

        ResponseLogin responseLogin = securityService.GetSessionData(sessionId);

        if (responseLogin == null || !responseLogin.isSuccess() || !responseLogin.isItemFound() || responseLogin.getUser() == null) {
            throw new ValidationSessionServiceException("La sessión remitida es inválida");
        }

        return responseLogin;
    }

    private VertexAI crearClienteVertex(GoogleCredentials credentials) {

        NetHttpTransport httpTransport = buildHttpTransport();

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

        PredictionServiceSettings predictionSettings;
        try {
            predictionSettings = settingsBuilder.build();
        } catch (IOException ex) {
            throw new RuntimeException(ex);
        }

        return new VertexAI.Builder()
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
                .build();
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

    private NetHttpTransport buildHttpTransport() {
        if (Boolean.TRUE.equals(properties.getProxyGoogleEnabled())) {
            java.net.Proxy proxy = new java.net.Proxy(java.net.Proxy.Type.HTTP,
                    new java.net.InetSocketAddress(properties.getProxyGoogleHost(), properties.getProxyGooglePort()));
            return new NetHttpTransport.Builder().setProxy(proxy).build();
        }
        return new NetHttpTransport.Builder().build();
    }

    /** Forma de la respuesta JSON del modelo. */
    @Data
    @NoArgsConstructor
    private static class RespuestaModelo {
        private List<ParrafoPlantilla> parrafos;
        private List<BloquePlantilla> bloques;
    }
}
