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
import pj.gob.pe.consultaia.dao.mysql.DemandasSentenciasDAO;
import pj.gob.pe.consultaia.exception.ValidationServiceException;
import pj.gob.pe.consultaia.exception.ValidationSessionServiceException;
import pj.gob.pe.consultaia.model.beans.DemandasSentenciasToKafka;
import pj.gob.pe.consultaia.model.entities.Configurations;
import pj.gob.pe.consultaia.model.entities.DemandasSentencias;
import pj.gob.pe.consultaia.service.business.ChunkStoreService;
import pj.gob.pe.consultaia.service.business.SentenciaService;
import pj.gob.pe.consultaia.service.externals.FtpService;
import pj.gob.pe.consultaia.service.externals.GcsStorageService;
import pj.gob.pe.consultaia.service.externals.SecurityService;
import pj.gob.pe.consultaia.utils.Constantes;
import pj.gob.pe.consultaia.utils.DocxGeneratorUtil;
import pj.gob.pe.consultaia.utils.PdfMergeUtil;
import pj.gob.pe.consultaia.utils.beans.inputs.InputDescargaSentencia;
import pj.gob.pe.consultaia.utils.beans.inputs.InputGeneracionSentencia;
import pj.gob.pe.consultaia.utils.beans.inputs.InputListadoDemandasSentencias;
import pj.gob.pe.consultaia.utils.beans.responses.ResponseGeneracionSentencia;
import pj.gob.pe.consultaia.utils.beans.responses.ResponseGeneracionSentenciaDocx;
import pj.gob.pe.consultaia.utils.beans.responses.ResponseListadoDemandaSentencia;
import pj.gob.pe.consultaia.utils.beans.responses.ResponseLogin;
import pj.gob.pe.consultaia.utils.beans.responses.UserLogin;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.*;

/**
 * Generación de la SENTENCIA de una demanda mediante RAG Orquestado (REST puro), espejo del flujo
 * de calificación ({@link GeminiServiceImpl}) pero con sus propios prompt, configuración
 * ({@code geminy_sentencia_1}), tabla ({@link DemandasSentencias}), tópico Kafka y timeout mayor.
 *
 * Diferencias clave respecto a la calificación:
 *  - El prompt solicita redactar la SENTENCIA (no la calificación/auto admisorio).
 *  - NO se inyectan los artículos estáticos de requisitos de calificación (CPC 424/425/427/130);
 *    el contexto legal proviene únicamente de la búsqueda vectorial dinámica.
 *  - Timeout un poco mayor que la calificación (7 min RPC / 15 min total).
 */
@Service
@RequiredArgsConstructor
public class SentenciaServiceImpl implements SentenciaService {

    private static final Logger logger = LoggerFactory.getLogger(SentenciaServiceImpl.class);

    private final SecurityService securityService;
    private final ConfigurationDAO configurationDAO;
    private final DemandasSentenciasDAO demandasSentenciasDAO;
    private final FtpService ftpService;
    private final ConfigProperties properties;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final ChunkStoreService chunkStoreService;
    private final GcsStorageService gcsStorageService;

    private final ObjectMapper mapper = new ObjectMapper();

    /** Cache en memoria del dominio público del Index Endpoint (resuelto vía metadata REST). */
    private volatile String publicDomainCache;

    /** Credenciales cacheadas a nivel de servicio (singleton); el token OAuth se reutiliza. */
    private volatile GoogleCredentials cachedCredentials;

    private static final String SERVICE_CODE = "geminy_sentencia_1";
    private static final String KAFKA_TOPIC = "judicial-metrics-sentencias";

    /**
     * Prompt interno (Fase 1) para que Gemini extraiga los conceptos jurídicos clave de la demanda.
     * Alimenta la búsqueda vectorial de normativa sustantiva aplicable al fondo de la sentencia.
     */
    private static final String PROMPT_EXTRACCION_CONCEPTOS =
            "Lee la demanda adjunta, INCLUYENDO sus anexos. Tu objetivo es generar términos de búsqueda para un sistema de RAG vectorial de normativa peruana, " +
                    "orientado a resolver el FONDO del asunto (no a calificar la admisibilidad). " +
                    "Extrae: 1) La pretensión y el tipo de proceso. 2) Las instituciones jurídicas sustantivas involucradas (causales, derechos, obligaciones). " +
                    "3) Los hechos relevantes y los medios probatorios ofrecidos que sustentan o contradicen la pretensión. " +
                    "Responde ÚNICAMENTE con una lista de palabras clave y artículos legales relevantes separados por comas, sin explicaciones ni viñetas.";

