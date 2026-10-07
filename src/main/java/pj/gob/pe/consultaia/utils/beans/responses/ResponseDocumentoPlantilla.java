package pj.gob.pe.consultaia.utils.beans.responses;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import pj.gob.pe.consultaia.utils.beans.BloquePlantilla;
import pj.gob.pe.consultaia.utils.beans.ParrafoPlantilla;

import java.math.BigDecimal;
import java.util.List;

@Schema(description = "Response del procesamiento de documento por plantilla con Gemini")
@Data
@AllArgsConstructor
@NoArgsConstructor
public class ResponseDocumentoPlantilla {

    private String sessionUID;

    @Schema(description = "Solo los párrafos corregidos y validados (mismo id y misma cantidad de segmentos)")
    private List<ParrafoPlantilla> parrafos;

    @Schema(description = "Bloques redactados")
    private List<BloquePlantilla> bloques;

    private Integer parrafosCorregidos;
    private Integer bloquesGenerados;

    private String model;
    private String roleSystem;
    private BigDecimal temperature;
    private Integer configurationsId;
    private String finishReason;

    private Integer promptTokens;
    private Integer candidatesTokens;
    private Integer thoughtsTokens;
    private Integer cachedTokens;
    private Integer totalTokens;

    @Schema(description = "Tiempo de la llamada a Gemini en segundos")
    private Double timeSeconds;
}
