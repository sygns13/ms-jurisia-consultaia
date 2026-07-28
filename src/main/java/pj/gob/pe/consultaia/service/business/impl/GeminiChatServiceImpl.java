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
import com.google.cloud.vertexai.generativeai.ContentMaker;
import com.google.cloud.vertexai.generativeai.GenerativeModel;
import com.google.cloud.vertexai.generativeai.PartMaker;
import com.google.cloud.vertexai.generativeai.ResponseHandler;
import lombok.RequiredArgsConstructor;
import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.extractor.WordExtractor;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.threeten.bp.Duration;
import pj.gob.pe.consultaia.configuration.ConfigProperties;
import pj.gob.pe.consultaia.dao.mysql.ConfigurationDAO;
import pj.gob.pe.consultaia.dao.mysql.GeminiChatsDAO;
import pj.gob.pe.consultaia.dao.mysql.GeminiChatsFilesDAO;
import pj.gob.pe.consultaia.dao.mysql.GeminiExpedienteChatsDAO;
import pj.gob.pe.consultaia.exception.ValidationServiceException;
import pj.gob.pe.consultaia.exception.ValidationSessionServiceException;
import pj.gob.pe.consultaia.model.entities.Configurations;
import pj.gob.pe.consultaia.model.entities.GeminiChats;
import pj.gob.pe.consultaia.model.entities.GeminiChatsFiles;
import pj.gob.pe.consultaia.model.entities.GeminiExpedienteChats;
import pj.gob.pe.consultaia.service.business.GeminiChatService;
import pj.gob.pe.consultaia.service.externals.GeminiChatStorageService;
import pj.gob.pe.consultaia.service.externals.JudicialService;
import pj.gob.pe.consultaia.service.externals.SecurityService;
import pj.gob.pe.consultaia.utils.Constantes;
import pj.gob.pe.consultaia.utils.beans.SectionTemplate;
import pj.gob.pe.consultaia.utils.beans.inputs.InputDocument;
import pj.gob.pe.consultaia.utils.beans.inputs.InputGeminiChatFile;
import pj.gob.pe.consultaia.utils.beans.responses.*;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.*;

/**
 * Módulo conversacional con Gemini. Mantiene la misma lógica general del flujo ChatGPT
 * ({@link ChatGPTServiceImpl#processChatGPT}): validación de sesión (Security API), configuración
 * desde BD (serviceCode gemini_chat_1), conversaciones identificadas por sessionUID (UUID),
 * historial limitado por maxMessages y métricas a Kafka. La invocación a Gemini reutiliza el
 * patrón técnico probado en la calificación de demandas ({@link GeminiServiceImpl}): Vertex AI SDK
 * con transporte REST, endpoint global, proxy conmutable y timeouts largos; los adjuntos se suben
 * a un bucket GCS propio y se referencian por URI gs:// (server-side, sin reenviar bytes por el
 * proxy), reinyectándose como contexto en los turnos siguientes de la conversación.
 */
@Service
@RequiredArgsConstructor
public class GeminiChatServiceImpl implements GeminiChatService {

    private static final Logger logger = LoggerFactory.getLogger(GeminiChatServiceImpl.class);

    private final SecurityService securityService;
    private final JudicialService judicialService;
    private final ConfigurationDAO configurationDAO;
    private final GeminiChatsDAO geminiChatsDAO;
    private final GeminiChatsFilesDAO geminiChatsFilesDAO;
    private final GeminiExpedienteChatsDAO geminiExpedienteChatsDAO;
    private final GeminiChatStorageService geminiChatStorageService;
    private final ConfigProperties properties;
    private final KafkaTemplate<String, Object> kafkaTemplate;

    private static final String SERVICE_CODE = "gemini_chat_1";
    private static final String SERVICE_CODE_DOCUMENT = "gemini_document_1";
    private static final String KAFKA_TOPIC = "judicial-metrics-gemini-chats";

    private static final String MIME_DOCX = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
    private static final String MIME_DOC = "application/msword";

