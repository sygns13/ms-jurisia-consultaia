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

@Schema(description = "Entidad que representa la tabla DemandasCalificadas")
@Entity
@Table(name = "DemandasCalificadas")
@Data // Lombok: Genera getters, setters, toString, equals, y hashCode
@NoArgsConstructor // Lombok: Constructor sin argumentos
@AllArgsConstructor // Lombok: Constructor con todos los argumentos
public class DemandasCalificadas {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Schema(description = "ID único de la demanda calificada", example = "1")
    private Long id;

    @Column(name = "nUnico")
    @Schema(description = "ID del expediente", example = "12345")
    private Long nUnico;

    @Column(name = "userId")
    @Schema(description = "ID del usuario", example = "12345")
    private Long userId;

    @Column(name = "model", length = 50)
    @Schema(description = "Modelo utilizado", example = "gemini-3.1-pro-preview")
    private String model;

    @Column(name = "roleSystem", columnDefinition = "TEXT")
    @Schema(description = "Rol del sistema", example = "Assistant")
    private String roleSystem;

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

    @Column(name = "status")
    @Schema(description = "Status de la transacción", example = "0")
    private Integer status;

    @Column(name = "anio", length = 10)
    @Schema(description = "Año del expediente", example = "2025")
    private String anio;

    @Column(name = "expNro", length = 20)
    @Schema(description = "Número de expediente", example = "00114")
    private String expNro;

    @Column(name = "tipoExpediente", length = 50)
    @Schema(description = "Tipo de expediente")
    private String tipoExpediente;

    @Column(name = "rutaCompleta", length = 100)
    @Schema(description = "Ruta completa del expediente")
    private String rutaCompleta;

    @Column(name = "xformato", length = 50)
    @Schema(description = "Formato del expediente")
    private String xformato;

    @Column(name = "cclave", length = 50)
    @Schema(description = "Clave del expediente")
    private String cclave;

    @Column(name = "xnomInstancia", length = 100)
    @Schema(description = "Nombre de la instancia")
    private String xnomInstancia;

    @Column(name = "cubicacion", length = 20)
    @Schema(description = "Código de ubicación")
    private String cubicacion;

    @Column(name = "cinstancia", length = 20)
    @Schema(description = "Código de instancia")
    private String cinstancia;

    @Column(name = "xdescEstado", length = 50)
    @Schema(description = "Descripción del estado")
    private String xdescEstado;

    @Column(name = "cusuario", length = 50)
    @Schema(description = "Código del usuario")
    private String cusuario;

    @Column(name = "cmateria", length = 20)
    @Schema(description = "Código de materia")
    private String cmateria;

    @Column(name = "cespecialidad", length = 20)
    @Schema(description = "Código de especialidad")
    private String cespecialidad;

    @Column(name = "xdescUbicacion", length = 100)
    @Schema(description = "Descripción de la ubicación")
    private String xdescUbicacion;

    @Column(name = "xnombreArchivo", length = 500)
    @Schema(description = "Nombre(s) del/los archivo(s) PDF que conforman la demanda (separados por '; ')")
    private String xnombreArchivo;

    @Column(name = "nincidente", length = 20)
    @Schema(description = "Número de incidente")
    private String nincidente;

    @Column(name = "xrutaArchivo", length = 100)
    @Schema(description = "Ruta del archivo")
    private String xrutaArchivo;

    @Column(name = "xdescMateria", length = 100)
    @Schema(description = "Descripción de materia")
    private String xdescMateria;

    @Column(name = "finicio")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    @Schema(description = "Fecha de inicio del expediente", example = "2025-01-01T00:00:00")
    private LocalDateTime finicio;

    @Transient
    private Integer configurationsId;
}
