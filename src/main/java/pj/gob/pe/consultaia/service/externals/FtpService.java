package pj.gob.pe.consultaia.service.externals;

import java.util.List;

public interface FtpService {

    byte[] descargarArchivo(String rutaCompleta, String nombreArchivo) throws Exception;

    /**
     * Descarga varios archivos de una misma ruta FTP reutilizando una sola conexión,
     * respetando el orden de la lista recibida.
     */
    List<byte[]> descargarArchivos(String rutaCompleta, List<String> nombresArchivo) throws Exception;
}
