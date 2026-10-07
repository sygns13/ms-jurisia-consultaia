package pj.gob.pe.consultaia.model.entities;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnore;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Registro de cada llamada a Gemini del flujo de generación de documentos por plantilla completa
 * (una fila por documento generado: corrección de párrafos + redacción de bloques en una sola
 * llamada). No funciona como caché: los datos del expediente pueden cambiar entre generaciones.
 */
@Schema(description = "Entidad que representa la tabla GeminiDocumentoPlantilla")
@Entity
@Table(name = "GeminiDocumentoPlantilla")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class GeminiDocumentoPlantilla {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Schema(description = "ID único del registro", example = "1")
    private Long id;

    @Column(name = "sessionUID", length = 50, nullable = false)
    @Schema(description = "UUID de la generación (lo asigna ms-jurisia-judicial)")
    private String sessionUID;

    @Column(name = "nUnico")
    @Schema(description = "ID del expediente")
    private Long nUnico;

    @Column(name = "idDocumento")
    private Long idDocumento;

    @Column(name = "idPlantilla")
    private Long idPlantilla;

    @Column(name = "codigoPlantilla", length = 50)
    private String codigoPlantilla;

    @Column(name = "versionPlantilla")
    private Integer versionPlantilla;

    @Column(name = "userId")
    private Long userId;

    @Column(name = "model", length = 50)
    private String model;

    @Column(name = "roleSystem", columnDefinition = "TEXT")
    private String roleSystem;

    @Column(name = "temperature", precision = 3, scale = 1)
    private BigDecimal temperature;

    @Column(name = "request", columnDefinition = "MEDIUMTEXT")
    @Schema(description = "Prompt enviado a Gemini")
    private String request;

    @Column(name = "response", columnDefinition = "MEDIUMTEXT")
    @Schema(description = "Respuesta cruda de Gemini (JSON) o el detalle del error")
    private String response;

    @Column(name = "parrafosEnviados")
    private Integer parrafosEnviados;

    @Column(name = "parrafosCorregidos")
    private Integer parrafosCorregidos;

    @Column(name = "bloquesSolicitados")
    private Integer bloquesSolicitados;

    @Column(name = "bloquesGenerados")
    private Integer bloquesGenerados;

    @Column(name = "promptTokens")
    private Integer promptTokens;

    @Column(name = "candidatesTokens")
    private Integer candidatesTokens;

    @Column(name = "thoughtsTokens")
    private Integer thoughtsTokens;

    @Column(name = "cachedTokens")
    private Integer cachedTokens;

    @Column(name = "totalTokens")
    private Integer totalTokens;

    @Column(name = "finishReason", length = 50)
    private String finishReason;

    @Column(name = "fechaSend")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime fechaSend;

    @Column(name = "fechaResponse")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime fechaResponse;

    @Column(name = "timeSeconds")
    private Double timeSeconds;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "ConfigurationsId", nullable = false)
    private Configurations configurations;

    @Column(name = "status")
    @Schema(description = "0 iniciado, 1 exitoso, 2 error")
    private Integer status;
}
