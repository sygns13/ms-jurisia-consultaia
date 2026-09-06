package pj.gob.pe.consultaia.configuration;

import lombok.Getter;
import lombok.Setter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

/**
 * Parámetros de los reintentos ante fallos de transporte hacia los servicios externos
 * (Vertex AI / Gemini, Cloud Storage y OpenAI). Ver {@code utils/ReintentoUtil}.
 *
 * Se define como clase separada (y no dentro de {@link ConfigProperties}) porque aplica tanto al
 * tráfico de Google como al de OpenAI, que salen por proxies distintos.
 */
@Configuration
@Getter
@Setter
public class RetryProperties {

    /** Número TOTAL de intentos (1 = sin reintentos). */
    @Value("${resiliencia.reintentos.maxIntentos:3}")
    private Integer maxIntentos;

    /** Espera antes del segundo intento, en milisegundos. */
    @Value("${resiliencia.reintentos.esperaInicialMs:2000}")
    private Long esperaInicialMs;

    /** Factor de crecimiento de la espera entre intentos sucesivos (backoff exponencial). */
    @Value("${resiliencia.reintentos.multiplicador:2.0}")
    private Double multiplicador;

    /** Tope de la espera entre intentos, en milisegundos. */
    @Value("${resiliencia.reintentos.esperaMaximaMs:20000}")
    private Long esperaMaximaMs;

    /** Aleatoriza la espera (±25 %) para no sincronizar reintentos de peticiones concurrentes. */
    @Value("${resiliencia.reintentos.jitter:true}")
    private Boolean jitter;

    /**
     * Reintentar también los vencimientos de plazo (SocketTimeoutException, DEADLINE_EXCEEDED,
     * HTTP 408). Cuesta caro en tiempo: cada reintento vuelve a esperar el timeout completo, por eso
     * está acotado por {@link #presupuestoOperacionMs}.
     */
    @Value("${resiliencia.reintentos.reintentarTimeouts:true}")
    private Boolean reintentarTimeouts;

    /**
     * Presupuesto de tiempo por operación, en milisegundos. No se inicia un nuevo intento si el
     * tiempo ya consumido por la operación lo ha alcanzado. 0 = sin límite.
     */
    @Value("${resiliencia.reintentos.presupuestoOperacionMs:1200000}")
    private Long presupuestoOperacionMs;
}
