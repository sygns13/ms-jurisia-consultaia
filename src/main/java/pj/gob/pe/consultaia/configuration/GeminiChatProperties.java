package pj.gob.pe.consultaia.configuration;

import lombok.Getter;
import lombok.Setter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

/**
 * Propiedades del módulo conversacional con Gemini. Se define como clase separada (y no dentro de
 * {@link ConfigProperties}) para no modificar el código existente de los flujos ya productivos.
 */
@Configuration
@Getter
@Setter
public class GeminiChatProperties {

    /**
     * Bucket GCS PROPIO para los adjuntos del chat. No se usa el bucket de calificación/sentencia
     * de demandas ({@code gcp.gcsBucket}) porque son flujos distintos y los adjuntos del chat son
     * persistentes (se reinyectan como contexto en turnos posteriores).
     */
    @Value("${gcp.chatGcsBucket}")
    private String chatGcsBucket;

    @Value("${gcp.chatGcsObjectPrefix}")
    private String chatGcsObjectPrefix;
}
