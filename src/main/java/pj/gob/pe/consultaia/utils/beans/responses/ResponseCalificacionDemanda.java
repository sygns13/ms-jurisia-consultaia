package pj.gob.pe.consultaia.utils.beans.responses;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Schema(description = "Response de calificación de demanda con Gemini")
@Data
@AllArgsConstructor
@NoArgsConstructor
public class ResponseCalificacionDemanda {

    @Schema(description = "ID de la calificación de demanda")
    private Long id;

    @Schema(description = "Status de la calificación", example = "1")
    private Integer status;

    @Schema(description = "Modelo Gemini utilizado", example = "gemini-3.1-pro-preview")
    private String model;

    @Schema(description = "Respuesta generada por Gemini con la calificación de la demanda")
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
