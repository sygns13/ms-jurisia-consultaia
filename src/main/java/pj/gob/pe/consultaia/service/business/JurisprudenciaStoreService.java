package pj.gob.pe.consultaia.service.business;

/**
 * Almacén en Redis de las resoluciones de ejemplo (jurisprudencia anonimizada del
 * 1er Juzgado de Familia) usadas como few-shot en la calificación de demandas.
 * Los chunks se precargan al iniciar el proyecto (sin TTL) desde
 * classpath chunks-jurisprudencia/*.jsonl y se seleccionan por código de materia
 * SIJ (cmateria) del expediente a calificar.
 */
public interface JurisprudenciaStoreService {

    /**
     * Carga todos los ejemplos (classpath chunks-jurisprudencia/*.jsonl) en Redis
     * si aún no están cargados. Idempotente. No establece TTL.
     *
     * @param forzar si es true recarga aunque ya existan datos en Redis.
     * @return cantidad de ejemplos presentes en Redis tras la operación.
     */
    long cargarChunks(boolean forzar);

    /**
     * Indica si los ejemplos ya están cargados en Redis.
     */
    boolean estaCargado();

    /**
     * Construye el bloque de ejemplos de resoluciones para inyectar en el prompt
     * de la fase de redacción (Fase 4). Selecciona hasta N resoluciones de la
     * etapa de calificación (auto admisorio / calificación) cuya metadata de
     * materias contenga el cmateria indicado, priorizando las de materia
     * principal coincidente y la diversidad de tipo.
     *
     * @param cmateria código de materia SIJ del expediente (ej. "637" tenencia).
     * @return bloque de texto listo para el prompt, o cadena vacía si no hay
     *         cmateria o no existen ejemplos para esa materia.
     */
    String construirEjemplos(String cmateria);
}
