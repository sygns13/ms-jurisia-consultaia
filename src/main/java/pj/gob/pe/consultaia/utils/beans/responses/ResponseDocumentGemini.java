package pj.gob.pe.consultaia.utils.beans.responses;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import pj.gob.pe.consultaia.utils.beans.SectionTemplate;

import java.math.BigDecimal;
import java.util.List;

/**
 * Resultado del procesamiento de un documento por secciones con Gemini (corrección
 * ortotipográfica). Equivalente Gemini de {@link ResponseDocument}, sin los campos de
 * tokens propios de la API de OpenAI.
 */
@Schema(description = "Response del procesamiento de documento con Gemini")
@Data
@AllArgsConstructor
@NoArgsConstructor
public class ResponseDocumentGemini {

    @Schema(description = "ID de Expediente")
    private Long nUnico;

    @Schema(description = "Template Code")
    private String codeTemplate;

    @Schema(description = "Section Templates con el contenido corregido")
    private List<SectionTemplate> sectionTemplates;

    private String model;
    private String roleSystem;
    private BigDecimal temperature;

    @Schema(description = "Cantidad de secciones enviadas a la IA en esta corrida (las demás salieron de caché o no aplicaban)")
    private Integer seccionesProcesadasIA;

    @Schema(description = "Tiempo total de las llamadas a la IA en segundos")
    private Double timeSeconds;

    private Integer configurationsId;

    @Schema(description = "UUID de la corrida de procesamiento")
    private String sessionUID;
}
