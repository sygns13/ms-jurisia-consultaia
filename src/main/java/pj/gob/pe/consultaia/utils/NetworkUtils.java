package pj.gob.pe.consultaia.utils;


import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;
import java.net.NetworkInterface;

public class NetworkUtils {

    private static final Logger logger = LoggerFactory.getLogger(NetworkUtils.class);

    public static String getMacAddress(String ip) {
        try {
            InetAddress address = InetAddress.getByName(ip);
            NetworkInterface network = NetworkInterface.getByInetAddress(address);
            if (network == null){
                logger.debug("No se pudo obtener la MAC");
                return "";
            }

            byte[] mac = network.getHardwareAddress();
            if (mac == null)
            {
                logger.debug("MAC no disponible");
                return "";
            }

            StringBuilder macAddress = new StringBuilder();
            for (byte b : mac) {
                macAddress.append(String.format("%02X:", b));
            }
            return macAddress.substring(0, macAddress.length() - 1); // Elimina el último ":"
        } catch (Exception e) {
            logger.error("Error obteniendo MAC: {}", e.getMessage());
            return "";
        }
    }
}
