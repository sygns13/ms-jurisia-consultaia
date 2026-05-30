package pj.gob.pe.consultaia.service.business.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import pj.gob.pe.consultaia.service.business.ChunkStoreService;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class ChunkStoreServiceImpl implements ChunkStoreService {

    private static final Logger log = LoggerFactory.getLogger(ChunkStoreServiceImpl.class);

    private static final String CHUNKS_LOCATION = "classpath*:chunks/*.jsonl";

    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${spring.data.redis.prefix:jurisia_consultationia}")
    private String redisPrefix;

    private String hashKey() {
        return redisPrefix + ":chunks:normativa";
    }

    private HashOperations<String, String, String> hashOps() {
        return stringRedisTemplate.opsForHash();
    }

    /**
     * Precarga los chunks al arrancar el contexto de Spring. Si Redis no está disponible,
     * se registra el error sin tumbar el arranque: el fallback en {@link #construirContextoLegal}
     * intentará cargarlos en el momento de la primera consulta.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void precargarAlIniciar() {
        try {
            // Forzamos la recarga en cada arranque para que Redis refleje los chunks del
            // despliegue actual (el Hash no tiene TTL y persistiría datos antiguos). Es
            // idempotente y de bajo costo (~7200 HSET, sub-segundo).
            long total = cargarChunks(true);
            log.info("[ChunkStore] Chunks normativos disponibles en Redis: {}", total);
        } catch (Exception e) {
            log.error("[ChunkStore] No se pudieron precargar los chunks al iniciar (se reintentará bajo demanda): {}",
                    e.getMessage());
        }
    }

    @Override
    public long cargarChunks(boolean forzar) {
        if (!forzar && estaCargado()) {
            return hashOps().size(hashKey());
        }

        try {
            ResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
            Resource[] recursos = resolver.getResources(CHUNKS_LOCATION);

            if (recursos.length == 0) {
                log.warn("[ChunkStore] No se encontraron archivos en {}", CHUNKS_LOCATION);
                return 0;
            }

            long cargados = 0;
            for (Resource recurso : recursos) {
                Map<String, String> lote = new HashMap<>();
                int lineaNum = 0;
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(recurso.getInputStream(), StandardCharsets.UTF_8))) {
                    String linea;
                    while ((linea = reader.readLine()) != null) {
                        lineaNum++;
                        if (linea.isBlank()) continue;
                        try {
                            JsonNode node = objectMapper.readTree(linea);
                            String id = node.path("id").asText("");
                            if (id.isEmpty()) {
                                log.warn("[ChunkStore] Línea sin 'id' en {} (línea {})",
                                        recurso.getFilename(), lineaNum);
                                continue;
                            }
                            // Guardamos la línea JSON cruda para conservar content + structData.
                            lote.put(id, linea);
                        } catch (Exception ex) {
                            log.warn("[ChunkStore] Línea inválida en {} (línea {}): {}",
                                    recurso.getFilename(), lineaNum, ex.getMessage());
                        }
                    }
                }
                if (!lote.isEmpty()) {
                    hashOps().putAll(hashKey(), lote);
                    cargados += lote.size();
                    log.info("[ChunkStore] Cargados {} chunks de {}", lote.size(), recurso.getFilename());
                }
            }
            // El hash de Redis no tiene TTL por defecto: los datos persisten indefinidamente.
            log.info("[ChunkStore] Carga finalizada. Total de chunks en Redis: {}", cargados);
            return cargados;
        } catch (Exception e) {
            throw new RuntimeException("Error cargando chunks normativos a Redis: " + e.getMessage(), e);
        }
    }

    @Override
    public boolean estaCargado() {
        Long size = hashOps().size(hashKey());
        return size != null && size > 0;
    }

    @Override
    public String construirContextoLegal(List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return "No se encontró normativa específica para la consulta.";
        }

        // Fallback: si por algún motivo los chunks no están en Redis, se cargan ahora.
        if (!estaCargado()) {
            log.warn("[ChunkStore] Chunks no presentes en Redis al momento de la consulta. Cargando bajo demanda...");
            cargarChunks(true);
        }

        // Mantener orden de relevancia y eliminar duplicados.
        List<String> idsUnicos = new ArrayList<>(new LinkedHashSet<>(ids));

        List<String> valores = hashOps().multiGet(hashKey(), idsUnicos);

        StringBuilder sb = new StringBuilder();
        List<String> noEncontrados = new ArrayList<>();

        for (int i = 0; i < idsUnicos.size(); i++) {
            String id = idsUnicos.get(i);
            String json = (valores != null) ? valores.get(i) : null;
            if (json == null) {
                noEncontrados.add(id);
                continue;
            }
            sb.append(formatearChunk(id, json)).append("\n\n");
        }

        if (sb.length() == 0) {
            return "No se encontró el texto de la normativa recuperada (IDs: " + String.join(", ", noEncontrados) + ").";
        }
        if (!noEncontrados.isEmpty()) {
            log.warn("[ChunkStore] IDs sin chunk asociado en Redis: {}", noEncontrados);
        }
        return sb.toString().trim();
    }

    /**
     * Formatea un chunk como bloque legible con su cita (a partir de structData) y el texto real.
     */
    private String formatearChunk(String id, String json) {
        try {
            JsonNode node = objectMapper.readTree(json);
            String content = node.path("content").asText("");
            JsonNode sd = node.path("structData");

            String code = sd.path("code").asText("");
            String titulo = sd.path("titulo").asText("");
            String numero = sd.path("numero").asText("");
            String fuente = sd.path("fuente_pdf").asText("");
            String pagina = sd.path("pagina_inicio").asText("");

            StringBuilder cita = new StringBuilder("[");
            cita.append(code.isEmpty() ? id : code);
            if (!titulo.isEmpty()) cita.append(" · ").append(titulo);
            if (!numero.isEmpty()) cita.append(" · Art./Núm. ").append(numero);
            cita.append("]");
            if (!fuente.isEmpty()) {
                cita.append(" (Fuente: ").append(fuente);
                if (!pagina.isEmpty()) cita.append(", p. ").append(pagina);
                cita.append(")");
            }

            return cita + "\n" + content;
        } catch (Exception e) {
            // Si el JSON no se puede parsear, devolvemos el contenido crudo identificado por su id.
            return "[" + id + "]\n" + json;
        }
    }
}
