package pj.gob.pe.consultaia.exception;

public class ValidationServiceException extends RuntimeException{

    private static final long serialVersionUID = -2783311501035261956L;

    public ValidationServiceException(String mensaje) {
        super(mensaje);
    }
}