    /** MIME por extensión, para cuando el multipart llega sin contentType o con octet-stream. */
    private static final Map<String, String> MIME_POR_EXTENSION = Map.ofEntries(
            Map.entry("pdf", "application/pdf"),
            Map.entry("png", "image/png"),
            Map.entry("jpg", "image/jpeg"),
            Map.entry("jpeg", "image/jpeg"),
            Map.entry("gif", "image/gif"),
            Map.entry("webp", "image/webp"),
            Map.entry("txt", "text/plain"),
            Map.entry("mp3", "audio/mpeg"),
            Map.entry("wav", "audio/wav"),
            Map.entry("m4a", "audio/mp4"),
            Map.entry("mp4", "video/mp4"),
            Map.entry("mov", "video/quicktime"),
            Map.entry("avi", "video/x-msvideo"),
            Map.entry("docx", MIME_DOCX),
            Map.entry("doc", MIME_DOC)
    );

    /** Credenciales cacheadas (mismo esquema que GeminiServiceImpl): el token OAuth se reutiliza. */
    private volatile GoogleCredentials cachedCredentials;

    @Override
    public ResponseGeminiChat processChat(String prompt, String sessionUID, List<InputGeminiChatFile> files, String SessionId) throws Exception {

        long start = System.nanoTime();

        ResponseLogin responseLogin = validarSesion(SessionId);

        if (prompt == null || prompt.isBlank()) {
            throw new ValidationServiceException("El prompt de consulta es obligatorio");
        }

        Map<String, Object> filters = new HashMap<>();
        filters.put("borrado", Constantes.REGISTRO_NO_BORRADO);
        filters.put("activo", Constantes.REGISTRO_ACTIVO);
        filters.put("serviceCode", SERVICE_CODE);

        Configurations configurations = configurationDAO.getConfigurationsByFilters(filters);

        if (configurations == null || configurations.getId() == null) {
            throw new ValidationServiceException("La Configuración de Comunicación con la IA no está realizada adecuadamente, comunicarlo a un administrador");
        }

        List<InputGeminiChatFile> adjuntos = files != null ? files : Collections.emptyList();

        // ============================================================
        // Historial de la conversación (texto + adjuntos previos)
        // ============================================================
        List<GeminiChats> historyChats = new ArrayList<>();
        Map<Long, List<GeminiChatsFiles>> historyFilesByChat = new HashMap<>();

        if (sessionUID != null && !sessionUID.isEmpty()) {
            Map<String, Object> historyFilters = new HashMap<>();
            historyFilters.put("userId", responseLogin.getUser().getIdUser());
            historyFilters.put("sessionUID", sessionUID);

            historyChats = geminiChatsDAO.getGeminiChatsByFilters(
                    historyFilters, new HashMap<>(), configurations.getMaxMessages(), "id");

            // Invertir el orden de la lista (queda cronológico, igual que en ChatGPT)
            Collections.reverse(historyChats);

            List<Long> chatIds = historyChats.stream().map(GeminiChats::getId).toList();
            for (GeminiChatsFiles f : geminiChatsFilesDAO.listarPorChatIds(chatIds, Constantes.REGISTRO_ACTIVO)) {
                historyFilesByChat.computeIfAbsent(f.getGeminiChatId(), k -> new ArrayList<>()).add(f);
            }
        } else {
            sessionUID = UUID.randomUUID().toString();
        }

        // ============================================================
        // Registro del turno (status iniciado)
        // ============================================================
        BigDecimal temperature = configurations.getTemperature() != null
                ? configurations.getTemperature().setScale(1, RoundingMode.HALF_UP)
                : BigDecimal.ZERO.setScale(1, RoundingMode.HALF_UP);

        GeminiChats chat = new GeminiChats();
        LocalDateTime fechaSend = LocalDateTime.now();

        chat.setUserId(responseLogin.getUser().getIdUser());
        chat.setModel(configurations.getModel());
        chat.setRoleSystem(configurations.getRoleSystem());
        chat.setPrompt(prompt);
        chat.setTemperature(temperature);
        chat.setFechaSend(fechaSend);
        chat.setConfigurations(configurations);
        chat.setSessionUID(sessionUID);
        chat.setStatus(Constantes.COMPLETION_INICIADO);
        chat.setHasFiles(adjuntos.isEmpty() ? Constantes.REGISTRO_INACTIVO : Constantes.REGISTRO_ACTIVO);

        chat = geminiChatsDAO.registrar(chat);

        // ============================================================
        // Subida de adjuntos a GCS + registro en GeminiChatsFiles
        // ============================================================
        List<GeminiChatsFiles> archivosTurno = new ArrayList<>();
        try {
            for (InputGeminiChatFile adjunto : adjuntos) {
                if (adjunto == null || adjunto.getContent() == null || adjunto.getContent().length == 0) {
                    continue;
                }
                String mime = resolverMime(adjunto);
                String textoWord = extraerTextoWord(mime, adjunto.getContent());
                String gsUri = geminiChatStorageService.subir(sessionUID, adjunto.getFileName(), mime, adjunto.getContent());

                GeminiChatsFiles fileRow = new GeminiChatsFiles();
                fileRow.setGeminiChatId(chat.getId());
                fileRow.setSessionUID(sessionUID);
                fileRow.setFileName(adjunto.getFileName());
                fileRow.setMimeType(mime);
                fileRow.setSizeBytes((long) adjunto.getContent().length);
                fileRow.setGcsUri(gsUri);
                fileRow.setTextoExtraido(textoWord);
                fileRow.setFechaReg(LocalDateTime.now());
                fileRow.setStatus(Constantes.REGISTRO_ACTIVO);

                fileRow = geminiChatsFilesDAO.registrar(fileRow);
                archivosTurno.add(fileRow);
            }
        } catch (ValidationServiceException ex) {
            marcarError(chat, "Error de adjunto: " + ex.getMessage());
            publicarKafka(ResponseGeminiChat.from(chat, archivosTurno));
            throw ex;
        } catch (Exception ex) {
            logger.error("Error subiendo adjunto del chat a GCS: {}", ex.getMessage(), ex);
            marcarError(chat, "Error subiendo adjunto: " + ex.getMessage());
            publicarKafka(ResponseGeminiChat.from(chat, archivosTurno));
            throw new ValidationServiceException("No se pudo subir el archivo adjunto: " + ex.getMessage());
        }

        // ============================================================
        // Invocación a Gemini con todo el contexto (historial multimodal)
        // ============================================================
        String respuesta;
        try {
            respuesta = invocarGeminiChat(configurations, historyChats, historyFilesByChat, prompt, archivosTurno);
        } catch (Exception ex) {
            logger.error("Error invocando Gemini (chat): {}", ex.getMessage(), ex);
            marcarError(chat, "Error Gemini: " + ex.getMessage());
            publicarKafka(ResponseGeminiChat.from(chat, archivosTurno));
            throw new ValidationServiceException("Error en la consulta a Gemini: " + ex.getMessage());
        }

        // ============================================================
        // Persistencia de la respuesta + métricas
        // ============================================================
        double seconds = (System.nanoTime() - start) / 1_000_000_000.0;

        chat.setFechaResponse(LocalDateTime.now());
        chat.setResponse(respuesta);
        chat.setTimeSeconds(seconds);
        chat.setStatus(Constantes.COMPLETION_EXITOSO);

        chat = geminiChatsDAO.modificar(chat);

        ResponseGeminiChat response = ResponseGeminiChat.from(chat, archivosTurno);
        response.setSedes(obtenerSedes(SessionId));

        publicarKafka(response);

        return response;
    }

