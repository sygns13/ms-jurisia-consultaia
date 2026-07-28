package pj.gob.pe.consultaia.service.externals;

import java.io.IOException;

/**
 * Almacenamiento en Google Cloud Storage de los adjuntos del chat conversacional con Gemini.
 *
 * A diferencia de {@link GcsStorageService} (calificación de demandas, objetos temporales que se
 * borran tras la llamada), los adjuntos del chat son PERSISTENTES: cada archivo se sube una vez y
 * su URI gs:// queda registrado en la tabla GeminiChatsFiles, de modo que Gemini pueda leerlo
 * server-side en el turno actual y en los turnos posteriores de la conversación (historial
 * multimodal). Usa un bucket propio ({@code gcp.chatGcsBucket}), separado del de calificación.
 */
public interface GeminiChatStorageService {

    /**
     * Sube el adjunto a {@code gs://{chatGcsBucket}/{prefix}/{sessionUID}/{uuid}_{fileName}} y
     * devuelve su URI gs://.
     *
     * @param sessionUID  UUID de la conversación (agrupa los adjuntos por conversación).
     * @param fileName    nombre original del archivo (se sanitiza para el objeto GCS).
     * @param contentType MIME del archivo.
     * @param contenido   bytes del archivo.
     * @return URI del objeto creado, con formato {@code gs://...}.
     */
    String subir(String sessionUID, String fileName, String contentType, byte[] contenido) throws IOException;
}
