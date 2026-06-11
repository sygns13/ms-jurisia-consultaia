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
import com.google.cloud.vertexai.api.GenerationConfig.ThinkingConfig;
import com.google.cloud.vertexai.api.PredictionServiceClient;
import com.google.cloud.vertexai.api.PredictionServiceSettings;
import com.google.cloud.vertexai.generativeai.ContentMaker;
import com.google.cloud.vertexai.generativeai.GenerativeModel;
import com.google.cloud.vertexai.generativeai.PartMaker;
import com.google.cloud.vertexai.generativeai.ResponseHandler;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.threeten.bp.Duration;
import pj.gob.pe.consultaia.configuration.ConfigProperties;
import pj.gob.pe.consultaia.dao.mysql.ConfigurationDAO;
import pj.gob.pe.consultaia.dao.mysql.DemandasCalificadasDAO;
import pj.gob.pe.consultaia.exception.ValidationServiceException;
import pj.gob.pe.consultaia.exception.ValidationSessionServiceException;
import pj.gob.pe.consultaia.model.beans.DemandasCalificadasToKafka;
import pj.gob.pe.consultaia.model.entities.Configurations;
import pj.gob.pe.consultaia.model.entities.DemandasCalificadas;
import pj.gob.pe.consultaia.service.business.ChunkStoreService;
import pj.gob.pe.consultaia.service.business.GeminiService;
import pj.gob.pe.consultaia.service.externals.FtpService;
import pj.gob.pe.consultaia.service.externals.GcsStorageService;
import pj.gob.pe.consultaia.service.externals.SecurityService;
import pj.gob.pe.consultaia.utils.Constantes;
import pj.gob.pe.consultaia.utils.DocxGeneratorUtil;
import pj.gob.pe.consultaia.utils.PdfMergeUtil;
import pj.gob.pe.consultaia.utils.beans.inputs.InputCalificacionDemanda;
import pj.gob.pe.consultaia.utils.beans.inputs.InputDescargaCalificacion;
import pj.gob.pe.consultaia.utils.beans.inputs.InputListadoDemandasCalificadas;
import pj.gob.pe.consultaia.utils.beans.responses.ResponseCalificacionDemanda;
import pj.gob.pe.consultaia.utils.beans.responses.ResponseCalificacionDemandaDocx;
import pj.gob.pe.consultaia.utils.beans.responses.ResponseListadoDemandaCalificada;
import pj.gob.pe.consultaia.utils.beans.responses.ResponseLogin;
import pj.gob.pe.consultaia.utils.beans.responses.UserLogin;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
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
    private final GcsStorageService gcsStorageService;

    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * Cache en memoria del dominio público del Index Endpoint (resuelto vía metadata REST).
     * Se calcula una sola vez tras el arranque y se reutiliza; evita un GET por calificación.
     * Solo se usa cuando {@code gcp.vectorSearchPublicDomain} no está configurado en properties.
     */
    private volatile String publicDomainCache;

    /**
     * Credenciales cacheadas a nivel de servicio (singleton). El token OAuth (TTL ~1h) se reutiliza
     * entre calificaciones; {@code refreshIfExpired()} solo va a la red cuando realmente expiró,
     * evitando el round-trip de firma JWT + intercambio de token en cada request.
     */
    private volatile GoogleCredentials cachedCredentials;

    private static final String SERVICE_CODE = "geminy_demanda_1";
    private static final String KAFKA_TOPIC = "judicial-metrics-califications";

    /**
     * Prompt interno (Fase 1) para que Gemini extraiga los conceptos jurídicos clave de la
     * demanda. Estos términos alimentan la búsqueda vectorial; no proviene de la BD porque
     * es parte del mecanismo RAG, no de la configuración funcional de la calificación.
     */
    /*
    private static final String PROMPT_EXTRACCION_CONCEPTOS =
            "Lee esta demanda adjunta. Extrae ÚNICAMENTE una lista de los conceptos " +
            "jurídicos procesales involucrados, el tipo de proceso, y las posibles omisiones de forma. " +
            "Responde solo con palabras clave separadas por comas, sin explicaciones.";
    */
    private static final String PROMPT_EXTRACCION_CONCEPTOS =
            "Lee la demanda adjunta. Tu objetivo es generar términos de búsqueda para un sistema de RAG vectorial de normativa peruana. " +
                    "Extrae: 1) Los conceptos jurídicos clave y el tipo de proceso. 2) Posibles omisiones formales (ej. falta de firma, falta de anexos, DNI). " +
                    "Responde ÚNICAMENTE con una lista de palabras clave y artículos legales relevantes separados por comas, sin explicaciones ni viñetas.";

    private static final String ROLE_SYSTEM = "Eres un Asistente Judicial Virtual experto en derecho procesal peruano. " +
            "Tu función es calificar demandas (generando Autos Admisorios, de Inadmisibilidad o Improcedencia) analizando " +
            "los hechos frente a la normativa vigente. \n" +
            "REGLA ESTRICTA: Tu respuesta debe ser ÚNICAMENTE el borrador de la resolución judicial. DEBES respetar rigurosamente " +
            "la estructura formal de las resoluciones judiciales peruanas, incluyendo siempre una cabecera completa con los datos de " +
            "identificación del expediente, partes y juzgado, sin omitir ningún campo.";

    private static final String PROMPT_DEFAULT =
            "Por favor, califica la demanda adjunta utilizando los artículos normativos recuperados y siguiendo ESTRICTAMENTE las reglas y la estructura que se detallan a continuación.\n\n" +

                    "<instrucciones>\n" +
                    "1. Extrae de la demanda los datos para la CABECERA (Expediente, Materia, Juez, Especialista, Demandado, Demandante). Si un dato (como el nombre del Juez o Especialista) no aparece en la demanda, utiliza el marcador '[Por designar]'. NUNCA omitas los campos de la cabecera.\n" +
                    "2. Evalúa la demanda paso a paso utilizando la <guia_de_calificacion>.\n" +
                    "3. Si la demanda incumple requisitos del Art. 427 CPC, redacta una resolución de IMPROCEDENCIA.\n" +
                    "4. Si la demanda incumple requisitos del Art. 424, 130, 425 CPC o pago de aranceles, redacta una resolución de INADMISIBILIDAD.\n" +
                    "5. Si la demanda cumple con todos los requisitos, redacta un AUTO ADMISORIO siguiendo exactamente la estructura de la <plantilla_ejemplo>.\n" +
                    "</instrucciones>\n\n" +

                    "<guia_de_calificacion>\n" +
                    "REQUISITOS DE PROCEDIBILIDAD (Art. 427 CPC - Improcedencia):\n" +
                    "- Demandante carece de legitimidad o interés para obrar.\n" +
                    "- Caducidad del derecho.\n" +
                    "- No existe conexión lógica entre hechos y petitorio.\n" +
                    "- Petitorio jurídica o físicamente imposible.\n\n" +
                    "REQUISITOS DE ADMISIBILIDAD (Art. 424, 130, 425 CPC y Res. Adm. 481-2025-CE-PJ - Inadmisibilidad):\n" +
                    "- Art 424: Designación del juez, datos completos y domicilios/casillas de demandante y demandado, petitorio claro, fundamentos de hecho y derecho, monto, medios probatorios, firma de demandante y abogado.\n" +
                    "- Art 130: Anexos identificados correctamente y otrosíes independientes.\n" +
                    "- Art 425: Copia legible de DNI, copias de medios probatorios.\n" +
                    "- Aranceles: Pago por ofrecimiento de pruebas y notificación (salvo defensa pública o pretensión alimentaria menor a 20 URP).\n" +
                    "</guia_de_calificacion>\n\n" +

                    "<reglas_redaccion>\n" +
                    "- Usa la <plantilla_ejemplo> como modelo de estructura, estilo y formalidades: cabecera, \n" +
                    "  \"RESOLUCIÓN NÚMERO...\", lugar y fecha en letras, considerandos numerados con subtítulos \n" +
                    "  (tutela jurisdiccional, calificación de la demanda, legitimidad, competencia, etc.) y \n" +
                    "  parte resolutiva con numerales romanos.\n" +
                    "- La cabecera DEBE incluir: juzgado y sede, EXPEDIENTE, MATERIA, JUEZ, ESPECIALISTA, \n" +
                    "  DEMANDADO y DEMANDANTE, tomados exclusivamente de <datos_expediente> con excepción de juzgado este tomarlo de la plantilla de ejemplo.\n" +
                    "- Cita los artículos aplicables apoyándote en <guia_de_calificacion> y <normativa_recuperada>; \n" +
                    "  no cites normas que no figuren en esos contextos ni en la demanda.\n" +
                    "- Si un dato no consta, escribe [COMPLETAR: dato]. Prohibido inventar datos, fechas, \n" +
                    "  enlaces o nombres.\n" +
                    "- Tu respuesta es ÚNICAMENTE el texto de la resolución, sin comentarios ni explicaciones. \n" +
                    "</reglas_redaccion>\n\n" +

                    "<plantilla_ejemplo>\n" +
                    "[Nombre de Juzgado]\n" +
                    "EXPEDIENTE     : [Número de Expediente]\n" +
                    "MATERIA        : [Materia de la demanda]\n" +
                    "JUEZ           : [Nombre del Juez]\n" +
                    "ESPECIALISTA   : [Nombre del Especialista]\n" +
                    "DEMANDADO      : [Nombre completo]\n" +
                    "DEMANDANTE     : [Nombre completo]\n\n" +
                    "AUTO [ADMISORIO / INADMISIBILIDAD / IMPROCEDENCIA]\n" +
                    "RESOLUCIÓN NÚMERO UNO\n" +
                    "[Ciudad], [Fecha actual]. -\n\n" +
                    "AUTOS Y VISTOS; ...\n" +
                    "CONSIDERANDO:\n" +
                    "[Desarrollar aquí los considerandos de Tutela, Calificación, Legitimación, etc., basándote en la normativa recuperada y la guía de calificación]\n\n" +
                    "SE RESUELVE:\n" +
                    "[Fallo correspondiente: Admitir, declarar inadmisible otorgando plazo, o rechazar la demanda].\n" +
                    "</plantilla_ejemplo>\n\n" +

                    "Basado en esto, redacta la resolución final.";

    @Override
    public ResponseCalificacionDemanda calificarDemanda(InputCalificacionDemanda input, String sessionId) throws Exception {

        long start = System.nanoTime();

        ResponseLogin responseLogin = validarSesion(sessionId);

        Map<String, Object> filters = new HashMap<>();
        filters.put("borrado", Constantes.REGISTRO_NO_BORRADO);
        filters.put("activo", Constantes.REGISTRO_ACTIVO);
        filters.put("serviceCode", SERVICE_CODE);

        Configurations configurations = configurationDAO.getConfigurationsByFilters(filters);

        if (configurations == null || configurations.getId() == null) {
            throw new ValidationServiceException("La Configuración de Comunicación con la IA no está realizada adecuadamente, comunicarlo a un administrador");
        }

        configurations.setRoleSystem(ROLE_SYSTEM);
        configurations.setPromptDefault(PROMPT_DEFAULT);
        configurations.setPromptDefault(configurations.getPromptDefault().replace("[Nombre de Juzgado]", input.getXnomInstancia()));

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
        demanda.setXnombreArchivo(String.join("; ", input.getArchivos()));
        demanda.setNincidente(input.getNincidente());
        demanda.setXrutaArchivo(input.getXrutaArchivo());
        demanda.setXdescMateria(input.getXdescMateria());
        demanda.setFinicio(input.getFinicio());
        demanda.setStatus(Constantes.CALIFICACION_DEMANDA_INICIADA);

        demanda = demandasCalificadasDAO.registrar(demanda);

        byte[] pdfBytes;
        try {
            // Una demanda puede estar formada por 1..n archivos PDF. Se descargan todos desde
            // la misma ruta FTP (una sola conexión) en el orden recibido y se unen en un solo PDF
            // antes de continuar con el flujo de calificación.
            List<byte[]> pdfsDescargados = ftpService.descargarArchivos(input.getRutaCompleta(), input.getArchivos());
            pdfBytes = PdfMergeUtil.unir(pdfsDescargados);
        } catch (Exception ex) {
            logger.error("Error descargando archivo FTP: {}", ex.getMessage(), ex);
            demanda.setStatus(Constantes.CALIFICACION_DEMANDA_ERROR_FILE_NOT_FOUND);
            demanda.setResponse("Error FTP: " + ex.getMessage());
            try {
                demanda = demandasCalificadasDAO.modificar(demanda);
            } catch (Exception modErr) {
                logger.error("Error actualizando DemandasCalificadas tras fallo FTP: {}", modErr.getMessage());
            }
            publicarKafka(demanda, responseLogin.getUser());
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
            publicarKafka(demanda, responseLogin.getUser());
            throw new ValidationServiceException("Error en la calificación con Gemini: " + ex.getMessage());
        }

        LocalDateTime fechaResponse = LocalDateTime.now();
        double seconds = (System.nanoTime() - start) / 1_000_000_000.0;

        demanda.setFechaResponse(fechaResponse);
        demanda.setResponse(fullText);
        demanda.setTimeSeconds(seconds);
        demanda.setStatus(Constantes.CALIFICACION_DEMANDA_EXITOSA);

        demanda = demandasCalificadasDAO.modificar(demanda);

        publicarKafka(demanda, responseLogin.getUser());

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

        String expediente = input.getXformato() != null ? input.getXformato() : "";
        String nombreArchivo = String.format("%s_calificacion_demanda.docx", expediente);

        ResponseCalificacionDemandaDocx response = new ResponseCalificacionDemandaDocx();
        response.setDocumento(docxBytes);
        response.setNombreArchivo(nombreArchivo);

        return response;
    }

    @Override
    public Page<ResponseListadoDemandaCalificada> listarDemandasCalificadas(InputListadoDemandasCalificadas input,
                                                                            Pageable pageable,
                                                                            String sessionId) throws Exception {

        ResponseLogin responseLogin = validarSesion(sessionId);
        Long userId = responseLogin.getUser().getIdUser();

        InputListadoDemandasCalificadas filtros = input != null ? input : new InputListadoDemandasCalificadas();

        // Los filtros llegan como fecha (yyyy-MM-dd) pero fechaSend es datetime: se expande el rango
        // al inicio del día inicial y al fin del día final (ambos inclusive).
        LocalDateTime fechaDesde = filtros.getFechaInicial() != null
                ? filtros.getFechaInicial().atStartOfDay() : null;
        LocalDateTime fechaHasta = filtros.getFechaFinal() != null
                ? filtros.getFechaFinal().atTime(LocalTime.MAX) : null;

        Page<DemandasCalificadas> pagina = demandasCalificadasDAO.listarPorFiltros(
                userId, fechaDesde, fechaHasta, filtros.getAnio(), filtros.getExpNro(), pageable);

        return pagina.map(ResponseListadoDemandaCalificada::fromEntity);
    }

    @Override
    public ResponseCalificacionDemandaDocx descargarCalificacionDocx(InputDescargaCalificacion input, String sessionId) throws Exception {

        ResponseLogin responseLogin = validarSesion(sessionId);
        Long userId = responseLogin.getUser().getIdUser();

        DemandasCalificadas demanda = demandasCalificadasDAO.listarPorId(input.getId());

        if (demanda == null) {
            throw new ValidationServiceException("No se encontró la calificación de demanda solicitada");
        }

        // La calificación solo puede ser descargada por el usuario propietario del registro.
        if (demanda.getUserId() == null || !demanda.getUserId().equals(userId)) {
            throw new ValidationSessionServiceException("La calificación de demanda no pertenece al usuario de la sesión");
        }

        byte[] docxBytes = DocxGeneratorUtil.textToDocx(demanda.getResponse());

        String expediente = demanda.getXformato() != null ? demanda.getXformato() : "";
        String nombreArchivo = String.format("%s_calificacion_demanda.docx", expediente);

        ResponseCalificacionDemandaDocx response = new ResponseCalificacionDemandaDocx();
        response.setDocumento(docxBytes);
        response.setNombreArchivo(nombreArchivo);

        return response;
    }

    @Override
    public Page<ResponseListadoDemandaCalificada> listarUltimaVersionDemandasCalificadas(InputListadoDemandasCalificadas input,
                                                                                         Pageable pageable,
                                                                                         String sessionId) throws Exception {

        ResponseLogin responseLogin = validarSesion(sessionId);
        Long userId = responseLogin.getUser().getIdUser();

        InputListadoDemandasCalificadas filtros = input != null ? input : new InputListadoDemandasCalificadas();

        // Los filtros llegan como fecha (yyyy-MM-dd) pero fechaSend es datetime: se expande el rango
        // al inicio del día inicial y al fin del día final (ambos inclusive).
        LocalDateTime fechaDesde = filtros.getFechaInicial() != null
                ? filtros.getFechaInicial().atStartOfDay() : null;
        LocalDateTime fechaHasta = filtros.getFechaFinal() != null
                ? filtros.getFechaFinal().atTime(LocalTime.MAX) : null;

        Page<DemandasCalificadas> pagina = demandasCalificadasDAO.listarUltimaVersionPorNunico(
                userId, fechaDesde, fechaHasta, filtros.getAnio(), filtros.getExpNro(), pageable);

        return pagina.map(ResponseListadoDemandaCalificada::fromEntity);
    }

    @Override
    public List<ResponseListadoDemandaCalificada> listarCalificacionesPorNunico(Long nUnico, String sessionId) throws Exception {

        ResponseLogin responseLogin = validarSesion(sessionId);
        Long userId = responseLogin.getUser().getIdUser();

        List<DemandasCalificadas> registros = demandasCalificadasDAO.listarPorNunico(userId, nUnico);

        return registros.stream()
                .map(ResponseListadoDemandaCalificada::fromEntity)
                .toList();
    }

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

    private void publicarKafka(DemandasCalificadas demanda, UserLogin user) {
        try {
            demanda.setConfigurationsId(demanda.getConfigurations() != null ? demanda.getConfigurations().getId() : null);
            DemandasCalificadasToKafka payload = DemandasCalificadasToKafka.from(demanda, user);
            kafkaTemplate.send(KAFKA_TOPIC, String.valueOf(demanda.getId()), payload);
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

        // Diagnóstico: tamaño del PDF y estimación de bytes en el cable. El PDF se incrusta inline
        // en base64 (~+33%) y se envía DOS veces (Fase 1 + Fase 4); este es el factor dominante de
        // latencia cuando el egress pasa por un proxy de bajo throughput.
        double pdfMb = pdfBytes.length / (1024.0 * 1024.0);
        logger.info("[Calificación diagnóstico] PDF={} bytes (~{} MB) | inline base64 ~{} MB por envío x2 (Fase1+Fase4) ~{} MB",
                pdfBytes.length, String.format("%.2f", pdfMb),
                String.format("%.2f", pdfMb * 4.0 / 3.0), String.format("%.2f", pdfMb * 4.0 / 3.0 * 2));

        // Credenciales cacheadas: el token se reutiliza entre calificaciones hasta que expira.
        GoogleCredentials credentials = getCredentials();

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

        // El PDF se sube UNA sola vez a GCS; ambas fases lo referencian por URI (gs://) para que
        // Vertex lo lea server-side y no reenviar los bytes por el proxy. Se borra al finalizar.
        long tGcsIni = System.nanoTime();
        String gsUri = gcsStorageService.subir(pdfBytes, "application/pdf");
        long tGcsFin = System.nanoTime();

        try {
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

            var documentPart = PartMaker.fromMimeTypeAndData("application/pdf", gsUri);

            // ============================================================
            // FASE 1: EXTRACCIÓN DE INTENCIÓN (conceptos jurídicos clave)
            // Modelo Flash + thinking desactivado: es un paso de RECUPERACIÓN, no el análisis
            // final, por lo que se prioriza velocidad y costo. La Fase 4 mantiene su capacidad.
            // ============================================================
            long tFase1Ini = System.nanoTime();
            GenerativeModel modeloExtraccion = new GenerativeModel(properties.getGcpExtractionModel(), vertexAI)
                    .withGenerationConfig(GenerationConfig.newBuilder()
                            .setTemperature(0.0f)
                            .setThinkingConfig(ThinkingConfig.newBuilder()
                                    .setThinkingBudget(0) // sin razonamiento: extracción simple de keywords
                                    .setIncludeThoughts(false)
                                    .build())
                            .build());

            String terminosClave = ResponseHandler.getText(
                    modeloExtraccion.generateContent(
                            ContentMaker.fromMultiModalData(documentPart, PROMPT_EXTRACCION_CONCEPTOS))
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
            // FASE 4: GENERACIÓN FINAL (resolución de calificación, configurada desde BD)
            // Modelo, roleSystem, promptDefault, temperature y maxOutputTokens de BD. Capacidad SIN cambios.
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

            String resolucionFinal = ResponseHandler.getText(
                    modeloFinal.generateContent(ContentMaker.fromMultiModalData(documentPart, promptEnriquecido))
            );
            long tFase4Fin = System.nanoTime();

            logger.info("[Calificación tiempos] GCS-upload={}s | Fase1(extracción {})={}s | Fase2(embedding)={}s | " +
                            "Fase3(vectorSearch)={}s | Fase4(redacción {})={}s | Total IA={}s",
                    seg(tGcsIni, tGcsFin), properties.getGcpExtractionModel(), seg(tFase1Ini, tFase1Fin),
                    seg(tFase1Fin, tFase2Fin), seg(tFase2Fin, tFase3Fin), configurations.getModel(),
                    seg(tFase3Fin, tFase4Fin), seg(tGcsIni, tFase4Fin));

            return resolucionFinal;
        }
        } finally {
            try {
                gcsStorageService.borrar(gsUri);
            } catch (Exception ex) {
                logger.warn("[GCS] No se pudo borrar el objeto temporal {}: {}", gsUri, ex.getMessage());
            }
        }
    }

    /**
     * Devuelve las credenciales cacheadas a nivel de servicio. Se construyen una sola vez desde el
     * JSON del service account; el token OAuth se refresca bajo demanda vía {@code refreshIfExpired()}
     * en el llamador, reutilizándose entre calificaciones.
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

    /** Formatea el lapso entre dos marcas de System.nanoTime() en segundos con 2 decimales. */
    private String seg(long fromNanos, long toNanos) {
        return String.format("%.2f", (toNanos - fromNanos) / 1_000_000_000.0);
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
     * Resuelve el dominio contra el que debe invocarse findNeighbors. Para endpoints públicos,
     * la búsqueda debe ir al publicEndpointDomainName dedicado; si no hay (endpoint privado/VPC),
     * se cae al endpoint regional estándar.
     *
     * Estrategia híbrida para evitar un GET de metadata por cada calificación:
     *  1. Si {@code gcp.vectorSearchPublicDomain} está configurado en properties, se usa directo
     *     (cero round-trips).
     *  2. Si no, se resuelve vía metadata una sola vez y se cachea en memoria ({@link #publicDomainCache}).
     */
    private String resolverDominioFindNeighbors(NetHttpTransport httpTransport, String accessToken,
                                                String indexEndpointResource) throws IOException {
        // 1) Dominio configurado explícitamente: se usa sin llamar a GCP.
        String configurado = properties.getGcpVectorSearchPublicDomain();
        if (configurado != null && !configurado.isBlank()) {
            return normalizarDominio(configurado);
        }

        // 2) Cache en memoria: solo la primera calificación tras el arranque hace el GET de metadata.
        if (publicDomainCache != null) {
            return publicDomainCache;
        }
        synchronized (this) {
            if (publicDomainCache == null) {
                String regionalBase = "https://" + properties.getGcpVectorSearchLocation() + "-aiplatform.googleapis.com";
                JsonNode resp = ejecutarGet(httpTransport, accessToken, regionalBase + "/v1/" + indexEndpointResource);
                String publicDomain = resp.path("publicEndpointDomainName").asText("");
                publicDomainCache = publicDomain.isEmpty() ? regionalBase : "https://" + publicDomain;
            }
        }
        return publicDomainCache;
    }

    /**
     * Antepone el esquema https:// al dominio si viene sin él, de modo que en properties se pueda
     * configurar tanto "xxxx.us-central1-123.vdb.vertexai.goog" como la URL completa.
     */
    private String normalizarDominio(String dominio) {
        String d = dominio.trim();
        return (d.startsWith("http://") || d.startsWith("https://")) ? d : "https://" + d;
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