    @Override
    public Page<GeminiChats> listar(Pageable pageable, String buscar, String SessionId) throws Exception {

        ResponseLogin responseLogin = validarSesion(SessionId);

        Map<String, Object> filters = new HashMap<>();
        filters.put("userId", responseLogin.getUser().getIdUser());

        Map<String, Object> filtersNotEquals = new HashMap<>();

        return geminiChatsDAO.getGralGeminiChatsByFilters(filters, filtersNotEquals, pageable);
    }

    @Override
    public Long getTotalConversaciones(String buscar, String SessionId) throws Exception {

        ResponseLogin responseLogin = validarSesion(SessionId);

        Map<String, Object> filters = new HashMap<>();
        filters.put("userId", responseLogin.getUser().getIdUser());

        Map<String, Object> filtersNotEquals = new HashMap<>();

        return geminiChatsDAO.getTotalConversaciones(filters, filtersNotEquals);
    }

    @Override
    public List<ResponseGeminiChat> getConversacion(String SessionId, String sessionUIDConversacion) throws Exception {

        ResponseLogin responseLogin = validarSesion(SessionId);

        Map<String, Object> filters = new HashMap<>();
        filters.put("userId", responseLogin.getUser().getIdUser());
        filters.put("sessionUID", sessionUIDConversacion);

        Map<String, Object> filtersNotEquals = new HashMap<>();

        List<GeminiChats> historyChats = geminiChatsDAO.getGeminiChatsByFilters(
                filters, filtersNotEquals, Constantes.CANTIDAD_MIL_INTEGER, "id");

        // Invertir el orden de la lista (cronológico)
        Collections.reverse(historyChats);

        List<Long> chatIds = historyChats.stream().map(GeminiChats::getId).toList();
        Map<Long, List<GeminiChatsFiles>> filesByChat = new HashMap<>();
        for (GeminiChatsFiles f : geminiChatsFilesDAO.listarPorChatIds(chatIds, Constantes.REGISTRO_ACTIVO)) {
            filesByChat.computeIfAbsent(f.getGeminiChatId(), k -> new ArrayList<>()).add(f);
        }

        return historyChats.stream()
                .map(c -> ResponseGeminiChat.from(c, filesByChat.getOrDefault(c.getId(), Collections.emptyList())))
                .toList();
    }

