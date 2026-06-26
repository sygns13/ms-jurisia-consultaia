package pj.gob.pe.consultaia.utils.beans.responses;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Schema(description = "Response de generación de sentencia de demanda con Gemini")
@Data
@AllArgsConstructor
@NoArgsConstructor
public class ResponseGeneracionSentencia {

    @Schema(description = "ID de la sentencia generada")
    private Long id;

    @Schema(description = "Status de la generación", example = "1")
    private Integer status;

    @Schema(description = "Modelo Gemini utilizado", example = "gemini-3.1-pro-preview")
    private String model;

    @Schema(description = "Respuesta generada por Gemini con la sentencia de la demanda")
    private String response;

    @Schema(description = "Tiempo total de procesamiento en segundos")
    private Double timeSeconds;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    @Schema(description = "Fecha y hora de envío a Gemini")
    private LocalDateTime fechaSend;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    @Schema(description = "Fecha y hora de respuesta de Gemini")
    private LocalDateTime fechaResponse;
}
