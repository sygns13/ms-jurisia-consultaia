package pj.gob.pe.consultaia.utils.beans.responses;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import pj.gob.pe.consultaia.model.entities.DemandasSentencias;

import java.time.LocalDateTime;

@Schema(description = "Item del listado de sentencias de demanda generadas (sin el texto completo de la sentencia)")
@Data
@AllArgsConstructor
@NoArgsConstructor
public class ResponseListadoDemandaSentencia {

    @Schema(description = "ID de la sentencia generada", example = "1")
    private Long id;

    @Schema(description = "ID del expediente (número único)", example = "2026002720201133")
    private Long nUnico;

    @Schema(description = "Año del expediente", example = "2026")
    private String anio;

    @Schema(description = "Número de expediente", example = "00272")
    private String expNro;

    @Schema(description = "Tipo de expediente", example = "Fisico")
    private String tipoExpediente;

    @Schema(description = "Formato del expediente", example = "00272-2026-0-0201-JR-FC-01")
    private String xformato;

    @Schema(description = "Nombre de la instancia")
    private String xnomInstancia;

    @Schema(description = "Descripción de la materia")
    private String xdescMateria;

    @Schema(description = "Modelo Gemini utilizado", example = "gemini-3.1-pro-preview")
    private String model;

    @Schema(description = "Status de la generación de la sentencia", example = "1")
    private Integer status;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    @Schema(description = "Fecha y hora de envío a Gemini")
    private LocalDateTime fechaSend;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    @Schema(description = "Fecha y hora de respuesta de Gemini")
    private LocalDateTime fechaResponse;

    @Schema(description = "Tiempo total de procesamiento en segundos")
    private Double timeSeconds;

    public static ResponseListadoDemandaSentencia fromEntity(DemandasSentencias d) {
        ResponseListadoDemandaSentencia r = new ResponseListadoDemandaSentencia();
        r.setId(d.getId());
        r.setNUnico(d.getNUnico());
        r.setAnio(d.getAnio());
        r.setExpNro(d.getExpNro());
        r.setTipoExpediente(d.getTipoExpediente());
        r.setXformato(d.getXformato());
        r.setXnomInstancia(d.getXnomInstancia());
        r.setXdescMateria(d.getXdescMateria());
        r.setModel(d.getModel());
        r.setStatus(d.getStatus());
        r.setFechaSend(d.getFechaSend());
        r.setFechaResponse(d.getFechaResponse());
        r.setTimeSeconds(d.getTimeSeconds());
        return r;
    }
}
