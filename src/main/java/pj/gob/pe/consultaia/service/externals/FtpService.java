package pj.gob.pe.consultaia.service.externals;

public interface FtpService {

    byte[] descargarArchivo(String rutaCompleta, String nombreArchivo) throws Exception;
}