    // ====================================================================
    // Invocación a Gemini (Vertex AI SDK, REST, mismo patrón que calificación)
    // ====================================================================
    private String invocarGeminiChat(Configurations configurations,
                                     List<GeminiChats> historial,
                                     Map<Long, List<GeminiChatsFiles>> filesByChat,
                                     String prompt,
                                     List<GeminiChatsFiles> archivosTurno) throws IOException {

        GoogleCredentials credentials = getCredentials();
        credentials.refreshIfExpired();

        try (VertexAI vertexAI = crearClienteVertex(credentials)) {

            // Historial multimodal: cada turno previo se reinyecta con su texto Y sus adjuntos
            // (URI gs:// para formatos nativos; texto extraído para Word), como pares user/model.
            List<Content> contents = new ArrayList<>();
            for (GeminiChats turno : historial) {
                if (turno == null || turno.getPrompt() == null || turno.getResponse() == null) {
                    continue;
                }
                contents.add(construirTurnoUsuario(turno.getPrompt(),
                        filesByChat.getOrDefault(turno.getId(), Collections.emptyList())));
                contents.add(ContentMaker.forRole("model").fromString(turno.getResponse()));
            }

            // Turno actual (texto + nuevos adjuntos)
            contents.add(construirTurnoUsuario(prompt, archivosTurno));

            float temperatureValue = configurations.getTemperature() != null
                    ? configurations.getTemperature().floatValue()
                    : 0.3f;
            int maxOutputTokens = configurations.getMaxOutputTokens() != null
                    ? configurations.getMaxOutputTokens()
                    : 8192;

            GenerationConfig generationConfig = GenerationConfig.newBuilder()
                    .setTemperature(temperatureValue)
                    .setMaxOutputTokens(maxOutputTokens)
                    .build();

            GenerativeModel model = new GenerativeModel(configurations.getModel(), vertexAI)
                    .withSystemInstruction(ContentMaker.fromString(configurations.getRoleSystem()))
                    .withGenerationConfig(generationConfig);

            long tIni = System.nanoTime();
            GenerateContentResponse generateContentResponse = model.generateContent(contents);
            String texto = ResponseHandler.getText(generateContentResponse);
            logger.info("[GeminiChat] modelo={} turnosHistorial={} adjuntosTurno={} tiempoIA={}s",
                    configurations.getModel(), historial.size(), archivosTurno.size(),
                    String.format("%.2f", (System.nanoTime() - tIni) / 1_000_000_000.0));
            return texto;
        }
    }

