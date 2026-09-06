package pj.gob.pe.consultaia.exception;

import java.io.IOException;

/**
 * Error HTTP de una llamada REST cruda hacia Google (Vertex AI, Vector Search, Cloud Storage),
 * conservando el código de estado.
 *
 * Existe para que {@code ReintentoUtil} pueda decidir si el fallo es reintentable a partir del
 * código (5xx / 408 sí, 4xx no) en vez de inspeccionar el texto del mensaje. El mensaje se mantiene
 * idéntico al que se lanzaba antes, y sigue siendo una {@link IOException}, por lo que las firmas y
 * el manejo de errores existentes no cambian.
 */
public class HttpEstadoException extends IOException {

    private final int estado;

    public HttpEstadoException(int estado, String mensaje) {
        super(mensaje);
        this.estado = estado;
    }

    public int getEstado() {
        return estado;
    }
}
