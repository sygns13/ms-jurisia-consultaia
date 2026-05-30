package pj.gob.pe.consultaia.service.business;

import java.util.List;

/**
 * Almacén en Redis de los chunks normativos (textos reales a los que apuntan los
 * embeddings del índice de Vector Search). Los chunks se precargan al iniciar el
 * proyecto (sin TTL) y se consultan por los datapointId devueltos por findNeighbors.
 */
public interface ChunkStoreService {

    /**
     * Carga todos los chunks (classpath chunks/*.jsonl) en Redis si aún no están cargados.
     * Idempotente. No establece TTL.
     *
     * @param forzar si es true recarga aunque ya existan datos en Redis.
     * @return cantidad de chunks presentes en Redis tras la operación.
     */
    long cargarChunks(boolean forzar);

    /**
     * Indica si los chunks ya están cargados en Redis.
     */
    boolean estaCargado();

    /**
     * Construye el bloque de contexto legal (texto real + cita) para los IDs indicados,
     * respetando el orden de relevancia recibido. Si Redis no tiene los datos cargados,
     * los carga en el momento (fallback) antes de consultar.
     *
     * @param ids datapointId devueltos por el Vector Search.
     * @return texto formateado listo para inyectar en el prompt de Gemini.
     */
    String construirContextoLegal(List<String> ids);
}
