package pj.gob.pe.consultaia.utils.beans.inputs;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Adjunto del chat con Gemini ya leído en memoria. El controller extrae los bytes del
 * {@code MultipartFile} antes de entrar al Callable async, para no depender del ciclo de vida
 * del archivo temporal de multipart.
 */
@Schema(description = "Adjunto (en memoria) de una consulta al chat Gemini")
@Data
@AllArgsConstructor
@NoArgsConstructor
public class InputGeminiChatFile {

    @Schema(description = "Nombre original del archivo", example = "demanda_alimentos.pdf")
    private String fileName;

    @Schema(description = "MIME type del archivo", example = "application/pdf")
    private String mimeType;

    @Schema(description = "Contenido del archivo")
    private byte[] content;
}