    private static final String ROLE_SYSTEM = "Eres un Asistente Judicial Virtual experto en derecho procesal y sustantivo peruano. " +
            "Tu función es redactar SENTENCIAS resolviendo el fondo de la controversia a partir de la demanda y sus anexos, " +
            "valorando los hechos y los medios probatorios frente a la normativa vigente. \n" +
            "REGLA ESTRICTA: Tu respuesta debe ser ÚNICAMENTE el borrador de la sentencia judicial. DEBES respetar rigurosamente " +
            "la estructura formal de las sentencias judiciales peruanas, incluyendo siempre una cabecera la cual no debe ser modificada " +
            "y debe enviar tal cual los datos se presentan en la <plantilla_ejemplo> que incluye los datos juzgado - sede, EXPEDIENTE, MATERIA, JUEZ, ESPECIALISTA, " +
            "DEMANDADO y DEMANDANTE";

    private static final String PROMPT_DEFAULT =
            "Por favor, redacta la SENTENCIA de la demanda adjunta apoyándote en la normativa recuperada y siguiendo ESTRICTAMENTE las reglas y la estructura que se detallan a continuación.\n\n" +

                    "<instrucciones>\n" +
                    "1. Los datos para la CABECERA (Expediente, Materia, Juez, Especialista, Demandado, Demandante) serán remitidos en la <plantilla_ejemplo>. No modificar estos datos y NUNCA omitas los campos de la cabecera.\n" +
                    "2. Identifica con precisión el PETITORIO y la causa de pedir a partir de la demanda y sus anexos.\n" +
                    "3. Establece los PUNTOS CONTROVERTIDOS que deben resolverse.\n" +
                    "4. Valora los HECHOS y los MEDIOS PROBATORIOS ofrecidos, indicando qué acreditan y su pertinencia respecto del petitorio.\n" +
                    "5. Aplica el DERECHO al caso concreto, fundamentando jurídicamente la decisión con la <normativa_recuperada> y la normativa citada en la propia demanda.\n" +
                    "6. Redacta una DECISIÓN (FALLO) congruente con el petitorio y con la fundamentación: declara FUNDADA, FUNDADA EN PARTE o INFUNDADA la demanda, precisando los efectos.\n" +
                    "</instrucciones>\n\n" +

                    "<reglas_redaccion>\n" +
                    "- Usa la <plantilla_ejemplo> como modelo de estructura, estilo y formalidades: cabecera, \n" +
                    "  \"SENTENCIA\", \"RESOLUCIÓN NÚMERO...\", lugar y fecha en letras, parte expositiva (VISTOS), \n" +
                    "  considerandos numerados con subtítulos (petitorio, puntos controvertidos, valoración de la \n" +
                    "  prueba, fundamentos jurídicos) y parte resolutiva (DECISIÓN / FALLO).\n" +
                    "- La cabecera DEBE incluir: juzgado - sede, EXPEDIENTE, MATERIA, JUEZ, ESPECIALISTA, \n" +
                    "  DEMANDADO y DEMANDANTE, tomados exclusivamente de la plantilla de ejemplo (No modificar estos datos).\n" +
                    "- Cita los artículos aplicables apoyándote en la <normativa_recuperada> y en las normas invocadas \n" +
                    "  en la demanda; no cites normas que no figuren en esos contextos.\n" +
                    "- Resuelve el FONDO del asunto. NO realices control de admisibilidad ni procedibilidad de la \n" +
                    "  demanda; asume que la demanda ya fue admitida y corresponde sentenciar.\n" +
                    "- Si un dato no consta, escribe [COMPLETAR: dato]. Prohibido inventar datos, fechas, \n" +
                    "  enlaces o nombres.\n" +
                    "- Tu respuesta es ÚNICAMENTE el texto de la sentencia, sin comentarios ni explicaciones. \n" +
                    "</reglas_redaccion>\n\n" +

