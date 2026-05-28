package pj.gob.pe.consultaia.utils.beans.inputs;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Schema(description = "Input para calificación de demanda con Gemini")
@Data
@AllArgsConstructor
@NoArgsConstructor
public class InputCalificacionDemanda {

    @Schema(description = "Año del expediente", example = "2026")
    private String anio;

    @Schema(description = "Número de expediente", example = "00272")
    private String expNro;

    @Schema(description = "Tipo de expediente", example = "Fisico")
    private String tipoExpediente;

    @NotNull(message = "rutaCompleta es requerida")
    @Schema(description = "Ruta FTP completa (incluye credenciales y carpeta)", example = "ftp://ncpp:123456@172.17.16.201/2026/05/2026002720201133/")
    private String rutaCompleta;

    @Schema(description = "Formato del expediente", example = "00272-2026-0-0201-JR-FC-01")
    private String xformato;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    @Schema(description = "Fecha de inicio (alterna)", example = "2026-05-15 15:02:52")
    private LocalDateTime finicio;

    @Schema(description = "Clave del expediente")
    private String cclave;

    @Schema(description = "IP del servidor FTP", example = "172.17.16.201")
    private String xip;

    @NotNull(message = "nunico es requerido")
    @Schema(description = "ID del expediente (número único)", example = "2026002720201133")
    private Long nunico;

    @Schema(description = "Nombre de la instancia", example = "1° JUZGADO FAMILIA - Sede San Martin")
    private String xnomInstancia;

    @Schema(description = "Código de ubicación", example = "10")
    private String cubicacion;

    @Schema(description = "Código de instancia", example = "301")
    private String cinstancia;

    @Schema(description = "Descripción del estado", example = "EN CALIFICACION")
    private String xdescEstado;

    @Schema(description = "Código de usuario", example = "ncpp")
    private String cusuario;

    @Schema(description = "Código de materia", example = "191")
    private String cmateria;

    @Schema(description = "Código de especialidad", example = "FC")
    private String cespecialidad;

    @Schema(description = "Descripción de la ubicación", example = "MPU / CDG")
    private String xdescUbicacion;

    @NotNull(message = "xnombreArchivo es requerido")
    @Schema(description = "Nombre del archivo PDF de la demanda", example = "0000185529-2026-EXP-JR-FC_15052026_155311.pdf")
    private String xnombreArchivo;

    @Schema(description = "Número de incidente", example = "0")
    private String nincidente;

    @Schema(description = "Ruta del archivo (relativa)", example = "2026/05/2026002720201133")
    private String xrutaArchivo;

    @Schema(description = "Descripción de la materia", example = "DIVORCIO POR CAUSAL")
    private String xdescMateria;
}
