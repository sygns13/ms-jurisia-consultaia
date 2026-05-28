package pj.gob.pe.consultaia.model.entities;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnore;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.persistence.*;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Schema(description = "Entidad que representa la tabla GeminiChats")
@Entity
@Table(name = "GeminiChats")
@Data // Lombok: Genera getters, setters, toString, equals, y hashCode
@NoArgsConstructor // Lombok: Constructor sin argumentos
@AllArgsConstructor // Lombok: Constructor con todos los argumentos
public class GeminiChats {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Schema(description = "ID único del chat", example = "1")
    private Long id;

    @Column(name = "userId")
    @Schema(description = "ID del usuario", example = "12345")
    private Long userId;

    @Column(name = "model", length = 50)
    @Schema(description = "Modelo utilizado", example = "gemini-3.1-pro-preview")
    private String model;

    @Column(name = "roleSystem", columnDefinition = "TEXT")
    @Schema(description = "Rol del sistema", example = "Assistant")
    private String roleSystem;

    @Column(name = "prompt", columnDefinition = "TEXT")
    @Schema(description = "Prompt del usuario")
    private String prompt;

    @Column(name = "temperature", precision = 3, scale = 1)
    @DecimalMin(value = "0.0", message = "El valor debe ser mayor o igual a 0.0")
    @DecimalMax(value = "1.0", message = "El valor debe ser menor o igual a 1.0")
    private BigDecimal temperature;

    @Column(name = "fechaSend")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    @Schema(description = "Fecha y hora de envío", example = "2023-10-01T12:00:00")
    private LocalDateTime fechaSend;

    @Column(name = "fechaResponse")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    @Schema(description = "Fecha y hora de respuesta", example = "2023-10-01T12:05:00")
    private LocalDateTime fechaResponse;

    @Column(name = "response", columnDefinition = "TEXT")
    @Schema(description = "Respuesta generada por Gemini")
    private String response;

    @Column(name = "timeSeconds")
    @Schema(description = "Tiempo total de procesamiento en segundos", example = "12.34")
    private Double timeSeconds;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "ConfigurationsId", nullable = false)
    @Schema(description = "Configuración asociada")
    private Configurations configurations;

    @Column(name = "sessionUID", length = 50, nullable = false)
    @Schema(description = "ID único de la sesión", example = "session-12345")
    private String sessionUID;

    @Column(name = "status")
    @Schema(description = "Status de la transacción", example = "0")
    private Integer status;

    @Column(name = "hasFiles")
    @Schema(description = "Indica si la consulta incluyó archivos adjuntos", example = "0")
    private Integer hasFiles;

    @Transient
    private Integer configurationsId;
}
