package pj.gob.pe.consultaia.configuration;

import lombok.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

@Configuration
@Getter
@Setter
public class ConfigProperties {

    @Value("${api.openai.main.url}")
    private String urlOpenAI;

    @Value("${api.openai.chat.path}")
    private String pathChatGPT;

    @Value("${api.security.url}")
    private String urlSecurityAPI;

    @Value("${api.security.get.session.path}")
    private String pathGetSession;

    @Value("${api.openai.secret.key}")
    private String secretKeyOpenAI;

    @Value("${spring.data.redis.prefix:jurisia_security}")
    private String REDIS_KEY_PREFIX;

    @Value("${spring.data.redis.ttl:3600}")
    private Long REDIS_TTL;

    // --- Proxy ANTERIOR (general SIJ). Usado por OpenAI (OpenAPIServiceImpl) ---
    @Value("${sij.proxy.config.enabled:false}")
    private Boolean proxyEnabled;

    @Value("${sij.proxy.config.host}")
    private String proxyURL;

    @Value("${sij.proxy.config.port}")
    private Integer proxyPort;

    // --- Proxy hacia Google Cloud / Vertex / Gemini / GCS (ver ProxyPacServiceImpl). Según el modo,
    //     el proxy lo decide el PAC institucional (AD_total) en cada host o es uno fijo. ---
    @Value("${sij.proxy.google.enabled:false}")
    private Boolean proxyGoogleEnabled;

    // "pac" = el PAC decide el proxy por host; "fijo" = todo Google sale por sij.proxy.google.fijo.
    @Value("${sij.proxy.google.modo:pac}")
    private String proxyGoogleModo;

    @Value("${sij.proxy.google.fijo.host:}")
    private String proxyGoogleFijoHost;

    @Value("${sij.proxy.google.fijo.port:0}")
    private Integer proxyGoogleFijoPort;

    @Value("${sij.proxy.google.pacUrl}")
    private String proxyGooglePacUrl;

    // IP que el PAC recibe como myIpAddress(). Vacía = se detecta la interfaz que sale hacia el PAC.
    @Value("${sij.proxy.google.ipLocal:}")
    private String proxyGoogleIpLocal;

    @Value("${sij.proxy.google.recargaMinutos:60}")
    private Integer proxyGoogleRecargaMinutos;

    // Proxy de respaldo mientras el PAC no se haya podido descargar o evaluar.
    @Value("${sij.proxy.google.respaldo.host}")
    private String proxyGoogleRespaldoHost;

    @Value("${sij.proxy.google.respaldo.port}")
    private Integer proxyGoogleRespaldoPort;

    // --- Proxy para Vector Search (findNeighbors contra *.vdb.vertexai.goog). Los proxys de Google
    //     del PAC rechazan ese dominio (403), así que sale por el proxy ANTERIOR. ---
    @Value("${sij.proxy.vectorSearch.enabled:false}")
    private Boolean proxyVectorSearchEnabled;

    @Value("${sij.proxy.vectorSearch.dominio:.vdb.vertexai.goog}")
    private String proxyVectorSearchDominio;

    @Value("${sij.proxy.vectorSearch.host}")
    private String proxyVectorSearchHost;

    @Value("${sij.proxy.vectorSearch.port}")
    private Integer proxyVectorSearchPort;

    @Value("${api.judicial.url}")
    private String urlJudicialAPI;

    @Value("${api.judicial.get.sedes.path}")
    private String pathSedes;

    @Value("${api.judicial.get.instancias.path}")
    private String pathInstancias;

    // Inyectamos la cadena completa del JSON desde el YAML
    @Value("${gcp.credentials.content}")
    private String gcpCredentialsContent;

    @Value("${gcp.projectId}")
    private String gcpProjectId;

    @Value("${gcp.locationGlobal}")
    private String gcpLocationGlobal;

    @Value("${gcp.dataStoreId}")
    private String gcpDataStoreId;

    @Value("${gcp.scoped}")
    private String gcpScoped;

    @Value("${gcp.endpoint}")
    private String gcpEndpoint;

    @Value("${gcp.endpointAPI}")
    private String gcpEndpointAPI;

    @Value("${gcp.datastorePath}")
    private String gcpDatastorePath;

    // --- RAG Orquestado (Vector Search) para la calificación de demandas ---
    @Value("${gcp.vectorSearchLocation}")
    private String gcpVectorSearchLocation;

    // Modelo Gemini de la Fase 1 (extracción de conceptos). El de la Fase 4 viene de BD.
    @Value("${gcp.extractionModel}")
    private String gcpExtractionModel;

    // Nivel de razonamiento (LOW/MEDIUM/HIGH) de la Fase 4. Vacío = no se envía thinkingConfig.
    @Value("${gcp.finalThinkingLevel:}")
    private String gcpFinalThinkingLevel;

    @Value("${gcp.embeddingModel}")
    private String gcpEmbeddingModel;

    @Value("${gcp.neighborCount}")
    private Integer gcpNeighborCount;

    @Value("${gcp.indexEndpointId}")
    private String gcpIndexEndpointId;

    @Value("${gcp.deployedIndexId}")
    private String gcpDeployedIndexId;

    // Opcional: si se setea, se evita el GET de metadata para resolver el dominio del Index Endpoint.
    @Value("${gcp.vectorSearchPublicDomain:}")
    private String gcpVectorSearchPublicDomain;

    // GCS: el PDF se sube aquí una vez y se referencia por URI gs:// en las llamadas a Gemini.
    @Value("${gcp.gcsBucket}")
    private String gcpGcsBucket;

    @Value("${gcp.gcsObjectPrefix}")
    private String gcpGcsObjectPrefix;

    // --- Acondicionamiento del PDF antes de enviarlo a Gemini (PdfOptimizerUtil) ---
    // Gemini rechaza los PDF de más de 50 MB (52 428 800 bytes) o de más de 1000 páginas.
    @Value("${gcp.pdf.limiteBytes:50331648}")
    private Long gcpPdfLimiteBytes;

    @Value("${gcp.pdf.dpiObjetivo:150}")
    private Integer gcpPdfDpiObjetivo;

    @Value("${gcp.pdf.calidadJpeg:0.6}")
    private Float gcpPdfCalidadJpeg;

    @Value("${gcp.pdf.maxPaginasPorParte:1000}")
    private Integer gcpPdfMaxPaginasPorParte;

    @Value("${gcp.pdf.comprimirSiempre:false}")
    private Boolean gcpPdfComprimirSiempre;
}
