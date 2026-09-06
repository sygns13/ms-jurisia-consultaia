package pj.gob.pe.consultaia.utils;

import com.google.api.client.http.HttpResponseException;
import com.google.api.gax.rpc.ApiException;
import com.google.api.gax.rpc.StatusCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import pj.gob.pe.consultaia.configuration.RetryProperties;
import pj.gob.pe.consultaia.exception.HttpEstadoException;

import javax.net.ssl.SSLException;
import java.io.EOFException;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.ProtocolException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Reintentos con espera exponencial para las llamadas de red a servicios externos.
 *
 * MOTIVO: el egress hacia Internet sale por el proxy institucional, y la pila HTTP que usan el SDK
 * de Vertex AI y las llamadas REST crudas ({@code NetHttpTransport} sobre {@code HttpURLConnection})
 * mantiene un pool de conexiones persistentes ESTÁTICO y global a la JVM. Cuando el proxy cierra por
 * su cuenta una conexión ociosa, la JVM no se entera y la reutiliza en la siguiente llamada; al ir a
 * leer la línea de estado encuentra EOF y lanza
 * {@code java.net.SocketException: Unexpected end of file from server}. GAX lo envuelve como
 * {@code UnknownException}, código {@code UNKNOWN}, que NO figura en los {@code retryableCodes} por
 * defecto de {@code generateContent}: por eso el {@code RetrySettings} de los services no reintenta
 * nada, solo ajusta plazos. Además {@code HttpURLConnection} reintenta por su cuenta los GET
 * idempotentes pero nunca un POST, y todas las llamadas a Gemini son POST.
 *
 * El segundo intento casi siempre funciona porque el fallo purga del pool la conexión muerta.
 *
 * Los reintentos se aplican POR OPERACIÓN (cada subida a GCS, cada fase del RAG, cada llamada al
 * modelo) y no sobre el flujo completo: así un fallo en la fase final no rehace la subida del PDF
 * ni vuelve a facturar los tokens de las fases previas.
 */
public final class ReintentoUtil {

    private static final Logger logger = LoggerFactory.getLogger(ReintentoUtil.class);

    private ReintentoUtil() {
    }

    /** Operación de red que devuelve un resultado. */
    @FunctionalInterface
    public interface OperacionIO<T> {
        T ejecutar() throws IOException;
    }

    /** Operación de red sin resultado. */
    @FunctionalInterface
    public interface AccionIO {
        void ejecutar() throws IOException;
    }

    /**
     * Ejecuta la operación reintentándola mientras el fallo sea de transporte (o de plazo, si está
     * habilitado), no se agoten los intentos y quede presupuesto de tiempo.
     *
     * @param config    parámetros de reintento
     * @param contexto  descripción corta para el log (ej. "Sentencia Fase 4 (redacción)")
     * @param operacion la llamada de red
     */
    public static <T> T ejecutar(RetryProperties config, String contexto, OperacionIO<T> operacion)
            throws IOException {

        int maxIntentos = Math.max(1, config.getMaxIntentos() != null ? config.getMaxIntentos() : 1);
        long presupuesto = config.getPresupuestoOperacionMs() != null ? config.getPresupuestoOperacionMs() : 0L;
        long inicio = System.nanoTime();
        long espera = Math.max(0L, config.getEsperaInicialMs() != null ? config.getEsperaInicialMs() : 0L);

        for (int intento = 1; ; intento++) {
            try {
                T resultado = operacion.ejecutar();
                if (intento > 1) {
                    logger.info("[Reintento] {} resuelto en el intento {}/{} tras {}s de la operación",
                            contexto, intento, maxIntentos, seg(inicio));
                }
                return resultado;
            } catch (IOException | RuntimeException ex) {
                long transcurridoMs = ms(inicio);

                if (!esReintentable(ex, config)) {
                    logger.error("[Reintento] {} terminó tras {}s con un error NO reintentable ({}): {}",
                            contexto, seg(inicio), ex.getClass().getName(), ex.getMessage());
                    throw ex;
                }
                if (intento >= maxIntentos) {
                    logger.error("[Reintento] {} agotó los {} intentos tras {}s. Último error ({}): {}",
                            contexto, maxIntentos, seg(inicio), ex.getClass().getName(), ex.getMessage(), ex);
                    throw ex;
                }
                long esperaReal = conJitter(Math.min(espera, tope(config)), config);
                if (presupuesto > 0 && transcurridoMs + esperaReal >= presupuesto) {
                    logger.error("[Reintento] {} agotó el presupuesto de {} ms en el intento {}/{} tras {}s. " +
                                    "Último error ({}): {}",
                            contexto, presupuesto, intento, maxIntentos, seg(inicio),
                            ex.getClass().getName(), ex.getMessage(), ex);
                    throw ex;
                }

                // El tiempo transcurrido en el log permite distinguir una conexión muerta reutilizada
                // (falla en segundos) de un corte del proxy durante la generación (falla en minutos).
                logger.warn("[Reintento] {} intento {}/{} sin éxito tras {}s ({}): {}. Reintentando en {} ms",
                        contexto, intento, maxIntentos, seg(inicio),
                        ex.getClass().getSimpleName(), ex.getMessage(), esperaReal);

                dormir(esperaReal, contexto);
                espera = siguiente(espera, config);
            }
        }
    }

