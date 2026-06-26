package pj.gob.pe.consultaia.model.beans;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import pj.gob.pe.consultaia.model.entities.DemandasSentencias;
import pj.gob.pe.consultaia.utils.beans.responses.UserLogin;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Objeto de transporte (DTO) publicado en Kafka (tópico judicial-metrics-sentencias) cuando se
 * genera la sentencia de una demanda. Contiene todos los campos de {@link DemandasSentencias}
 * (excepto la relación Configurations, que se aplana a {@code configurationsId}) más los datos del
 * usuario que generó la sentencia, obtenidos de la sesión ({@link UserLogin}).
 */
@Schema(description = "Sentencia generada enriquecida con datos del usuario, para publicación en Kafka")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DemandasSentenciasToKafka {

    // ===== Campos de DemandasSentencias =====
    private Long id;
    private Long nUnico;
    private Long userId;
    private String model;
    private String roleSystem;
    private BigDecimal temperature;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime fechaSend;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime fechaResponse;

    private String response;
    private Double timeSeconds;
    private Integer configurationsId;
    private Integer status;
    private String anio;
    private String expNro;
    private String tipoExpediente;
    private String rutaCompleta;
    private String xformato;
    private String cclave;
    private String xnomInstancia;
    private String cubicacion;
    private String cinstancia;
    private String xdescEstado;
    private String cusuario;
    private String cmateria;
    private String cespecialidad;
    private String xdescUbicacion;
    private String xnombreArchivo;
    private String nincidente;
    private String xrutaArchivo;
    private String xdescMateria;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime finicio;

    private String xdescJuez;
    private String xdescEspecialista;
    private String xdescDemandado;
    private String xdescDemandante;

    // ===== Datos del usuario (sesión / UserLogin) =====
    private Long idUser;
    private Integer tipoDocumento;
    private String documento;
    private String apellidos;
    private String nombres;
    private String username;
    private String email;
    private Integer genero;
    private String telefono;
    private String direccion;
    private Integer activo;
    private Long idDependencia;
    private String nombreDependencia;
    private String codigoDependencia;
    private String siglaDependencia;
    private String cargo;
    private Long idTipoUser;
    private String tipoUser;

    /**
     * Construye el objeto a publicar a partir de la sentencia generada y el usuario de la sesión.
     * La relación Configurations se aplana a {@code configurationsId}.
     */
    public static DemandasSentenciasToKafka from(DemandasSentencias d, UserLogin u) {
        DemandasSentenciasToKafka k = new DemandasSentenciasToKafka();

        k.setId(d.getId());
        k.setNUnico(d.getNUnico());
        k.setUserId(d.getUserId());
        k.setModel(d.getModel());
        k.setRoleSystem(d.getRoleSystem());
        k.setTemperature(d.getTemperature());
        k.setFechaSend(d.getFechaSend());
        k.setFechaResponse(d.getFechaResponse());
        k.setResponse(d.getResponse());
        k.setTimeSeconds(d.getTimeSeconds());
        k.setConfigurationsId(d.getConfigurations() != null ? d.getConfigurations().getId() : d.getConfigurationsId());
        k.setStatus(d.getStatus());
        k.setAnio(d.getAnio());
        k.setExpNro(d.getExpNro());
        k.setTipoExpediente(d.getTipoExpediente());
        k.setRutaCompleta(d.getRutaCompleta());
        k.setXformato(d.getXformato());
        k.setCclave(d.getCclave());
        k.setXnomInstancia(d.getXnomInstancia());
        k.setCubicacion(d.getCubicacion());
        k.setCinstancia(d.getCinstancia());
        k.setXdescEstado(d.getXdescEstado());
        k.setCusuario(d.getCusuario());
        k.setCmateria(d.getCmateria());
        k.setCespecialidad(d.getCespecialidad());
        k.setXdescUbicacion(d.getXdescUbicacion());
        k.setXnombreArchivo(d.getXnombreArchivo());
        k.setNincidente(d.getNincidente());
        k.setXrutaArchivo(d.getXrutaArchivo());
        k.setXdescMateria(d.getXdescMateria());
        k.setFinicio(d.getFinicio());
        k.setXdescJuez(d.getXdescJuez());
        k.setXdescEspecialista(d.getXdescEspecialista());
        k.setXdescDemandado(d.getXdescDemandado());
        k.setXdescDemandante(d.getXdescDemandante());

        if (u != null) {
            k.setIdUser(u.getIdUser());
            k.setTipoDocumento(u.getTipoDocumento());
            k.setDocumento(u.getDocumento());
            k.setApellidos(u.getApellidos());
            k.setNombres(u.getNombres());
            k.setUsername(u.getUsername());
            k.setEmail(u.getEmail());
            k.setGenero(u.getGenero());
            k.setTelefono(u.getTelefono());
            k.setDireccion(u.getDireccion());
            k.setActivo(u.getActivo());
            k.setIdDependencia(u.getIdDependencia());
            k.setNombreDependencia(u.getNombreDependencia());
            k.setCodigoDependencia(u.getCodigoDependencia());
            k.setSiglaDependencia(u.getSiglaDependencia());
            k.setCargo(u.getCargo());
            k.setIdTipoUser(u.getIdTipoUser());
            k.setTipoUser(u.getTipoUser());
        }

        return k;
    }
}