                    "<plantilla_ejemplo>\n" +
                    "[Nombre_de_Juzgado]\n" +
                    "EXPEDIENTE     : [Número_de_Expediente]\n" +
                    "MATERIA        : [Materia_de_la_demanda]\n" +
                    "JUEZ           : [Nombre_del_Juez]\n" +
                    "ESPECIALISTA   : [Nombre_del_Especialista]\n" +
                    "DEMANDADO      : [Nombre_completo_Demandado]\n" +
                    "DEMANDANTE     : [Nombre_completo_Demandante]\n\n" +
                    "SENTENCIA\n" +
                    "RESOLUCIÓN NÚMERO [Número]\n" +
                    "[Ciudad], [Fecha actual]. -\n\n" +
                    "VISTOS; ...\n" +
                    "CONSIDERANDO:\n" +
                    "[Desarrollar petitorio, puntos controvertidos, valoración de los medios probatorios y los fundamentos jurídicos, basándote en la normativa recuperada]\n\n" +
                    "SE RESUELVE:\n" +
                    "[Fallo correspondiente: declarar FUNDADA, FUNDADA EN PARTE o INFUNDADA la demanda, con sus efectos].\n" +
                    "</plantilla_ejemplo>\n\n" +

                    "<estilos_documento>\n" +
                    "- Todos los datos de la cabecera, incluso hasta la ciudad y fecha deben de pintarse en negrita.\n" +
                    "- En la sección de contenido, todos los títulos deben de pintarse con negrita.\n" +
                    "<estilos_documento>\n\n" +

                    "Basado en esto, redacta la sentencia final.";