    /**
     * Construye el Content de un turno de usuario: adjuntos primero (fileData con URI gs:// para
     * formatos nativos de Gemini; texto extraído con POI para Word) y el prompt al final, igual
     * que el patrón del chat multimodal de prueba.
     */
    private Content construirTurnoUsuario(String prompt, List<GeminiChatsFiles> archivos) {
        List<Object> multiModalData = new ArrayList<>();
        for (GeminiChatsFiles archivo : archivos) {
            if (archivo.getTextoExtraido() != null && !archivo.getTextoExtraido().isEmpty()) {
                multiModalData.add("\n--- Contenido del documento Word adjunto (" + archivo.getFileName() + ") ---\n"
                        + archivo.getTextoExtraido()
                        + "\n--------------------------------------------\n");
            } else if (archivo.getGcsUri() != null && !archivo.getGcsUri().isEmpty()) {
                multiModalData.add(PartMaker.fromMimeTypeAndData(archivo.getMimeType(), archivo.getGcsUri()));
            }
        }
        multiModalData.add(prompt);
        return ContentMaker.forRole("user").fromMultiModalData(multiModalData.toArray());
    }

    // ====================================================================
    // Procesamiento de documento por secciones (corrección ortotipográfica)
    // ====================================================================
    @Override
    public ResponseDocumentGemini processDocument(InputDocument inputDocument) throws Exception {

        Map<String, Object> filters = new HashMap<>();
        filters.put("borrado", Constantes.REGISTRO_NO_BORRADO);
        filters.put("activo", Constantes.REGISTRO_ACTIVO);
        filters.put("serviceCode", SERVICE_CODE_DOCUMENT);

        Configurations configurations = configurationDAO.getConfigurationsByFilters(filters);

        if (configurations == null || configurations.getId() == null) {
            throw new ValidationServiceException("La Configuración de Comunicación con la IA no está realizada adecuadamente, comunicarlo a un administrador");
        }

        // Copia de las secciones (misma lógica que el flujo ChatGPT): las que no van a la IA
        // (isSendIA = 0) quedan marcadas como procesadas de entrada.
        List<SectionTemplate> sectionTemplatesResponse = new ArrayList<>();

        for (SectionTemplate sectionTemplate : inputDocument.getSectionTemplates()) {
            SectionTemplate newSectionTemplate = new SectionTemplate();

            newSectionTemplate.setId(sectionTemplate.getId());
            newSectionTemplate.setIdTemplate(sectionTemplate.getIdTemplate());
            newSectionTemplate.setCodigo(sectionTemplate.getCodigo());
            newSectionTemplate.setContent(sectionTemplate.getContent());
            newSectionTemplate.setDescripcion(sectionTemplate.getDescripcion());
            newSectionTemplate.setIsFinal(sectionTemplate.getIsFinal());
            newSectionTemplate.setIsBold(sectionTemplate.getIsBold());
            newSectionTemplate.setIsSendIA(sectionTemplate.getIsSendIA());
            newSectionTemplate.setIsSaltoLinea(sectionTemplate.getIsSaltoLinea());
            newSectionTemplate.setIsProcessed(false);

            if (sectionTemplate.getIsSendIA().equals(Constantes.REGISTRO_INACTIVO))
                newSectionTemplate.setIsProcessed(true);

            sectionTemplatesResponse.add(newSectionTemplate);
        }

        // Caché: correcciones previas del expediente/plantilla (ventana de 7 días, igual que
        // ExpedienteCompletions). Solo se reutilizan registros exitosos.
        List<GeminiExpedienteChats> previos = geminiExpedienteChatsDAO.findGeminiExpedienteChats(
                inputDocument.getNUnico(), inputDocument.getCodeTemplate());

        for (SectionTemplate seccion : sectionTemplatesResponse) {
            if (!seccion.getIsProcessed() && previos != null && !previos.isEmpty()) {
                for (GeminiExpedienteChats previo : previos) {
                    if (previo.getSectionId() != null && previo.getSectionId().equals(seccion.getId())
                            && Constantes.COMPLETION_EXITOSO.equals(previo.getStatus())
                            && previo.getResponse() != null && !previo.getResponse().isEmpty()) {
                        seccion.setContent(previo.getResponse());
                        seccion.setIsProcessed(true);
                        break;
                    }
                }
            }
        }

        String UIDSession = UUID.randomUUID().toString();
        BigDecimal temperature = configurations.getTemperature() != null
                ? configurations.getTemperature().setScale(1, RoundingMode.HALF_UP)
                : BigDecimal.ZERO.setScale(1, RoundingMode.HALF_UP);

        List<SectionTemplate> pendientes = sectionTemplatesResponse.stream()
                .filter(s -> !s.getIsProcessed())
                .toList();

        double timeSecondsIA = 0.0;
        int seccionesProcesadasIA = 0;

        if (!pendientes.isEmpty()) {

            GoogleCredentials credentials = getCredentials();
            credentials.refreshIfExpired();

            float temperatureValue = configurations.getTemperature() != null
                    ? configurations.getTemperature().floatValue()
                    : 0.3f;
            int maxOutputTokens = configurations.getMaxOutputTokens() != null
                    ? configurations.getMaxOutputTokens()
                    : 8192;

            GenerationConfig generationConfig = GenerationConfig.newBuilder()
                    .setTemperature(temperatureValue)
                    .setMaxOutputTokens(maxOutputTokens)
                    .build();

            // Un solo cliente Vertex y un solo GenerativeModel para toda la corrida; cada
            // sección pendiente es una llamada independiente (sin historial entre secciones).
            try (VertexAI vertexAI = crearClienteVertex(credentials)) {

                GenerativeModel model = new GenerativeModel(configurations.getModel(), vertexAI)
                        .withSystemInstruction(ContentMaker.fromString(configurations.getRoleSystem()))
                        .withGenerationConfig(generationConfig);

                for (SectionTemplate seccion : pendientes) {

                    GeminiExpedienteChats registro = new GeminiExpedienteChats();
                    LocalDateTime fechaSend = LocalDateTime.now();

                    registro.setNUnico(inputDocument.getNUnico());
                    registro.setTemplateCode(inputDocument.getCodeTemplate());
                    registro.setSectionId(seccion.getId());
                    registro.setUserId(inputDocument.getIdUser());
                    registro.setModel(configurations.getModel());
                    registro.setRoleSystem(configurations.getRoleSystem());
                    registro.setRoleUser(seccion.getContent());
                    registro.setTemperature(temperature);
                    registro.setFechaSend(fechaSend);
                    registro.setConfigurations(configurations);
                    registro.setSessionUID(UIDSession);
                    registro.setStatus(Constantes.COMPLETION_INICIADO);

                    try {
                        registro = geminiExpedienteChatsDAO.registrar(registro);
                    } catch (Exception e) {
                        logger.error("Error registrando GeminiExpedienteChats: {}", e.getMessage());
                        throw new ValidationServiceException("El registro de Data no se ha realizado correctamente, por favor comunicarlo a un administrador");
                    }

                    long tIni = System.nanoTime();
                    String textoCorregido;
                    try {
                        GenerateContentResponse geminiResponse = model.generateContent(
                                ContentMaker.fromString(seccion.getContent()));
                        textoCorregido = ResponseHandler.getText(geminiResponse);
                    } catch (Exception ex) {
                        logger.error("Error invocando Gemini para la sección {}: {}", seccion.getId(), ex.getMessage(), ex);
                        registro.setStatus(Constantes.COMPLETION_ERROR);
                        registro.setResponse("Error Gemini: " + ex.getMessage());
                        try {
                            geminiExpedienteChatsDAO.modificar(registro);
                        } catch (Exception modErr) {
                            logger.error("Error actualizando GeminiExpedienteChats tras fallo Gemini: {}", modErr.getMessage());
                        }
                        // Las secciones ya corregidas quedaron cacheadas: un reintento retoma desde esta.
                        throw new ValidationServiceException("Error en la corrección de la sección "
                                + seccion.getId() + " con Gemini: " + ex.getMessage());
                    }
                    double seccionSeconds = (System.nanoTime() - tIni) / 1_000_000_000.0;

                    registro.setFechaResponse(LocalDateTime.now());
                    registro.setResponse(textoCorregido);
                    registro.setTimeSeconds(seccionSeconds);
                    registro.setStatus(Constantes.COMPLETION_EXITOSO);

                    try {
                        geminiExpedienteChatsDAO.modificar(registro);
                    } catch (Exception e) {
                        logger.error("Error actualizando GeminiExpedienteChats: {}", e.getMessage());
                    }

                    seccion.setContent(textoCorregido);
                    seccion.setIsProcessed(true);
                    timeSecondsIA += seccionSeconds;
                    seccionesProcesadasIA++;
                }
            }
        }

        logger.info("[GeminiDocumento] nUnico={} template={} seccionesIA={} (de {} secciones) tiempoIA={}s",
                inputDocument.getNUnico(), inputDocument.getCodeTemplate(), seccionesProcesadasIA,
                sectionTemplatesResponse.size(), String.format("%.2f", timeSecondsIA));

        ResponseDocumentGemini response = new ResponseDocumentGemini();

        response.setNUnico(inputDocument.getNUnico());
        response.setCodeTemplate(inputDocument.getCodeTemplate());
        response.setSectionTemplates(sectionTemplatesResponse);
        response.setModel(configurations.getModel());
        response.setRoleSystem(configurations.getRoleSystem());
        response.setTemperature(temperature);
        response.setSeccionesProcesadasIA(seccionesProcesadasIA);
        response.setTimeSeconds(timeSecondsIA);
        response.setConfigurationsId(configurations.getId());
        response.setSessionUID(UIDSession);

        return response;
    }

