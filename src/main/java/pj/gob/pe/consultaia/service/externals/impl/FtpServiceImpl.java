package pj.gob.pe.consultaia.service.externals.impl;

import org.apache.commons.net.ftp.FTP;
import org.apache.commons.net.ftp.FTPClient;
import org.apache.commons.net.ftp.FTPReply;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import pj.gob.pe.consultaia.exception.ValidationServiceException;
import pj.gob.pe.consultaia.service.externals.FtpService;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

@Service
public class FtpServiceImpl implements FtpService {

    private static final Logger logger = LoggerFactory.getLogger(FtpServiceImpl.class);

    @Override
    public byte[] descargarArchivo(String rutaCompleta, String nombreArchivo) throws Exception {

        if (nombreArchivo == null || nombreArchivo.isEmpty()) {
            throw new ValidationServiceException("El nombre del archivo (archivos) es requerido");
        }

        return descargarArchivos(rutaCompleta, List.of(nombreArchivo)).get(0);
    }

    @Override
    public List<byte[]> descargarArchivos(String rutaCompleta, List<String> nombresArchivo) throws Exception {

        if (rutaCompleta == null || rutaCompleta.isEmpty()) {
            throw new ValidationServiceException("La ruta FTP (rutaCompleta) es requerida");
        }
        if (nombresArchivo == null || nombresArchivo.isEmpty()) {
            throw new ValidationServiceException("Se requiere al menos un nombre de archivo (archivos)");
        }

        FtpConnectionInfo info = parseFtpUri(rutaCompleta);

        FTPClient ftpClient = new FTPClient();

        try {
            ftpClient.setConnectTimeout(30000);
            ftpClient.setDefaultTimeout(60000);
            ftpClient.connect(info.host, info.port);

            int replyCode = ftpClient.getReplyCode();
            if (!FTPReply.isPositiveCompletion(replyCode)) {
                ftpClient.disconnect();
                throw new IOException("El servidor FTP rechazó la conexión. Código: " + replyCode);
            }

            boolean login = ftpClient.login(info.user, info.password);
            if (!login) {
                throw new IOException("Credenciales FTP inválidas para usuario: " + info.user);
            }

            ftpClient.enterLocalPassiveMode();
            ftpClient.setFileType(FTP.BINARY_FILE_TYPE);
            ftpClient.setSoTimeout(60000);

            if (info.path != null && !info.path.isEmpty()) {
                boolean changed = ftpClient.changeWorkingDirectory(info.path);
                if (!changed) {
                    throw new IOException("No se pudo acceder al directorio FTP: " + info.path);
                }
            }

            List<byte[]> archivos = new ArrayList<>(nombresArchivo.size());

            for (String nombreArchivo : nombresArchivo) {

                if (nombreArchivo == null || nombreArchivo.isEmpty()) {
                    throw new ValidationServiceException("La lista de archivos contiene un nombre vacío o nulo");
                }

                ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
                boolean retrieved = ftpClient.retrieveFile(nombreArchivo, outputStream);

                if (!retrieved) {
                    throw new IOException("No se pudo descargar el archivo: " + nombreArchivo
                            + " (replyCode=" + ftpClient.getReplyCode() + ")");
                }

                byte[] bytes = outputStream.toByteArray();

                if (bytes.length == 0) {
                    throw new IOException("El archivo descargado está vacío: " + nombreArchivo);
                }

                archivos.add(bytes);
            }

            return archivos;

        } finally {
            try {
                if (ftpClient.isConnected()) {
                    ftpClient.logout();
                    ftpClient.disconnect();
                }
            } catch (IOException ex) {
                logger.warn("Error cerrando conexión FTP: {}", ex.getMessage());
            }
        }
    }

    private FtpConnectionInfo parseFtpUri(String rutaCompleta) throws Exception {
        URI uri = new URI(rutaCompleta);

        String scheme = uri.getScheme();
        if (scheme == null || !"ftp".equalsIgnoreCase(scheme)) {
            throw new ValidationServiceException("La ruta no es un URI FTP válido: " + rutaCompleta);
        }

        String host = uri.getHost();
        int port = uri.getPort() == -1 ? 21 : uri.getPort();

        String userInfo = uri.getUserInfo();
        String user = "anonymous";
        String password = "";
        if (userInfo != null && !userInfo.isEmpty()) {
            int idx = userInfo.indexOf(':');
            if (idx >= 0) {
                user = URLDecoder.decode(userInfo.substring(0, idx), StandardCharsets.UTF_8);
                password = URLDecoder.decode(userInfo.substring(idx + 1), StandardCharsets.UTF_8);
            } else {
                user = URLDecoder.decode(userInfo, StandardCharsets.UTF_8);
            }
        }

        String path = uri.getPath();
        if (path == null) path = "";

        return new FtpConnectionInfo(host, port, user, password, path);
    }

    private static class FtpConnectionInfo {
        final String host;
        final int port;
        final String user;
        final String password;
        final String path;

        FtpConnectionInfo(String host, int port, String user, String password, String path) {
            this.host = host;
            this.port = port;
            this.user = user;
            this.password = password;
            this.path = path;
        }
    }
}