    @Override
    public ResponseGeneracionSentencia generarSentencia(InputGeneracionSentencia input, String sessionId) throws Exception {

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

        if (input.getXformato() != null && !input.getXformato().trim().isEmpty())
            configurations.setPromptDefault(configurations.getPromptDefault().replace("[Número_de_Expediente]", input.getXformato()));

        if (input.getXnomInstancia() != null && !input.getXnomInstancia().trim().isEmpty())
            configurations.setPromptDefault(configurations.getPromptDefault().replace("[Nombre_de_Juzgado]", input.getXnomInstancia()));

        if (input.getXdescMateria() != null && !input.getXdescMateria().trim().isEmpty())
            configurations.setPromptDefault(configurations.getPromptDefault().replace("[Materia_de_la_demanda]", input.getXdescMateria()));

        if (input.getXdescJuez() != null && !input.getXdescJuez().trim().isEmpty())
            configurations.setPromptDefault(configurations.getPromptDefault().replace("[Nombre_del_Juez]", input.getXdescJuez()));

        if (input.getXdescEspecialista() != null && !input.getXdescEspecialista().trim().isEmpty())
            configurations.setPromptDefault(configurations.getPromptDefault().replace("[Nombre_del_Especialista]", input.getXdescEspecialista()));

        if (input.getXdescDemandado() != null && !input.getXdescDemandado().trim().isEmpty())
            configurations.setPromptDefault(configurations.getPromptDefault().replace("[Nombre_completo_Demandado]", input.getXdescDemandado()));

        if (input.getXdescDemandante() != null && !input.getXdescDemandante().trim().isEmpty())
            configurations.setPromptDefault(configurations.getPromptDefault().replace("[Nombre_completo_Demandante]", input.getXdescDemandante()));

        DemandasSentencias sentencia = new DemandasSentencias();
        LocalDateTime fechaSend = LocalDateTime.now();

        sentencia.setUserId(responseLogin.getUser().getIdUser());
        sentencia.setNUnico(input.getNunico());
        sentencia.setModel(configurations.getModel());
        sentencia.setRoleSystem(configurations.getRoleSystem());
        BigDecimal temperature = configurations.getTemperature() != null
                ? configurations.getTemperature().setScale(1, RoundingMode.HALF_UP)
                : BigDecimal.ZERO.setScale(1, RoundingMode.HALF_UP);
        sentencia.setTemperature(temperature);
        sentencia.setFechaSend(fechaSend);
        sentencia.setConfigurations(configurations);
        sentencia.setAnio(input.getAnio());
        sentencia.setExpNro(input.getExpNro());
        sentencia.setTipoExpediente(input.getTipoExpediente());
        sentencia.setRutaCompleta(input.getRutaCompleta());
        sentencia.setXformato(input.getXformato());
        sentencia.setCclave(input.getCclave());
        sentencia.setXnomInstancia(input.getXnomInstancia());
        sentencia.setCubicacion(input.getCubicacion());
        sentencia.setCinstancia(input.getCinstancia());
        sentencia.setXdescEstado(input.getXdescEstado());
        sentencia.setCusuario(input.getCusuario());
        sentencia.setCmateria(input.getCmateria());
        sentencia.setCespecialidad(input.getCespecialidad());
        sentencia.setXdescUbicacion(input.getXdescUbicacion());
        sentencia.setXnombreArchivo(String.join("; ", input.getArchivos()));
        sentencia.setNincidente(input.getNincidente());
        sentencia.setXrutaArchivo(input.getXrutaArchivo());
        sentencia.setXdescMateria(input.getXdescMateria());
        sentencia.setFinicio(input.getFinicio());
        sentencia.setStatus(Constantes.GENERACION_SENTENCIA_INICIADA);
        sentencia.setXdescJuez(input.getXdescJuez());
        sentencia.setXdescEspecialista(input.getXdescEspecialista());
        sentencia.setXdescDemandado(input.getXdescDemandado());
        sentencia.setXdescDemandante(input.getXdescDemandante());

        sentencia = demandasSentenciasDAO.registrar(sentencia);

        byte[] pdfBytes;
        try {
            // Una demanda puede estar formada por 1..n archivos PDF. Se descargan todos desde
            // la misma ruta FTP (una sola conexión) en el orden recibido y se unen en un solo PDF.
            List<byte[]> pdfsDescargados = ftpService.descargarArchivos(input.getRutaCompleta(), input.getArchivos());
            pdfBytes = PdfMergeUtil.unir(pdfsDescargados);
        } catch (Exception ex) {
            logger.error("Error descargando archivo FTP: {}", ex.getMessage(), ex);
            sentencia.setStatus(Constantes.GENERACION_SENTENCIA_ERROR_FILE_NOT_FOUND);
            sentencia.setResponse("Error FTP: " + ex.getMessage());
            try {
                sentencia = demandasSentenciasDAO.modificar(sentencia);
            } catch (Exception modErr) {
                logger.error("Error actualizando DemandasSentencias tras fallo FTP: {}", modErr.getMessage());
            }
            publicarKafka(sentencia, responseLogin.getUser());
            double seconds = (System.nanoTime() - start) / 1_000_000_000.0;
            sentencia.setTimeSeconds(seconds);
            throw new ValidationServiceException("No se pudo recuperar el archivo de la demanda desde FTP: " + ex.getMessage());
        }

        String fullText;
        try {
            fullText = invocarGemini(pdfBytes, configurations);
        } catch (Exception ex) {
            logger.error("Error invocando Gemini: {}", ex.getMessage(), ex);
            sentencia.setStatus(Constantes.GENERACION_SENTENCIA_ERROR_GEMINY);
            sentencia.setResponse("Error Gemini: " + ex.getMessage());
            try {
                sentencia = demandasSentenciasDAO.modificar(sentencia);
            } catch (Exception modErr) {
                logger.error("Error actualizando DemandasSentencias tras fallo Gemini: {}", modErr.getMessage());
            }
            publicarKafka(sentencia, responseLogin.getUser());
            throw new ValidationServiceException("Error en la generación de la sentencia con Gemini: " + ex.getMessage());
        }

        LocalDateTime fechaResponse = LocalDateTime.now();
        double seconds = (System.nanoTime() - start) / 1_000_000_000.0;

        sentencia.setFechaResponse(fechaResponse);
        sentencia.setResponse(fullText);
        sentencia.setTimeSeconds(seconds);
        sentencia.setStatus(Constantes.GENERACION_SENTENCIA_EXITOSA);

        sentencia = demandasSentenciasDAO.modificar(sentencia);

        publicarKafka(sentencia, responseLogin.getUser());

        ResponseGeneracionSentencia response = new ResponseGeneracionSentencia();
        response.setId(sentencia.getId());
        response.setStatus(sentencia.getStatus());
        response.setModel(sentencia.getModel());
        response.setResponse(sentencia.getResponse());
        response.setTimeSeconds(sentencia.getTimeSeconds());
        response.setFechaSend(sentencia.getFechaSend());
        response.setFechaResponse(sentencia.getFechaResponse());

        return response;
    }

