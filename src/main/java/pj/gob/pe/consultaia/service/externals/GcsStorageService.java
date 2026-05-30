package pj.gob.pe.consultaia.service.externals;

import java.io.IOException;

/**
 * Almacenamiento temporal en Google Cloud Storage para la calificación de demandas.
 *
 * Estrategia: el PDF se sube UNA sola vez a GCS y se referencia por URI ({@code gs://...}) en las
 * llamadas a Gemini. Así Vertex AI lee el documento server-side (dentro de la red de Google) y se
 * evita reenviar los ~10 MB del PDF por el proxy del Poder Judicial en cada fase. El objeto se borra
 * tras la calificación (best-effort).
 *
 * Implementado por REST crudo sobre la JSON API de Storage, reutilizando el mismo transporte con
 * proxy y las credenciales del service account, sin agregar la dependencia google-cloud-storage.
 */
public interface GcsStorageService {

    /**
     * Sube el contenido a un objeto único bajo el prefijo configurado y devuelve su URI gs://.
     *
     * @param contenido   bytes del archivo (p. ej. el PDF de la demanda).
     * @param contentType MIME del archivo (p. ej. "application/pdf").
     * @return URI del objeto creado, con formato {@code gs://{bucket}/{prefix}/{uuid}.bin}.
     */
    String subir(byte[] contenido, String contentType) throws IOException;

    /**
     * Borra el objeto identificado por su URI gs://. Pensado para invocarse en un finally; el
     * llamador decide cómo manejar la excepción (típicamente loguear sin abortar el flujo).
     *
     * @param gsUri URI devuelto por {@link #subir(byte[], String)}.
     */
    void borrar(String gsUri) throws IOException;
}
