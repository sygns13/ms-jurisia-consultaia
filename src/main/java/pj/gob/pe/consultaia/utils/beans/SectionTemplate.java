package pj.gob.pe.consultaia.utils.beans;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Schema(description = "Sections of Template Model")
@Entity
@Table(name = "SectionTemplates")
@Data
@AllArgsConstructor
@NoArgsConstructor
public class SectionTemplate {

    private Long id;

    @Schema(description = "ID de Template")
    private Long idTemplate;

    @Schema(description = "Codigo de Section")
    private String codigo;

    @Schema(description = "Content de Section")
    private String content;

    @Schema(description = "Descripcion de Section")
    private String descripcion;

    @Schema(description = "Si es final su valor se reemplaza directamente en el documento")
    private Integer isFinal;

    @Schema(description = "Si es bold para retornar al frontend")
    private Integer isBold;

    @Schema(description = "Indicador si se va a enviar a la IA para revisión")
    private Integer isSendIA;

    @Schema(description = "Indicador si luego de pintarlo se hace un salto de línea para retornar al frontend")
    private Integer isSaltoLinea;

    private Boolean isProcessed;
}