    @Override
    public ResponseGeneracionSentenciaDocx generarSentenciaDocx(InputGeneracionSentencia input, String sessionId) throws Exception {

        ResponseGeneracionSentencia sentencia = generarSentencia(input, sessionId);

        byte[] docxBytes = DocxGeneratorUtil.textToDocx(sentencia.getResponse());

        String expediente = input.getXformato() != null ? input.getXformato() : "";
        String nombreArchivo = String.format("%s_sentencia_demanda.docx", expediente);

        ResponseGeneracionSentenciaDocx response = new ResponseGeneracionSentenciaDocx();
        response.setDocumento(docxBytes);
        response.setNombreArchivo(nombreArchivo);

        return response;
    }

    @Override
    public Page<ResponseListadoDemandaSentencia> listarDemandasSentencias(InputListadoDemandasSentencias input,
                                                                          Pageable pageable,
                                                                          String sessionId) throws Exception {

        ResponseLogin responseLogin = validarSesion(sessionId);
        Long userId = responseLogin.getUser().getIdUser();

        InputListadoDemandasSentencias filtros = input != null ? input : new InputListadoDemandasSentencias();

        // Los filtros llegan como fecha (yyyy-MM-dd) pero fechaSend es datetime: se expande el rango
        // al inicio del día inicial y al fin del día final (ambos inclusive).
        LocalDateTime fechaDesde = filtros.getFechaInicial() != null
                ? filtros.getFechaInicial().atStartOfDay() : null;
        LocalDateTime fechaHasta = filtros.getFechaFinal() != null
                ? filtros.getFechaFinal().atTime(LocalTime.MAX) : null;

        Page<DemandasSentencias> pagina = demandasSentenciasDAO.listarPorFiltros(
                userId, fechaDesde, fechaHasta, filtros.getAnio(), filtros.getExpNro(), pageable);

        return pagina.map(ResponseListadoDemandaSentencia::fromEntity);
    }

    @Override
    public ResponseGeneracionSentenciaDocx descargarSentenciaDocx(InputDescargaSentencia input, String sessionId) throws Exception {

        ResponseLogin responseLogin = validarSesion(sessionId);
        Long userId = responseLogin.getUser().getIdUser();

        DemandasSentencias sentencia = demandasSentenciasDAO.listarPorId(input.getId());

        if (sentencia == null) {
            throw new ValidationServiceException("No se encontró la sentencia de demanda solicitada");
        }

        // La sentencia solo puede ser descargada por el usuario propietario del registro.
        if (sentencia.getUserId() == null || !sentencia.getUserId().equals(userId)) {
            throw new ValidationSessionServiceException("La sentencia de demanda no pertenece al usuario de la sesión");
        }

        byte[] docxBytes = DocxGeneratorUtil.textToDocx(sentencia.getResponse());

        String expediente = sentencia.getXformato() != null ? sentencia.getXformato() : "";
        String nombreArchivo = String.format("%s_sentencia_demanda.docx", expediente);

        ResponseGeneracionSentenciaDocx response = new ResponseGeneracionSentenciaDocx();
        response.setDocumento(docxBytes);
        response.setNombreArchivo(nombreArchivo);

        return response;
    }

    @Override
    public Page<ResponseListadoDemandaSentencia> listarUltimaVersionDemandasSentencias(InputListadoDemandasSentencias input,
                                                                                       Pageable pageable,
                                                                                       String sessionId) throws Exception {

        ResponseLogin responseLogin = validarSesion(sessionId);
        Long userId = responseLogin.getUser().getIdUser();

        InputListadoDemandasSentencias filtros = input != null ? input : new InputListadoDemandasSentencias();

        // Los filtros llegan como fecha (yyyy-MM-dd) pero fechaSend es datetime: se expande el rango
        // al inicio del día inicial y al fin del día final (ambos inclusive).
        LocalDateTime fechaDesde = filtros.getFechaInicial() != null
                ? filtros.getFechaInicial().atStartOfDay() : null;
        LocalDateTime fechaHasta = filtros.getFechaFinal() != null
                ? filtros.getFechaFinal().atTime(LocalTime.MAX) : null;

        Page<DemandasSentencias> pagina = demandasSentenciasDAO.listarUltimaVersionPorNunico(
                userId, fechaDesde, fechaHasta, filtros.getAnio(), filtros.getExpNro(), pageable);

        return pagina.map(ResponseListadoDemandaSentencia::fromEntity);
    }

