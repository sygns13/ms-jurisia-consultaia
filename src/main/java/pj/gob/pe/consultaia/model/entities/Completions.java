package pj.gob.pe.consultaia.model.entities;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.persistence.*;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Schema(description = "Entidad que representa la tabla Completions")
@Entity
@Table(name = "Completions")
@Data // Lombok: Genera getters, setters, toString, equals, y hashCode
@NoArgsConstructor // Lombok: Constructor sin argumentos
@AllArgsConstructor // Lombok: Constructor con todos los argumentos
public class Completions {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Schema(description = "ID único de la finalización", example = "1")
    private Long id;

    @Column(name = "userId")
    @Schema(description = "ID del usuario", example = "12345")
    private Long userId;

    @Column(name = "model", length = 50, nullable = false)
    @Schema(description = "Modelo utilizado", example = "GPT-4")
    private String model;

    @Column(name = "roleSystem", columnDefinition = "TEXT")
    @Schema(description = "Rol del sistema", example = "Assistant")
    private String roleSystem;

    @Column(name = "roleUser", columnDefinition = "TEXT")
    @Schema(description = "Rol del usuario", example = "User")
    private String roleUser;

    @Column(name = "temperature", precision = 2, scale = 1)
    @DecimalMin(value = "0.0", message = "El valor debe ser mayor o igual a 0.0")
    @DecimalMax(value = "1.0", message = "El valor debe ser menor o igual a 1.0")
    private BigDecimal temperature;

    @Column(name = "fechaSend")
    @JsonFormat(pattern="yyyy-MM-dd HH:mm:ss")
    @Schema(description = "Fecha y hora de envío", example = "2023-10-01T12:00:00")
    private LocalDateTime fechaSend;

    @Column(name = "fechaResponse")
    @JsonFormat(pattern="yyyy-MM-dd HH:mm:ss")
    @Schema(description = "Fecha y hora de respuesta", example = "2023-10-01T12:05:00")
    private LocalDateTime fechaResponse;

    @Column(name = "idGpt", length = 100)
    @Schema(description = "ID de GPT", example = "chatcmpl-12345")
    private String idGpt;

    @Column(name = "object", length = 100)
    @Schema(description = "Objeto de la respuesta", example = "chat.completion")
    private String object;

    @Column(name = "created")
    @Schema(description = "Timestamp de creación", example = "1696166400")
    private Long created;

    @Column(name = "modelResponse", length = 100)
    @Schema(description = "Modelo de respuesta", example = "GPT-4")
    private String modelResponse;

    @Column(name = "roleResponse", length = 100)
    @Schema(description = "Rol de la respuesta", example = "Assistant")
    private String roleResponse;

    @Column(name = "roleContent", columnDefinition = "TEXT")
    @Schema(description = "Contenido del rol", example = "Hello, how can I help you?")
    private String roleContent;

    @Column(name = "refusal", columnDefinition = "TEXT")
    @Schema(description = "Razón de rechazo", example = "Content policy violation")
    private String refusal;

    @Column(name = "logprobs")
    @Schema(description = "Log probabilities", example = "12345")
    private Integer logprobs;

    @Column(name = "finishReason", columnDefinition = "TEXT")
    @Schema(description = "Razón de finalización", example = "stop")
    private String finishReason;

    @Column(name = "promptTokens")
    @Schema(description = "Tokens del prompt", example = "100")
    private Integer promptTokens;

    @Column(name = "completionTokens")
    @Schema(description = "Tokens de la finalización", example = "200")
    private Integer completionTokens;

    @Column(name = "totalTokens")
    @Schema(description = "Total de tokens", example = "300")
    private Integer totalTokens;

    @Column(name = "cachedTokens")
    @Schema(description = "Tokens cacheados", example = "50")
    private Integer cachedTokens;

    @Column(name = "audioTokens")
    @Schema(description = "Tokens de audio", example = "10")
    private Integer audioTokens;

    @Column(name = "CompletionReasoningTokens")
    @Schema(description = "Tokens de razonamiento", example = "20")
    private Integer completionReasoningTokens;

    @Column(name = "CompletionAudioTokens")
    @Schema(description = "Tokens de audio de la finalización", example = "5")
    private Integer completionAudioTokens;

    @Column(name = "CompletionAceptedTokens")
    @Schema(description = "Tokens aceptados", example = "150")
    private Integer completionAceptedTokens;

    @Column(name = "CompletionRejectedTokens")
    @Schema(description = "Tokens rechazados", example = "50")
    private Integer completionRejectedTokens;

    @Column(name = "serviceTier", length = 250)
    @Schema(description = "Nivel de servicio", example = "Premium")
    private String serviceTier;

    @Column(name = "systemFingerprint", length = 250)
    @Schema(description = "Huella del sistema", example = "abc123")
    private String systemFingerprint;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "ConfigurationsId", nullable = false)
    @Schema(description = "Configuración asociada")
    private Configurations configurations;

    @Column(name = "sessionUID", length = 50, nullable = false)
    @Schema(description = "ID único de la sesión", example = "session-12345")
    private String sessionUID;

    @Column(name = "status")
    @Schema(description = "status de la transaccion", example = "0")
    private Integer status;
}