    /** Variante para operaciones sin resultado. */
    public static void ejecutarAccion(RetryProperties config, String contexto, AccionIO accion) throws IOException {
        ejecutar(config, contexto, () -> {
            accion.ejecutar();
            return null;
        });
    }

    // ====================================================================
    // Clasificación del error
    // ====================================================================

    /**
     * Determina si el fallo corresponde a una avería del enlace (conexión cerrada, restablecida,
     * TLS caído, DNS momentáneo, 5xx del servidor) y por tanto tiene sentido repetir la llamada.
     * Un 4xx, un error de validación o un fallo de negocio no se reintentan: volverían a fallar.
     *
     * Recorre toda la cadena de causas porque el error real llega envuelto en varias capas
     * (GAX {@code UnknownException} -&gt; {@code IOException} -&gt; {@code SocketException}).
     */
    public static boolean esReintentable(Throwable error, RetryProperties config) {

        boolean timeouts = Boolean.TRUE.equals(config.getReintentarTimeouts());
        Set<Throwable> vistos = Collections.newSetFromMap(new IdentityHashMap<>());

        for (Throwable t = error; t != null && vistos.add(t); t = t.getCause()) {

            // Vencimientos de plazo: solo si están habilitados en properties.
            if (t instanceof SocketTimeoutException || t instanceof InterruptedIOException) {
                if (timeouts) {
                    return true;
                }
                continue;
            }

            // Averías del enlace. ConnectException y NoRouteToHostException extienden SocketException.
            if (t instanceof SocketException
                    || t instanceof EOFException
                    || t instanceof SSLException
                    || t instanceof UnknownHostException
                    || t instanceof ProtocolException) {
                return true;
            }

            if (t instanceof ApiException api) {
                if (api.isRetryable() || esCodigoGaxReintentable(api, timeouts)) {
                    return true;
                }
            }

            if (t instanceof HttpEstadoException he) {
                if (esCodigoHttpReintentable(he.getEstado(), timeouts)) {
                    return true;
                }
            }

            if (t instanceof HttpResponseException hre) {
                if (esCodigoHttpReintentable(hre.getStatusCode(), timeouts)) {
                    return true;
                }
            }

            // Última red: algunas capas pierden el tipo original y solo conservan el texto.
            if (esMensajeDeCorte(t.getMessage())) {
                return true;
            }
        }
        return false;
    }

    private static boolean esCodigoGaxReintentable(ApiException api, boolean timeouts) {
        StatusCode statusCode = api.getStatusCode();
        if (statusCode == null || statusCode.getCode() == null) {
            return false;
        }
        return switch (statusCode.getCode()) {
            // UNKNOWN es el código con el que GAX envuelve "Unexpected end of file from server".
            case UNKNOWN, UNAVAILABLE, INTERNAL, ABORTED -> true;
            case DEADLINE_EXCEEDED -> timeouts;
            default -> false;
        };
    }

    private static boolean esCodigoHttpReintentable(int estado, boolean timeouts) {
        if (estado == 408) {
            return timeouts;
        }
        return estado == 500 || estado == 502 || estado == 503 || estado == 504;
    }

    private static boolean esMensajeDeCorte(String mensaje) {
        if (mensaje == null || mensaje.isBlank()) {
            return false;
        }
        String m = mensaje.toLowerCase(Locale.ROOT);
        return m.contains("unexpected end of file from server")
                || m.contains("unexpected end of stream")
                || m.contains("connection reset")
                || m.contains("connection closed")
                || m.contains("connection abort")
                || m.contains("broken pipe")
                || m.contains("remote host terminated the handshake")
                || m.contains("premature eof")
                || m.contains("goaway");
    }

    // ====================================================================
    // Espera entre intentos
    // ====================================================================
    private static long tope(RetryProperties config) {
        return config.getEsperaMaximaMs() != null ? config.getEsperaMaximaMs() : Long.MAX_VALUE;
    }

    private static long siguiente(long espera, RetryProperties config) {
        double factor = config.getMultiplicador() != null ? config.getMultiplicador() : 1.0d;
        return Math.min((long) (espera * Math.max(1.0d, factor)), tope(config));
    }

    /** Reparte las esperas en ±25 % para no sincronizar los reintentos de peticiones concurrentes. */
    private static long conJitter(long espera, RetryProperties config) {
        if (!Boolean.TRUE.equals(config.getJitter()) || espera <= 0) {
            return Math.max(0L, espera);
        }
        long delta = espera / 4;
        if (delta <= 0) {
            return espera;
        }
        return Math.max(0L, espera - delta + ThreadLocalRandom.current().nextLong(2 * delta + 1));
    }

    private static void dormir(long milisegundos, String contexto) throws IOException {
        if (milisegundos <= 0) {
            return;
        }
        try {
            Thread.sleep(milisegundos);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new IOException("Reintento interrumpido en " + contexto, ie);
        }
    }

    private static long ms(long inicioNano) {
        return (System.nanoTime() - inicioNano) / 1_000_000L;
    }

    private static String seg(long inicioNano) {
        return String.format("%.2f", (System.nanoTime() - inicioNano) / 1_000_000_000.0);
    }
}