    @Override
    public List<ResponseListadoDemandaSentencia> listarSentenciasPorNunico(Long nUnico, String sessionId) throws Exception {

        ResponseLogin responseLogin = validarSesion(sessionId);
        Long userId = responseLogin.getUser().getIdUser();

        List<DemandasSentencias> registros = demandasSentenciasDAO.listarPorNunico(userId, nUnico);

        return registros.stream()
                .map(ResponseListadoDemandaSentencia::fromEntity)
                .toList();
    }

    /**
     * Valida la sesión contra el servicio de seguridad y devuelve los datos del usuario autenticado.
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

    private void publicarKafka(DemandasSentencias sentencia, UserLogin user) {
        try {
            sentencia.setConfigurationsId(sentencia.getConfigurations() != null ? sentencia.getConfigurations().getId() : null);
            DemandasSentenciasToKafka payload = DemandasSentenciasToKafka.from(sentencia, user);
            kafkaTemplate.send(KAFKA_TOPIC, String.valueOf(sentencia.getId()), payload);
        } catch (Exception ex) {
            logger.warn("Error publicando en Kafka topic {}: {}", KAFKA_TOPIC, ex.getMessage());
        }
    }

    /**
     * Generación de la sentencia mediante RAG Orquestado (REST puro), sin Data Store.
     *
     * Flujo:
     *  Fase 1 - Gemini extrae los conceptos jurídicos clave de la demanda (SDK Vertex AI, REST).
     *  Fase 2 - Se generan embeddings de esos conceptos (Vertex AI Prediction REST).
     *  Fase 3 - Búsqueda vectorial en el índice de normativa (Vector Search findNeighbors REST).
     *  Fase 4 - Gemini redacta la sentencia con la normativa recuperada.
     *
     * Timeout un poco mayor que el de calificación (7 min RPC / 15 min total).
     */
    private String invocarGemini(byte[] pdfBytes, Configurations configurations) throws IOException {

        double pdfMb = pdfBytes.length / (1024.0 * 1024.0);
        logger.info("[Sentencia diagnóstico] PDF={} bytes (~{} MB) | inline base64 ~{} MB por envío x2 (Fase1+Fase4) ~{} MB",
                pdfBytes.length, String.format("%.2f", pdfMb),
                String.format("%.2f", pdfMb * 4.0 / 3.0), String.format("%.2f", pdfMb * 4.0 / 3.0 * 2));

        GoogleCredentials credentials = getCredentials();

        NetHttpTransport httpTransport = buildHttpTransport();

        credentials.refreshIfExpired();
        String accessToken = credentials.getAccessToken().getTokenValue();

        PredictionServiceSettings.Builder settingsBuilder = PredictionServiceSettings.newHttpJsonBuilder();
        settingsBuilder.setEndpoint(properties.getGcpEndpoint());
        settingsBuilder.setCredentialsProvider(FixedCredentialsProvider.create(credentials));

        // Timeout un poco mayor que la calificación (calificación: 5 min RPC / 10 min total).
        RetrySettings retry = settingsBuilder
                .generateContentSettings()
                .getRetrySettings()
                .toBuilder()
                .setInitialRpcTimeout(Duration.ofMinutes(7))
                .setMaxRpcTimeout(Duration.ofMinutes(7))
                .setTotalTimeout(Duration.ofMinutes(15))
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
            // FASE 4: GENERACIÓN FINAL (redacción de la sentencia, configurada desde BD)
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
                            "NORMATIVA LEGAL DE APOYO (referencial):%n%s%n%n" +
                            "TAREA:%nAnaliza el documento PDF adjunto, valora los hechos y medios probatorios y, " +
                            "apoyándote en la normativa proporcionada y la invocada en la demanda, redacta la sentencia.",
                    configurations.getPromptDefault(), contextoLeyes
            );

            String sentenciaFinal = ResponseHandler.getText(
                    modeloFinal.generateContent(ContentMaker.fromMultiModalData(documentPart, promptEnriquecido))
            );
            long tFase4Fin = System.nanoTime();

