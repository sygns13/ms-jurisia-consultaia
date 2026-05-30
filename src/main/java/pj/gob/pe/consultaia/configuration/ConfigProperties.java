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

    @Value("${sij.proxy.config.enabled:false}")
    private Boolean proxyEnabled;

    @Value("${sij.proxy.config.host}")
    private String proxyURL;

    @Value("${sij.proxy.config.port}")
    private Integer proxyPort;

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

    @Value("${gcp.embeddingModel}")
    private String gcpEmbeddingModel;

    @Value("${gcp.neighborCount}")
    private Integer gcpNeighborCount;

    @Value("${gcp.indexEndpointId}")
    private String gcpIndexEndpointId;

    @Value("${gcp.deployedIndexId}")
    private String gcpDeployedIndexId;
}
