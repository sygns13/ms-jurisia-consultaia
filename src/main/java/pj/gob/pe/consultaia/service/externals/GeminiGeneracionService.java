package pj.gob.pe.consultaia.service.externals;

import java.io.IOException;
import java.util.List;

/**
 * Redacción final (Fase 4) de la calificación de demandas y de la sentencia con Gemini, aplicando el
 * nivel de razonamiento configurado en {@code gcp.finalThinkingLevel}.
 *
 * MOTIVO: el SDK {@code google-cloud-vertexai} (obsoleto) solo expone {@code thinkingBudget}, y los
 * modelos nuevos (gemini-4-argon) lo rechazan con HTTP 400 y exigen {@code thinkingLevel}. El cliente
 * Java de {@code google-cloud-aiplatform} sí conoce {@code thinkingLevel}, pero su PredictionService
 * solo habla gRPC, que no sale por el proxy institucional. Por eso la llamada se hace por REST con el
 * transporte del PAC, armando la petición y leyendo la respuesta con los protos v1 de aiplatform.
 */
public interface GeminiGeneracionService {

    /**
     * Invoca {@code generateContent} y devuelve el texto de la respuesta (sin las partes de razonamiento).
     *
     * @param contexto           etiqueta para logs y reintentos (p. ej. "Calificación Fase 4").
     * @param modelo             id del modelo (p. ej. "gemini-4-argon").
     * @param instruccionSistema system instruction.
     * @param gsUrisPdf          1..n PDF ya subidos a GCS; viajan en la misma petición, antes del prompt.
     * @param prompt             texto del usuario.
     * @param temperatura        temperatura de muestreo.
     * @param maxOutputTokens    tope de salida (incluye los tokens de razonamiento).
     * @param timeoutLecturaMs   tiempo máximo de espera de la respuesta.
     */
    String generarTexto(String contexto, String modelo, String instruccionSistema, List<String> gsUrisPdf,
                        String prompt, float temperatura, int maxOutputTokens, int timeoutLecturaMs) throws IOException;
}