    /**
     * Construye el cliente Vertex AI con la configuración probada del proyecto: transporte REST,
     * endpoint global, timeouts largos (5 min RPC / 10 min total) y proxy conmutable. Compartido
     * por el chat conversacional y el procesamiento de documentos.
     */
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

    // ====================================================================
    // Adjuntos: MIME y extracción de Word
    // ====================================================================
    /** Resuelve el MIME del adjunto; si el multipart no lo trae (o es genérico), se infiere por extensión. */
    private String resolverMime(InputGeminiChatFile adjunto) {
        String mime = adjunto.getMimeType();
        if (mime != null && !mime.isBlank() && !"application/octet-stream".equalsIgnoreCase(mime)) {
            return mime;
        }
        String fileName = adjunto.getFileName() != null ? adjunto.getFileName() : "";
        int punto = fileName.lastIndexOf('.');
        String extension = punto >= 0 ? fileName.substring(punto + 1).toLowerCase(Locale.ROOT) : "";
        String porExtension = MIME_POR_EXTENSION.get(extension);
        if (porExtension == null) {
            throw new ValidationServiceException("Tipo de archivo no soportado: " + fileName
                    + ". Formatos permitidos: PDF, imágenes, audio, video, Word (.doc/.docx) y texto plano");
        }
        return porExtension;
    }

