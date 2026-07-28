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

/**
 * Corrección de una sección de plantilla de documento con Gemini (espejo de
 * {@link ExpedienteCompletion} del flujo OpenAI, sin las columnas de tokens). Funciona como
 * caché por (nUnico, templateCode, sectionId): si la sección ya fue corregida dentro de la
 * ventana de reutilización, se devuelve el response guardado sin volver a llamar a la IA.
 */
@Schema(description = "Entidad que representa la tabla GeminiExpedienteChats")
@Entity
@Table(name = "GeminiExpedienteChats")
@Data // Lombok: Genera getters, setters, toString, equals, y hashCode
@NoArgsConstructor // Lombok: Constructor sin argumentos
@AllArgsConstructor // Lombok: Constructor con todos los argumentos
public class GeminiExpedienteChats {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Schema(description = "ID único del registro", example = "1")
    private Long id;

    @Column(name = "nUnico")
    @Schema(description = "ID del expediente", example = "12345")
    private Long nUnico;

    @Column(name = "templateCode", length = 50, nullable = false)
    @Schema(description = "templateCode utilizado", example = "template_auto_01")
    private String templateCode;

    @Column(name = "sectionId")
    @Schema(description = "ID de la Section", example = "12345")
    private Long sectionId;

    @Column(name = "userId")
    @Schema(description = "ID del usuario", example = "12345")
    private Long userId;

    @Column(name = "model", length = 50)
    @Schema(description = "Modelo utilizado", example = "gemini-3.6-flash")
    private String model;

    @Column(name = "roleSystem", columnDefinition = "TEXT")
    @Schema(description = "Instrucción de sistema utilizada")
    private String roleSystem;

    @Column(name = "roleUser", columnDefinition = "TEXT")
    @Schema(description = "Contenido de la sección enviado a la IA")
    private String roleUser;

    @Column(name = "temperature", precision = 3, scale = 1)
    @DecimalMin(value = "0.0", message = "El valor debe ser mayor o igual a 0.0")
    @DecimalMax(value = "1.0", message = "El valor debe ser menor o igual a 1.0")
    private BigDecimal temperature;

    @Column(name = "fechaSend")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    @Schema(description = "Fecha y hora de envío", example = "2026-07-28T12:00:00")
    private LocalDateTime fechaSend;

    @Column(name = "fechaResponse")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    @Schema(description = "Fecha y hora de respuesta", example = "2026-07-28T12:00:05")
    private LocalDateTime fechaResponse;

    @Column(name = "response", columnDefinition = "TEXT")
    @Schema(description = "Texto corregido devuelto por Gemini")
    private String response;

    @Column(name = "timeSeconds")
    @Schema(description = "Tiempo de procesamiento de la sección en segundos", example = "3.21")
    private Double timeSeconds;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "ConfigurationsId", nullable = false)
    @Schema(description = "Configuración asociada")
    private Configurations configurations;

    @Column(name = "sessionUID", length = 50, nullable = false)
    @Schema(description = "UUID de la corrida de procesamiento del documento", example = "session-12345")
    private String sessionUID;

    @Column(name = "status")
    @Schema(description = "Status de la transacción", example = "1")
    private Integer status;

    @Transient
    private Integer configurationsId;
}