            logger.info("[Sentencia tiempos] GCS-upload={}s | Fase1(extracción {})={}s | Fase2(embedding)={}s | " +
                            "Fase3(vectorSearch)={}s | Fase4(redacción {})={}s | Total IA={}s",
                    seg(tGcsIni, tGcsFin), properties.getGcpExtractionModel(), seg(tFase1Ini, tFase1Fin),
                    seg(tFase1Fin, tFase2Fin), seg(tFase2Fin, tFase3Fin), configurations.getModel(),
                    seg(tFase3Fin, tFase4Fin), seg(tGcsIni, tFase4Fin));

            return sentenciaFinal;
        }
        } finally {
            try {
                gcsStorageService.borrar(gsUri);
            } catch (Exception ex) {
                logger.warn("[GCS] No se pudo borrar el objeto temporal {}: {}", gsUri, ex.getMessage());
            }
        }
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
    // Para la sentencia NO se inyectan los artículos estáticos de requisitos de calificación
    // (CPC 424/425/427/130): el contexto legal proviene solo de la recuperación dinámica.
    // ====================================================================
    private List<String> buscarVecinos(NetHttpTransport httpTransport, String accessToken, List<Double> vectorConsulta)
            throws IOException {
        String indexEndpointResource = String.format(
                "projects/%s/locations/%s/indexEndpoints/%s",
                properties.getGcpProjectId(), properties.getGcpVectorSearchLocation(), properties.getGcpIndexEndpointId());

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

        // Umbral de similitud (se afina empíricamente, 0.65 - 0.75 suele ser un buen inicio).
        double UMBRAL_MINIMO_SIMILITUD = 0.78;

        List<String> ids = new ArrayList<>();

        for (JsonNode grupo : resp.path("nearestNeighbors")) {
            for (JsonNode vecino : grupo.path("neighbors")) {
                String id = vecino.path("datapoint").path("datapointId").asText("");
                double distance = vecino.path("distance").asDouble(0.0);

                if (!id.isEmpty()) {
                    if (distance >= UMBRAL_MINIMO_SIMILITUD) {
                        if (!ids.contains(id)) {
                            ids.add(id);
                            logger.info("Aceptado - ID: {} | Score: {}", id, String.format("%.4f%n", distance));
                        } else {
                            logger.info("Aceptado Pero - ID Ya en list: {} | Score: {}", id, String.format("%.4f%n", distance));
                        }
                    } else {
                        logger.info("Descartado (Ruido) - ID: {} | Score: {}", id, String.format("%.4f%n", distance));
                    }
                }
            }
        }
        return ids;
    }

    /**
     * Resuelve el dominio contra el que debe invocarse findNeighbors (público dedicado si existe).
     * Estrategia híbrida: dominio configurado en properties, o resolución vía metadata cacheada.
     */
    private String resolverDominioFindNeighbors(NetHttpTransport httpTransport, String accessToken,
                                                String indexEndpointResource) throws IOException {
        String configurado = properties.getGcpVectorSearchPublicDomain();
        if (configurado != null && !configurado.isBlank()) {
            return normalizarDominio(configurado);
        }

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

    private String normalizarDominio(String dominio) {
        String d = dominio.trim();
        return (d.startsWith("http://") || d.startsWith("https://")) ? d : "https://" + d;
    }

    // ====================================================================
    // Utilidades REST (HTTP crudo con NetHttpTransport + proxy + Bearer token)
    // ====================================================================
    private NetHttpTransport buildHttpTransport() {
        return buildHttpTransportProxyGoogle();
    }

    /** Proxy ANTERIOR (general SIJ, {@code sij.proxy.config}). */
    private NetHttpTransport buildHttpTransportProxyAntiguo() {
        if (Boolean.TRUE.equals(properties.getProxyEnabled())) {
            Proxy proxy = new Proxy(Proxy.Type.HTTP,
                    new InetSocketAddress(properties.getProxyURL(), properties.getProxyPort()));
            return new NetHttpTransport.Builder().setProxy(proxy).build();
        }
        return new NetHttpTransport.Builder().build();
    }

    /** Proxy NUEVO (PAC ADcsjan, {@code sij.proxy.google}) para Google Cloud / Vertex / Gemini. */
    private NetHttpTransport buildHttpTransportProxyGoogle() {
        if (Boolean.TRUE.equals(properties.getProxyGoogleEnabled())) {
            Proxy proxy = new Proxy(Proxy.Type.HTTP,
                    new InetSocketAddress(properties.getProxyGoogleHost(), properties.getProxyGooglePort()));
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