    /**
     * Extrae el texto de documentos Word (.docx con XWPF, .doc con HWPF), que Gemini no acepta
     * nativamente. Devuelve null para el resto de formatos (se envían como fileData).
     */
    private String extraerTextoWord(String mime, byte[] contenido) throws IOException {
        if (MIME_DOCX.equals(mime)) {
            try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(contenido));
                 XWPFWordExtractor extractor = new XWPFWordExtractor(doc)) {
                return extractor.getText();
            }
        }
        if (MIME_DOC.equals(mime)) {
            try (HWPFDocument doc = new HWPFDocument(new ByteArrayInputStream(contenido));
                 WordExtractor extractor = new WordExtractor(doc)) {
                return extractor.getText();
            }
        }
        return null;
    }

    // ====================================================================
    // Soporte: sesión, error, Kafka, sedes, credenciales, proxy
    // ====================================================================
    /**
     * Valida la sesión contra el servicio de seguridad y devuelve los datos del usuario autenticado.
     * Lanza {@link ValidationSessionServiceException} si la sesión es nula, vacía o inválida.
     */
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

    /** Marca el turno con error y persiste (best-effort: un fallo aquí no debe tapar el error original). */
    private void marcarError(GeminiChats chat, String mensaje) {
        chat.setStatus(Constantes.COMPLETION_ERROR);
        chat.setResponse(mensaje);
        try {
            geminiChatsDAO.modificar(chat);
        } catch (Exception ex) {
            logger.error("Error actualizando GeminiChats tras fallo: {}", ex.getMessage());
        }
    }

    private void publicarKafka(ResponseGeminiChat payload) {
        try {
            kafkaTemplate.send(KAFKA_TOPIC, String.valueOf(payload.getId()), payload);
        } catch (Exception ex) {
            logger.warn("Error publicando en Kafka topic {}: {}", KAFKA_TOPIC, ex.getMessage());
        }
    }

    /** Sedes/instancias del usuario para métricas, igual que en ChatGPT (best-effort). */
    private List<Sedes> obtenerSedes(String sessionId) {
        List<Sedes> sedes = new ArrayList<>();
        try {
            List<DataSedeDTO> sedesDTO = judicialService.GetSedes(sessionId);
            List<DataInstanciaDTO> instanciasDTOS = judicialService.GetInstancias(sessionId);

            sedesDTO.forEach(sedeDTO -> {
                Sedes sede = new Sedes();
                sede.setCodSede(sedeDTO.getCodigoSede());
                sede.setSede(sedeDTO.getSede());

                List<Instancias> instancias = new ArrayList<>();

                instanciasDTOS.forEach(instanciaDTO -> {
                    Instancias instancia = new Instancias();
                    instancia.setCodInstancia(instanciaDTO.getCodigoInstancia());
                    instancia.setInstancia(instanciaDTO.getInstancia());
                    instancias.add(instancia);
                });

                sede.setInstancias(instancias);
                sedes.add(sede);
            });
        } catch (Exception ex) {
            logger.warn("No se pudieron obtener sedes/instancias para métricas: {}", ex.getMessage());
        }
        return sedes;
    }

    /**
     * Credenciales cacheadas a nivel de servicio (mismo esquema que GeminiServiceImpl): se
     * construyen una sola vez desde el JSON del service account y el token OAuth se refresca
     * bajo demanda.
     */
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
     * Proxy para el tráfico hacia Vertex / Gemini: mismo punto de conmutación que el resto de
     * flujos Google del microservicio (proxy nuevo PAC ADcsjan cuando esté habilitado).
     */
    private NetHttpTransport buildHttpTransport() {
        if (Boolean.TRUE.equals(properties.getProxyGoogleEnabled())) {
            java.net.Proxy proxy = new java.net.Proxy(java.net.Proxy.Type.HTTP,
                    new java.net.InetSocketAddress(properties.getProxyGoogleHost(), properties.getProxyGooglePort()));
            return new NetHttpTransport.Builder().setProxy(proxy).build();
        }
        return new NetHttpTransport.Builder().build();
    }
}
