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
import pj.gob.pe.consultaia.service.business.JurisprudenciaStoreService;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class JurisprudenciaStoreServiceImpl implements JurisprudenciaStoreService {

    private static final Logger log = LoggerFactory.getLogger(JurisprudenciaStoreServiceImpl.class);

    private static final String CHUNKS_LOCATION = "classpath*:chunks-jurisprudencia/*.jsonl";

    /**
     * Tipos de resolución de la etapa de calificación, en orden de preferencia
     * para la selección (el resto del corpus - sentencias, audiencias, etc. -
     * queda cargado para usos futuros pero no se inyecta en este flujo).
     */
    private static final List<String> TIPOS_CALIFICACION = List.of("auto_admisorio", "calificacion");

    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${spring.data.redis.prefix:jurisia_consultationia}")
    private String redisPrefix;

    /** Máximo de resoluciones de ejemplo a inyectar en el prompt. */
    @Value("${jurisprudencia.maxEjemplos:2}")
    private int maxEjemplos;

    private String hashKey() {
        return redisPrefix + ":chunks:jurisprudencia";
    }

    private HashOperations<String, String, String> hashOps() {
        return stringRedisTemplate.opsForHash();
    }

    /**
     * Precarga los ejemplos al arrancar el contexto de Spring. Si Redis no está
     * disponible, se registra el error sin tumbar el arranque: el fallback en
     * {@link #construirEjemplos} intentará cargarlos en la primera consulta.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void precargarAlIniciar() {
        try {
            // Recarga forzada en cada arranque para que Redis refleje el corpus del
            // despliegue actual (el Hash no tiene TTL y persistiría datos antiguos).
            long total = cargarChunks(true);
            log.info("[JurisprudenciaStore] Ejemplos jurisprudenciales disponibles en Redis: {}", total);
        } catch (Exception e) {
            log.error("[JurisprudenciaStore] No se pudieron precargar los ejemplos al iniciar (se reintentará bajo demanda): {}",
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
                log.warn("[JurisprudenciaStore] No se encontraron archivos en {}", CHUNKS_LOCATION);
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
                                log.warn("[JurisprudenciaStore] Línea sin 'id' en {} (línea {})",
                                        recurso.getFilename(), lineaNum);
                                continue;
                            }
                            // Guardamos la línea JSON cruda para conservar content + structData.
                            lote.put(id, linea);
                        } catch (Exception ex) {
                            log.warn("[JurisprudenciaStore] Línea inválida en {} (línea {}): {}",
                                    recurso.getFilename(), lineaNum, ex.getMessage());
                        }
                    }
                }
                if (!lote.isEmpty()) {
                    hashOps().putAll(hashKey(), lote);
                    cargados += lote.size();
                    log.info("[JurisprudenciaStore] Cargados {} ejemplos de {}", lote.size(), recurso.getFilename());
                }
            }
            log.info("[JurisprudenciaStore] Carga finalizada. Total de ejemplos en Redis: {}", cargados);
            return cargados;
        } catch (Exception e) {
            throw new RuntimeException("Error cargando ejemplos jurisprudenciales a Redis: " + e.getMessage(), e);
        }
    }

    @Override
    public boolean estaCargado() {
        Long size = hashOps().size(hashKey());
        return size != null && size > 0;
    }

    @Override
    public String construirEjemplos(String cmateria) {
        if (cmateria == null || cmateria.trim().isEmpty()) {
            return "";
        }
        String materiaBuscada = cmateria.trim();

        try {
            // Fallback: si por algún motivo los ejemplos no están en Redis, se cargan ahora.
            if (!estaCargado()) {
                log.warn("[JurisprudenciaStore] Ejemplos no presentes en Redis al momento de la consulta. Cargando bajo demanda...");
                cargarChunks(true);
            }

            // El corpus es pequeño (<100 ejemplos): se recorre completo en memoria.
            List<String> valores = hashOps().values(hashKey());
            List<JsonNode> candidatos = new ArrayList<>();
            for (String json : valores) {
                JsonNode node = objectMapper.readTree(json);
                JsonNode sd = node.path("structData");
                if (!TIPOS_CALIFICACION.contains(sd.path("tipo").asText(""))) {
                    continue;
                }
                if (contieneMateria(sd.path("materias"), materiaBuscada)) {
                    candidatos.add(node);
                }
            }

            if (candidatos.isEmpty()) {
                log.info("[JurisprudenciaStore] Sin ejemplos de calificación para cmateria={}", materiaBuscada);
                return "";
            }

            // Orden: materia principal coincidente primero, luego autos admisorios,
            // luego los más cortos (presupuesto de tokens). Selección con diversidad
            // de tipo: el segundo ejemplo intenta ser de tipo distinto al primero.
            candidatos.sort(Comparator
                    .comparingInt((JsonNode n) -> materiaBuscada.equals(
                            n.path("structData").path("materias").path(0).asText("")) ? 0 : 1)
                    .thenComparingInt(n -> TIPOS_CALIFICACION.indexOf(n.path("structData").path("tipo").asText("")))
                    .thenComparingInt(n -> n.path("content").asText("").length()));

            List<JsonNode> seleccionados = new ArrayList<>();
            seleccionados.add(candidatos.get(0));
            for (JsonNode candidato : candidatos) {
                if (seleccionados.size() >= maxEjemplos) break;
                boolean tipoRepetido = seleccionados.stream().anyMatch(s ->
                        s.path("structData").path("tipo").asText("")
                                .equals(candidato.path("structData").path("tipo").asText("")));
                if (!seleccionados.contains(candidato) && !tipoRepetido) {
                    seleccionados.add(candidato);
                }
            }
            for (JsonNode candidato : candidatos) {
                if (seleccionados.size() >= maxEjemplos) break;
                if (!seleccionados.contains(candidato)) {
                    seleccionados.add(candidato);
                }
            }

            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < seleccionados.size(); i++) {
                JsonNode node = seleccionados.get(i);
                JsonNode sd = node.path("structData");
                log.info("[JurisprudenciaStore] Ejemplo seleccionado para cmateria={}: {} (tipo={})",
                        materiaBuscada, node.path("id").asText(""), sd.path("tipo").asText(""));
                sb.append(String.format("<ejemplo_resolucion_%d tipo=\"%s\" materia=\"%s\">%n",
                                i + 1,
                                sd.path("tipo").asText(""),
                                joinTextos(sd.path("materias_desc"))))
                        .append(node.path("content").asText(""))
                        .append(String.format("%n</ejemplo_resolucion_%d>%n%n", i + 1));
            }
            return sb.toString().trim();
        } catch (Exception e) {
            // Los ejemplos son un refuerzo, no un insumo crítico: ante cualquier error
            // se continúa la calificación sin ellos.
            log.error("[JurisprudenciaStore] Error construyendo ejemplos para cmateria={}: {}",
                    materiaBuscada, e.getMessage());
            return "";
        }
    }

    private boolean contieneMateria(JsonNode materias, String cmateria) {
        if (!materias.isArray()) return false;
        for (JsonNode m : materias) {
            if (cmateria.equals(m.asText(""))) return true;
        }
        return false;
    }

    private String joinTextos(JsonNode array) {
        if (!array.isArray()) return "";
        List<String> out = new ArrayList<>();
        for (JsonNode n : array) {
            out.add(n.asText(""));
        }
        return String.join(", ", out);
    }
}
