package pj.gob.pe.consultaia.service.business.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.api.client.http.ByteArrayContent;
import com.google.api.client.http.GenericUrl;
import com.google.api.client.http.HttpHeaders;
import com.google.api.client.http.HttpRequest;
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
import pj.gob.pe.consultaia.configuration.RetryProperties;
import pj.gob.pe.consultaia.dao.mysql.ConfigurationDAO;
import pj.gob.pe.consultaia.dao.mysql.DemandasSentenciasDAO;
import pj.gob.pe.consultaia.exception.HttpEstadoException;
import pj.gob.pe.consultaia.exception.ValidationServiceException;
import pj.gob.pe.consultaia.exception.ValidationSessionServiceException;
import pj.gob.pe.consultaia.model.beans.DemandasSentenciasToKafka;
import pj.gob.pe.consultaia.model.entities.Configurations;
import pj.gob.pe.consultaia.model.entities.DemandasSentencias;
import pj.gob.pe.consultaia.service.business.ChunkStoreService;
import pj.gob.pe.consultaia.service.business.SentenciaService;
import pj.gob.pe.consultaia.service.externals.FtpService;
import pj.gob.pe.consultaia.service.externals.GcsStorageService;
import pj.gob.pe.consultaia.service.externals.JudicialService;
import pj.gob.pe.consultaia.service.externals.SecurityService;
import pj.gob.pe.consultaia.utils.beans.responses.DataSedeDTO;
import pj.gob.pe.consultaia.utils.Constantes;
import pj.gob.pe.consultaia.utils.DocxGeneratorUtil;
import pj.gob.pe.consultaia.utils.PdfMergeUtil;
import pj.gob.pe.consultaia.utils.PdfOptimizerUtil;
import pj.gob.pe.consultaia.utils.ReintentoUtil;
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
 *  - El prompt solicita redactar la SENTENCIA (no la calificación/auto admisorio), con la estructura
 *    completa de las sentencias de la Corte Superior de Justicia de Áncash: cabecera, VISTOS, parte
 *    expositiva, parte considerativa con considerandos numerados y un bloque por punto controvertido,
 *    y parte resolutiva con el fallo en numerales romanos.
 *  - NO se inyectan los artículos estáticos de requisitos de calificación (CPC 424/425/427/130);
 *    el contexto legal proviene únicamente de la búsqueda vectorial dinámica.
 *  - Timeout bastante mayor que la calificación (15 min RPC / 25 min total), porque la sentencia se
 *    redacta completa y puede llegar a 65535 tokens de salida.
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
    private final RetryProperties retryProperties;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final ChunkStoreService chunkStoreService;
    private final GcsStorageService gcsStorageService;
    private final JudicialService judicialService;

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
            "Lee el expediente adjunto (demanda, anexos, contestación, actas de audiencia, informes técnicos y dictamen fiscal). " +
                    "Tu objetivo es generar términos de búsqueda para un sistema de RAG vectorial de normativa peruana, " +
                    "orientado a resolver el FONDO del asunto y a redactar la SENTENCIA (no a calificar la admisibilidad). " +
                    "Extrae: 1) La pretensión principal, las pretensiones accesorias y el tipo de proceso. " +
                    "2) Las instituciones jurídicas sustantivas involucradas (causales, derechos, obligaciones, presupuestos). " +
                    "3) Las instituciones procesales que sustentan la sentencia: tutela jurisdiccional efectiva, finalidad del proceso, " +
                    "fijación de puntos controvertidos, carga de la prueba y valoración conjunta de los medios probatorios. " +
                    "4) Si el proceso involucra niños, niñas o adolescentes: interés superior del niño, derecho a ser oído, tenencia, " +
                    "régimen de visitas, alimentos y quantum alimentario, según corresponda. " +
                    "5) Los hechos relevantes y los medios probatorios ofrecidos y actuados que sustentan o contradicen la pretensión. " +
                    "Responde ÚNICAMENTE con una lista de palabras clave y artículos legales relevantes separados por comas, sin explicaciones ni viñetas.";

    private static final String ROLE_SYSTEM = "Eres un Asistente Judicial Virtual experto en derecho procesal y sustantivo peruano, " +
            "con experiencia en la redacción de sentencias de los juzgados de la Corte Superior de Justicia de Áncash (Poder Judicial del Perú). " +
            "Tu función es redactar PROYECTOS DE SENTENCIA que resuelvan el fondo de la controversia a partir del expediente adjunto " +
            "(demanda, anexos, contestación, actas de audiencia, informes técnicos del Equipo Multidisciplinario y dictamen fiscal), " +
            "valorando individualmente los hechos y los medios probatorios frente a la normativa vigente.\n" +
            "REGLA ESTRICTA: Tu respuesta debe ser ÚNICAMENTE el borrador de la sentencia judicial, sin preámbulos, comentarios ni " +
            "explicaciones sobre cómo la elaboraste.\n" +
            "DEBES respetar rigurosamente la estructura formal de las sentencias judiciales peruanas: cabecera, SENTENCIA, " +
            "RESOLUCIÓN NÚMERO, lugar y fecha, VISTOS, PARTE EXPOSITIVA, PARTE CONSIDERATIVA con considerandos numerados correlativos " +
            "y subtítulos, y PARTE RESOLUTIVA con el fallo en numerales romanos.\n" +
            "La cabecera no debe ser modificada: debes enviar tal cual los datos que se presentan en la <plantilla_ejemplo>, que incluye " +
            "los datos juzgado - sede, EXPEDIENTE, MATERIA, JUEZ, ESPECIALISTA, DEMANDADO y DEMANDANTE.\n" +
            "La sentencia debe ser EXHAUSTIVA y AUTOSUFICIENTE: nunca resumas, nunca uses expresiones como \"etc.\", \"entre otros\" o " +
            "\"se omite por brevedad\", y nunca dejes una sección incompleta.";

    private static final String PROMPT_DEFAULT =
            "Redacta el PROYECTO DE SENTENCIA del expediente adjunto, apoyándote en la <normativa_recuperada> y siguiendo ESTRICTAMENTE\n" +
                    "las reglas, la estructura y el nivel de detalle que se detallan a continuación. El resultado debe ser indistinguible, en\n" +
                    "forma y extensión, de una sentencia real de la Corte Superior de Justicia de Áncash.\n\n" +

                    "<instrucciones>\n" +
                    "1. Los datos para la CABECERA (Juzgado, Expediente, Materia, Juez, Especialista, Demandado, Demandante) serán remitidos en\n" +
                    "   la <plantilla_ejemplo>. No modifiques estos datos y NUNCA omitas los campos de la cabecera. Las líneas ABOGADO, MENOR y\n" +
                    "   MINISTERIO PÚBLICO no se remiten: inclúyelas SOLO si constan en el expediente adjunto y, si no constan, suprime la línea\n" +
                    "   completa (no escribas marcas de dato faltante en la cabecera).\n" +
                    "2. Lee ÍNTEGRAMENTE el expediente adjunto: demanda y subsanaciones, anexos, auto admisorio, cédulas de notificación,\n" +
                    "   contestación y sus anexos, actas de audiencia (única, de continuación, de informes orales, de toma de opinión o cámara\n" +
                    "   Gesell), informes psicológicos e informes sociales del Equipo Multidisciplinario, dictamen fiscal y demás resoluciones.\n" +
                    "3. Identifica con precisión el PETITORIO (pretensión principal y pretensiones accesorias), la causa de pedir, los fundamentos\n" +
                    "   de la defensa y la actividad procesal cronológica, siempre con su referencia de folios.\n" +
                    "4. Reproduce los PUNTOS CONTROVERTIDOS exactamente como fueron fijados en la audiencia única, citando el acta y sus folios.\n" +
                    "   Si el expediente no los contiene, formúlalos a partir del petitorio y la contestación y déjalo expresamente indicado.\n" +
                    "5. Desarrolla la PARTE CONSIDERATIVA con considerandos NUMERADOS CORRELATIVOS (1, 2, 3, ...) agrupados bajo subtítulos:\n" +
                    "   primero el marco jurídico aplicable y luego un bloque de análisis independiente por CADA punto controvertido.\n" +
                    "6. Valora los MEDIOS PROBATORIOS uno por uno, identificándolos por su denominación, número y folios (por ejemplo:\n" +
                    "   \"Informe Psicológico N° 009-2026-EM-CSJAN-PJ-PS, obrante de folios 88/92\"), precisando qué acredita cada uno, su\n" +
                    "   pertinencia respecto del punto controvertido y por qué se le otorga o no valor probatorio. Cuando el expediente contenga\n" +
                    "   informes técnicos, transcribe entre comillas sus conclusiones pertinentes antes de analizarlas.\n" +
                    "7. Aplica el DERECHO al caso concreto: fundamenta cada conclusión con los artículos de la <normativa_recuperada>, con las\n" +
                    "   normas invocadas en la demanda y en la contestación, y con la doctrina, jurisprudencia, casaciones, plenos casatorios o\n" +
                    "   sentencias del Tribunal Constitucional que consten en el expediente o en la normativa recuperada.\n" +
                    "8. Pondera expresamente el dictamen fiscal cuando exista, señalando si se concuerda total o parcialmente con él.\n" +
                    "9. Cierra cada bloque de análisis con una conclusión expresa del tipo \"Quedando dilucidado el [primer/segundo/...] punto\n" +
                    "   controvertido\", y antes del fallo consolida la conclusión general del caso.\n" +
                    "10. Redacta una DECISIÓN (FALLO) congruente con el petitorio y con la fundamentación: declara FUNDADA, FUNDADA EN PARTE o\n" +
                    "    INFUNDADA la demanda (y cada pretensión accesoria por separado), con mandatos concretos y ejecutables.\n" +
                    "11. EXTENSIÓN: la sentencia debe ser exhaustiva. Desarrolla cada considerando en párrafos completos de redacción forense; no\n" +
                    "    produzcas esquemas, listas telegráficas ni resúmenes. Una sentencia de familia típica supera los cincuenta considerandos\n" +
                    "    cuando el expediente lo permite.\n" +
                    "</instrucciones>\n\n" +

                    "<estructura_obligatoria>\n" +
                    "Emite SIEMPRE, y en este orden, las siguientes secciones:\n" +
                    "1. CABECERA institucional: \"CORTE SUPERIOR DE JUSTICIA DE ÁNCASH\", el nombre del juzgado, la línea separadora y el bloque\n" +
                    "   de datos del expediente (EXPEDIENTE, MATERIA, JUEZ, ESPECIALISTA, [ABOGADO], [MENOR], [MINISTERIO PÚBLICO], DEMANDADO,\n" +
                    "   DEMANDANTE).\n" +
                    "2. \"SENTENCIA\", \"RESOLUCIÓN NÚMERO <número en letras>\", y a continuación la ciudad y la fecha en letras.\n" +
                    "3. VISTOS: párrafo de encabezamiento que identifica el proceso, las partes, la materia, el o los beneficiarios con su edad\n" +
                    "   entre paréntesis y, de existir, la conformidad total o parcial con el dictamen fiscal y sus folios.\n" +
                    "4. PARTE EXPOSITIVA, con estos subtítulos:\n" +
                    "   4.1. \"De la demanda\": fecha del escrito, folios, pretensión y fundamentos de hecho enumerados con literales a), b), c)...\n" +
                    "        Cada literal debe reproducir con fidelidad y en tercera persona un fundamento de la demanda.\n" +
                    "   4.2. \"De la contestación de la demanda\": auto admisorio y sus folios, notificación y su constancia, escrito de\n" +
                    "        contestación con fecha y folios, lo que solicita el demandado y sus fundamentos enumerados con literales. Si el\n" +
                    "        demandado fue declarado rebelde o no contestó, consígnalo con la resolución que así lo declara.\n" +
                    "   4.3. \"Actividad judicial\": relación cronológica en viñetas de las resoluciones, audiencias, saneamiento procesal, fijación\n" +
                    "        de puntos controvertidos, admisión y actuación de medios probatorios, informes psicológicos y sociales, tomas de\n" +
                    "        opinión, dictamen fiscal y la resolución que deja los autos en despacho para sentenciar.\n" +
                    "5. PARTE CONSIDERATIVA, con considerandos numerados correlativos agrupados en subtítulos:\n" +
                    "   5.1. Marco jurídico general y específico, conforme a <marco_juridico_por_materia>.\n" +
                    "   5.2. \"Sobre los puntos controvertidos\": invocación del artículo 122° del Código Procesal Civil y enumeración de los puntos\n" +
                    "        fijados, con la referencia al acta de audiencia y sus folios.\n" +
                    "   5.3. Un subtítulo por cada punto controvertido (\"Sobre el primer punto controvertido\", \"Sobre el segundo punto\n" +
                    "        controvertido\", ...), con: enunciado literal del punto entre comillas, marco normativo aplicable, valoración\n" +
                    "        individual de cada medio probatorio con sus folios, doctrina y jurisprudencia pertinente, y conclusión del punto.\n" +
                    "   5.4. Considerandos finales de cierre: proporcionalidad y razonabilidad de la decisión y, cuando corresponda, determinación\n" +
                    "        del quantum alimentario o de la modalidad y progresividad del régimen que se fija.\n" +
                    "6. PARTE RESOLUTIVA: fórmula de estilo \"Estando a las consideraciones precedentes y con lo dispuesto por el artículo 138° de\n" +
                    "   la Constitución Política del Estado, el/la Señor(a) Juez del [Nombre_de_Juzgado], impartiendo Justicia a nombre de la\n" +
                    "   Nación, FALLA:\" seguida de numerales romanos (I., II., III., ...) que contengan:\n" +
                    "   - El fallo sobre la pretensión principal y sobre cada pretensión accesoria.\n" +
                    "   - Los mandatos concretos derivados del fallo (monto de la pensión en cifras y en letras, fecha desde la que rige, forma y\n" +
                    "     cuenta de pago, etapas y horarios del régimen de visitas, seguimiento del Equipo Multidisciplinario, etc.).\n" +
                    "   - Los apercibimientos aplicables (por ejemplo, inscripción en el Registro de Deudores Alimentarios Morosos conforme a la\n" +
                    "     Primera Disposición Final de la Ley N° 28970, o remisión de copias a la ODANC).\n" +
                    "   - Las exhortaciones a las partes y las órdenes a los órganos de apoyo.\n" +
                    "   - El pronunciamiento sobre costas y costos.\n" +
                    "   - La fórmula final: \"Consentida y/o ejecutoriada que sea la presente sentencia, CÚMPLASE lo ordenado como corresponde.\n" +
                    "     NOTIFÍQUESE.-\".\n" +
                    "7. \"NOTAS\": al final, y solo si citaste doctrina o fuentes bibliográficas, la relación numerada de las referencias.\n" +
                    "</estructura_obligatoria>\n\n" +

                    "<marco_juridico_por_materia>\n" +
                    "Desarrolla como subtítulos de la parte considerativa ÚNICAMENTE los bloques que correspondan a la materia del proceso:\n" +
                    "- SIEMPRE: \"Del derecho a la tutela jurisdiccional efectiva\" (artículo I del Título Preliminar del Código Procesal Civil);\n" +
                    "  \"Finalidad del proceso\" (artículo 139° incisos 3) y 6) de la Constitución y artículo III del Título Preliminar del Código\n" +
                    "  Procesal Civil); \"De los medios probatorios y de la carga de la prueba\" (artículos 188°, 196° y 197° del Código Procesal\n" +
                    "  Civil).\n" +
                    "- PROCESOS CON NIÑOS, NIÑAS O ADOLESCENTES: \"Sobre el derecho convencional\" y \"El interés superior del niño y adolescente\"\n" +
                    "  (artículo 3 de la Convención sobre los Derechos del Niño, artículo IX del Título Preliminar del Código de los Niños y\n" +
                    "  Adolescentes, y Ley N° 30466 con su reglamento); \"Sobre la opinión del menor\" (artículo 12 de la Convención sobre los\n" +
                    "  Derechos del Niño y artículo 85° del Código de los Niños y Adolescentes); y la valoración flexible de la prueba en los\n" +
                    "  procesos de familia establecida por el Cuarto Pleno Casatorio Civil.\n" +
                    "- ALIMENTOS: \"Sobre el derecho a los alimentos\" (artículo 25° inciso 1) de la Declaración Universal de Derechos Humanos,\n" +
                    "  artículo 27° de la Convención sobre los Derechos del Niño, artículo 6° de la Constitución, artículo 92° del Código de los\n" +
                    "  Niños y Adolescentes y artículo 472° del Código Civil); \"Obligación alimentaria\" (artículos 235°, 423° inciso 1) y 474° del\n" +
                    "  Código Civil); y \"Presupuestos del quantum alimentario\" (artículo 481° del Código Civil: estado de necesidad del alimentista\n" +
                    "  y capacidad económica del obligado, incluidas sus demás cargas familiares).\n" +
                    "- TENENCIA: \"Sobre la tenencia en el Código de los Niños y Adolescentes\" (artículos 81°, 82°, 83°, 84° y 86° del Código de los\n" +
                    "  Niños y Adolescentes, modificados por la Ley N° 31590), precisando la preferencia legal por la tenencia compartida y el\n" +
                    "  carácter excepcional de la tenencia exclusiva.\n" +
                    "- RÉGIMEN DE VISITAS: \"Régimen de visitas\" (artículos 88° y 89° del Código de los Niños y Adolescentes, artículo 422° del\n" +
                    "  Código Civil y artículo 9° numeral 3 de la Convención sobre los Derechos del Niño), precisando que es principalmente un\n" +
                    "  derecho del niño y que, ante distanciamiento prolongado o conflictividad coparental, debe fijarse de forma progresiva.\n" +
                    "- VIOLENCIA FAMILIAR CONCURRENTE: Ley N° 30364 y las medidas de protección vigentes que consten en el expediente.\n" +
                    "- OTRAS MATERIAS (civil, laboral, comercial, contencioso administrativo, penal, constitucional): sustituye los bloques\n" +
                    "  especializados anteriores por el marco normativo sustantivo y procesal que corresponda a esa materia, manteniendo el mismo\n" +
                    "  nivel de profundidad, la misma estructura de secciones y el mismo tratamiento individualizado de la prueba.\n" +
                    "No cites normas, casaciones ni doctrina que no figuren en la <normativa_recuperada>, en el expediente adjunto o en esta lista.\n" +
                    "</marco_juridico_por_materia>\n\n" +

                    "<reglas_redaccion>\n" +
                    "- Escribe en español jurídico peruano, en tercera persona y registro formal forense, evitando el lenguaje coloquial.\n" +
                    "- El número de resolución, el día, el mes y el año se escriben en letras (por ejemplo: \"RESOLUCIÓN NÚMERO SIETE\",\n" +
                    "  \"Huaraz, veintiséis de junio\", \"Del año dos mil veinticinco. -\").\n" +
                    "- Los montos dinerarios se expresan en letras y en cifras (por ejemplo: \"CUATROCIENTOS CINCUENTA CON 00/100 SOLES\n" +
                    "  (S/ 450.00)\").\n" +
                    "- Los nombres de las partes se escriben en MAYÚSCULAS en la parte resolutiva y en formato normal en el resto del documento.\n" +
                    "- Ancla cada afirmación fáctica a su fuente en el expediente mediante la referencia de folios (\"obrante de folios 20/24\").\n" +
                    "- Las citas normativas, doctrinarias y jurisprudenciales van entre comillas y con indicación precisa de su origen.\n" +
                    "- Cita los artículos aplicables apoyándote en la <normativa_recuperada> y en las normas invocadas en el expediente; no cites\n" +
                    "  normas que no figuren en esos contextos ni en <marco_juridico_por_materia>.\n" +
                    "- Resuelve el FONDO del asunto. NO realices control de admisibilidad ni procedibilidad de la demanda; asume que la demanda ya\n" +
                    "  fue admitida y corresponde sentenciar.\n" +
                    "- Prohibido inventar datos, fechas, folios, números de informe, montos, enlaces o nombres. Si un dato necesario no consta en\n" +
                    "  el expediente, escribe [COMPLETAR: dato] en su lugar, sin interrumpir la redacción de la sentencia.\n" +
                    "- No incluyas datos operativos que no consten en el expediente (direcciones, teléfonos o números de contacto de los órganos\n" +
                    "  de apoyo); si necesitas referirlos, usa [COMPLETAR: dato].\n" +
                    "- Tu respuesta es ÚNICAMENTE el texto de la sentencia, sin comentarios ni explicaciones.\n" +
                    "</reglas_redaccion>\n\n" +

                    "<plantilla_ejemplo>\n" +
                    "**CORTE SUPERIOR DE JUSTICIA DE ÁNCASH**\n" +
                    "**[Nombre_de_Juzgado]**\n" +
                    "**…………………………………………………………..………………………………………….....…………………**\n\n" +
                    "**EXPEDIENTE          : [Número_de_Expediente]**\n" +
                    "**MATERIA             : [Materia_de_la_demanda]**\n" +
                    "**JUEZ                : [Nombre_del_Juez]**\n" +
                    "**ESPECIALISTA        : [Nombre_del_Especialista]**\n" +
                    "**ABOGADO             : (incluir esta línea solo si consta en el expediente)**\n" +
                    "**MENOR               : (incluir esta línea solo si consta en el expediente)**\n" +
                    "**MINISTERIO PÚBLICO  : (incluir esta línea solo si consta en el expediente)**\n" +
                    "**DEMANDADO           : [Nombre_completo_Demandado]**\n" +
                    "**DEMANDANTE          : [Nombre_completo_Demandante]**\n\n" +
                    "# SENTENCIA\n" +
                    "**RESOLUCIÓN NÚMERO (número en letras)**\n" +
                    "**(Ciudad), (día y mes en letras)**\n" +
                    "**Del año (año en letras). -**\n\n" +
                    "**VISTOS;** con el expediente seguido por (demandante), sobre (materia), a favor de (beneficiario y su edad), acción que\n" +
                    "dirige contra (demandado); de conformidad (total o parcial) con lo expuesto en el dictamen fiscal de folios (...).\n\n" +
                    "## PARTE EXPOSITIVA:\n" +
                    "### De la demanda\n" +
                    "Mediante escrito de fecha (...), obrante de folios (...), (demandante) interpone demanda de (materia) contra (demandado),\n" +
                    "sustentándola en los siguientes fundamentos:\n" +
                    "a. (fundamento)\n" +
                    "b. (fundamento)\n" +
                    "### De la contestación de la demanda\n" +
                    "Mediante resolución (...) de fecha (...), obrante de folios (...), se admitió a trámite la demanda; notificado el demandado\n" +
                    "con fecha (...), absolvió el traslado mediante escrito de fecha (...), obrante de folios (...), solicitando que se declare\n" +
                    "(...); fundamenta su posición indicando que:\n" +
                    "a. (fundamento)\n" +
                    "b. (fundamento)\n" +
                    "### Actividad judicial\n" +
                    "- (resolución o actuación procesal, con fecha y folios)\n" +
                    "- (resolución o actuación procesal, con fecha y folios)\n\n" +
                    "## PARTE CONSIDERATIVA:\n" +
                    "### Del derecho a la tutela jurisdiccional efectiva\n" +
                    "1. (desarrollo)\n" +
                    "### Finalidad del proceso\n" +
                    "2. (desarrollo)\n" +
                    "### (demás subtítulos del marco jurídico que correspondan a la materia)\n" +
                    "3. (desarrollo)\n" +
                    "### Sobre los puntos controvertidos\n" +
                    "N. Estando a lo dispuesto por el artículo 122° del Código Procesal Civil, se procede a dilucidar los puntos controvertidos\n" +
                    "fijados en la audiencia única obrante de folios (...), siendo los siguientes: I) (...). II) (...). III) (...).\n" +
                    "### Sobre el primer punto controvertido\n" +
                    "N+1. Respecto al primer punto controvertido, consistente en: \"(...)\"; (marco normativo, valoración individual de cada medio\n" +
                    "probatorio con sus folios, doctrina y jurisprudencia).\n" +
                    "N+2. (continuación del análisis)\n" +
                    "N+3. En consecuencia, (...). Quedando dilucidado el primer punto controvertido.\n" +
                    "### Sobre el segundo punto controvertido\n" +
                    "(mismo tratamiento, y así sucesivamente con todos los puntos controvertidos)\n\n" +
                    "## PARTE RESOLUTIVA:\n" +
                    "Estando a las consideraciones precedentes y con lo dispuesto por el artículo 138° de la Constitución Política del Estado,\n" +
                    "el/la Señor(a) Juez del [Nombre_de_Juzgado], impartiendo Justicia a nombre de la Nación, **FALLA:**\n" +
                    "**I. DECLARANDO (FUNDADA / FUNDADA EN PARTE / INFUNDADA)** la demanda de (materia) interpuesta por (DEMANDANTE) contra\n" +
                    "(DEMANDADO) (...).\n" +
                    "**II. (mandato concreto derivado del fallo).**\n" +
                    "**III. (apercibimientos, órdenes a los órganos de apoyo y exhortaciones que correspondan).**\n" +
                    "**IV. (pronunciamiento sobre costas y costos).**\n" +
                    "**V. Consentida y/o ejecutoriada que sea la presente sentencia, CÚMPLASE lo ordenado como corresponde. NOTIFÍQUESE. -**\n\n" +
                    "## NOTAS\n" +
                    "1. (referencia doctrinaria o bibliográfica citada, solo si corresponde)\n" +
                    "</plantilla_ejemplo>\n\n" +

                    "<estilos_documento>\n" +
                    "- El texto se convierte automáticamente a DOCX con un marcado ligero. Usa exclusivamente: \"# \" al inicio de línea para el\n" +
                    "  título SENTENCIA, \"## \" para los títulos de parte (PARTE EXPOSITIVA, PARTE CONSIDERATIVA, PARTE RESOLUTIVA, NOTAS),\n" +
                    "  \"### \" para los subtítulos internos, \"**texto**\" para negrita dentro de un párrafo y \"- \" para viñetas.\n" +
                    "- Todos los datos de la cabecera, incluso hasta la ciudad y fecha, deben de pintarse en negrita.\n" +
                    "- En la sección de contenido, todos los títulos y subtítulos deben de pintarse con negrita.\n" +
                    "- Los numerales romanos de la parte resolutiva deben ir en negrita.\n" +
                    "- No uses tablas, ni el carácter \"|\", ni guiones bajos, ni asteriscos sueltos: el conversor los interpreta como formato y\n" +
                    "  deformaría el documento. Para resaltar usa siempre pares de dos asteriscos.\n" +
                    "- Cada considerando debe iniciar en una línea nueva con su número seguido de punto.\n" +
                    "</estilos_documento>\n\n" +

                    "Basado en todo lo anterior, redacta ahora la sentencia final completa.";

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
        //sentencia.setRutaCompleta(input.getRutaCompleta());
        sentencia.setRutaCompleta("");
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

        // Sede de la instancia, para los reportes de métricas por sede (best-effort: puede quedar null).
        DataSedeDTO sede = judicialService.GetSedePorInstancia(sessionId, input.getCinstancia());
        if (sede != null) {
            sentencia.setCodSede(sede.getCodigoSede());
            sentencia.setSede(sede.getSede() != null ? sede.getSede().trim() : null);
        }

        sentencia = demandasSentenciasDAO.registrar(sentencia);

        byte[] pdfBytes;
        try {
            // Una demanda puede estar formada por 1..n archivos PDF. Se descargan todos desde
            // la misma ruta FTP (una sola conexión) en el orden recibido y se unen en un solo PDF.
            List<byte[]> pdfsDescargados = ftpService.descargarArchivosRutaAll(input.getArchivos());
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

        // Timeout bastante mayor que la calificación (calificación: 5 min RPC / 10 min total). La sentencia
        // se redacta completa (hasta 65535 tokens de salida), por lo que la Fase 4 puede tardar varios minutos.
        RetrySettings retry = settingsBuilder
                .generateContentSettings()
                .getRetrySettings()
                .toBuilder()
                .setInitialRpcTimeout(Duration.ofMinutes(15))
                .setMaxRpcTimeout(Duration.ofMinutes(15))
                .setTotalTimeout(Duration.ofMinutes(25))
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

        // Gemini rechaza los PDF de más de 50 MB o de más de 1000 páginas. Un expediente es la unión
        // de 1..n documentos digitalizados, así que antes de subirlo se recomprimen sus imágenes y,
        // si aun así no cabe, se parte en varios archivos que viajan juntos en la misma petición.
        PdfOptimizerUtil.ResultadoOptimizacion optimizacion = PdfOptimizerUtil.prepararParaGemini(
                pdfBytes,
                properties.getGcpPdfLimiteBytes(),
                properties.getGcpPdfDpiObjetivo(),
                properties.getGcpPdfCalidadJpeg(),
                properties.getGcpPdfMaxPaginasPorParte(),
                Boolean.TRUE.equals(properties.getGcpPdfComprimirSiempre()));

        List<String> gsUris = new ArrayList<>(optimizacion.partes().size());
        String notaMultiparte = notaMultiparte(optimizacion.partes().size());

        long tGcsIni = System.nanoTime();
        try {
        for (byte[] parte : optimizacion.partes()) {
            gsUris.add(gcsStorageService.subir(parte, "application/pdf"));
        }
        long tGcsFin = System.nanoTime();

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

            List<Object> partesDocumento = new ArrayList<>(gsUris.size());
            for (String uri : gsUris) {
                partesDocumento.add(PartMaker.fromMimeTypeAndData("application/pdf", uri));
            }

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
                    ReintentoUtil.ejecutar(retryProperties, "Sentencia Fase 1 (extracción de conceptos)", () ->
                            modeloExtraccion.generateContent(
                                    ContentMaker.fromMultiModalData(
                                            contenidoMultimodal(partesDocumento, notaMultiparte + PROMPT_EXTRACCION_CONCEPTOS))))
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
            // La sentencia se redacta completa (todas las secciones de la <estructura_obligatoria>), por lo que
            // necesita un presupuesto de salida amplio: con 8192 tokens el texto se cortaba a media parte
            // considerativa. Las sentencias reales del juzgado equivalen a ~10 000-18 000 tokens.
            int maxOutputTokens = configurations.getMaxOutputTokens() != null
                    ? configurations.getMaxOutputTokens()
                    : 65535;

            GenerationConfig generationConfig = GenerationConfig.newBuilder()
                    .setTemperature(temperatureValue)
                    .setMaxOutputTokens(maxOutputTokens)
                    .build();

            GenerativeModel modeloFinal = new GenerativeModel(configurations.getModel(), vertexAI)
                    .withSystemInstruction(ContentMaker.fromString(configurations.getRoleSystem()))
                    .withGenerationConfig(generationConfig);

            // El bloque de leyes se envuelve en <normativa_recuperada> porque el PROMPT_DEFAULT referencia esa
            // etiqueta de forma explícita en sus instrucciones, reglas de redacción y marco jurídico.
            String promptEnriquecido = notaMultiparte + String.format(
                    "INSTRUCCIONES DEL JUZGADO:%n%s%n%n" +
                            "<normativa_recuperada>%n%s%n</normativa_recuperada>%n%n" +
                            "TAREA:%nAnaliza el expediente PDF adjunto en su integridad, valora individualmente los hechos y los " +
                            "medios probatorios y, apoyándote en la <normativa_recuperada> y en la normativa invocada en el " +
                            "expediente, redacta la sentencia COMPLETA respetando todas las secciones de la " +
                            "<estructura_obligatoria>.",
                    configurations.getPromptDefault(), contextoLeyes
            );

            String sentenciaFinal = ResponseHandler.getText(
                    ReintentoUtil.ejecutar(retryProperties, "Sentencia Fase 4 (redacción de la sentencia)", () ->
                            modeloFinal.generateContent(ContentMaker.fromMultiModalData(
                                    contenidoMultimodal(partesDocumento, promptEnriquecido))))
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
            for (String uri : gsUris) {
                try {
                    gcsStorageService.borrar(uri);
                } catch (Exception ex) {
                    logger.warn("[GCS] No se pudo borrar el objeto temporal {}: {}", uri, ex.getMessage());
                }
            }
        }
    }

    /**
     * Arma el arreglo (1..n documentos + prompt) que espera {@code ContentMaker.fromMultiModalData}.
     * El expediente puede haberse partido en varios PDF por el límite de 50 MB de Gemini; todas las
     * partes viajan en la MISMA petición para que el modelo lo siga viendo como un solo documento.
     */
    private Object[] contenidoMultimodal(List<Object> partesDocumento, String prompt) {
        Object[] datos = new Object[partesDocumento.size() + 1];
        for (int i = 0; i < partesDocumento.size(); i++) {
            datos[i] = partesDocumento.get(i);
        }
        datos[partesDocumento.size()] = prompt;
        return datos;
    }

    /** Aviso que se antepone al prompt cuando el expediente tuvo que enviarse partido. */
    private String notaMultiparte(int cantidadPartes) {
        if (cantidadPartes <= 1) {
            return "";
        }
        return String.format(
                "NOTA IMPORTANTE: por su tamaño, el expediente se adjunta dividido en %d archivos PDF " +
                        "consecutivos que constituyen UN ÚNICO documento. Analízalos en conjunto y respetando " +
                        "el orden en que se envían.%n%n", cantidadPartes);
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

        JsonNode resp = ejecutarPost(httpTransport, "Sentencia Fase 2 (embeddings)",
                accessToken, url, mapper.writeValueAsString(body));

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

        JsonNode resp = ejecutarPost(httpTransport, "Sentencia Fase 3 (Vector Search)",
                accessToken, url, mapper.writeValueAsString(body));

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
                JsonNode resp = ejecutarGet(httpTransport, "Sentencia: dominio del Index Endpoint",
                        accessToken, regionalBase + "/v1/" + indexEndpointResource);
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

    private JsonNode ejecutarPost(NetHttpTransport httpTransport, String contexto, String accessToken,
                                  String url, String jsonBody) throws IOException {
        return ejecutar(contexto, accessToken, url, () ->
                httpTransport.createRequestFactory().buildPostRequest(
                        new GenericUrl(url),
                        new ByteArrayContent("application/json", jsonBody.getBytes(StandardCharsets.UTF_8))));
    }

    private JsonNode ejecutarGet(NetHttpTransport httpTransport, String contexto, String accessToken, String url)
            throws IOException {
        return ejecutar(contexto, accessToken, url, () ->
                httpTransport.createRequestFactory().buildGetRequest(new GenericUrl(url)));
    }

    /**
     * Ejecuta la llamada REST con reintentos ante cortes del enlace con el proxy (ver
     * {@link ReintentoUtil}). La petición se reconstruye en cada intento (de ahí el constructor,
     * en vez de un {@code HttpRequest} ya armado) para que el intento tome una conexión limpia.
     */
    private JsonNode ejecutar(String contexto, String accessToken, String url,
                              ReintentoUtil.OperacionIO<HttpRequest> constructorPeticion) throws IOException {

        return ReintentoUtil.ejecutar(retryProperties, contexto, () -> {
            HttpRequest request = constructorPeticion.ejecutar();

            HttpHeaders headers = new HttpHeaders();
            headers.setAuthorization("Bearer " + accessToken);
            request.setHeaders(headers);
            request.setConnectTimeout(60_000);
            request.setReadTimeout(120_000);
            request.setThrowExceptionOnExecuteError(false);

            HttpResponse response = request.execute();
            try {
                if (response.getStatusCode() >= 300) {
                    throw new HttpEstadoException(response.getStatusCode(), "Error REST "
                            + response.getStatusCode() + " en " + url + ": " + response.parseAsString());
                }
                return mapper.readTree(response.getContent());
            } finally {
                response.disconnect();
            }
        });
    }
}
