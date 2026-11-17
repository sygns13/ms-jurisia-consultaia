package pj.gob.pe.consultaia.service.externals.impl;

import com.google.api.client.http.javanet.NetHttpTransport;
import lombok.RequiredArgsConstructor;
import org.openjdk.nashorn.api.scripting.AbstractJSObject;
import org.openjdk.nashorn.api.scripting.NashornScriptEngineFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import pj.gob.pe.consultaia.configuration.ConfigProperties;
import pj.gob.pe.consultaia.service.externals.ProxyPacService;

import javax.script.Invocable;
import javax.script.ScriptEngine;
import java.io.IOException;
import java.io.InputStream;
import java.net.DatagramSocket;
import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Resuelve el proxy de salida hacia Google evaluando el PAC institucional, igual que lo haría un
 * navegador. Ver {@link ProxyPacService}.
 *
 * MOTIVO: {@code proxypac.pj.gob.pe:9001} no es un proxy (responde 405 al CONNECT); solo publica el
 * PAC. El PAC reparte *.googleapis.com entre 20 nodos 172.18.3.x:3128 según el último octeto de la IP
 * del cliente, y redes puede cambiar ese reparto sin aviso, por eso no se fija un host en properties.
 *
 * El script se descarga al arrancar y cada {@code recargaMinutos}; si una recarga falla se mantiene
 * el último PAC válido. Mientras no haya ninguno se usa el proxy de respaldo. El resultado se cachea
 * por host hasta la siguiente recarga, así que la evaluación cuesta una sola vez por dominio.
 *
 * El PAC se ejecuta en Nashorn con {@code --no-java} y un filtro que niega toda clase Java: es código
 * descargado de la red y no debe poder tocar la JVM.
 */
@Service
@RequiredArgsConstructor
public class ProxyPacServiceImpl implements ProxyPacService {

    private static final Logger logger = LoggerFactory.getLogger(ProxyPacServiceImpl.class);

    private static final int TIMEOUT_DESCARGA_MS = 10_000;

    /** Tras un fallo de descarga se espera 1 min antes de reintentar, para no castigar cada conexión. */
    private static final long ESPERA_TRAS_FALLO_MS = 60_000;

    /** Host representativo con el que se registra en el log el proxy elegido al cargar el PAC. */
    private static final String HOST_REFERENCIA = "aiplatform.googleapis.com";

    /**
     * Funciones auxiliares que el navegador pone a disposición del PAC (las que usa el PAC del PJ más
     * las de uso habitual). {@code myIpAddress} lee la IP inyectada y {@code dnsResolve} la provee Java.
     */
    private static final String FUNCIONES_PAC = """
            function dnsDomainIs(host, domain) {
                return host.length >= domain.length && host.substring(host.length - domain.length) == domain;
            }
            function dnsDomainLevels(host) { return host.split('.').length - 1; }
            function isPlainHostName(host) { return host.search('(\\\\.)|:') == -1; }
            function localHostOrDomainIs(host, hostdom) {
                return host == hostdom || hostdom.lastIndexOf(host + '.', 0) == 0;
            }
            function isResolvable(host) { return dnsResolve(host) != null; }
            function myIpAddress() { return __ipLocal; }
            function shExpMatch(str, pattern) {
                pattern = pattern.replace(/\\./g, '\\\\.').replace(/\\*/g, '.*').replace(/\\?/g, '.');
                return new RegExp('^' + pattern + '$').test(str);
            }
            function __convertirIp(ip) {
                var b = ip.split('.');
                return ((b[0] & 0xff) << 24) | ((b[1] & 0xff) << 16) | ((b[2] & 0xff) << 8) | (b[3] & 0xff);
            }
            function isInNet(ipaddr, pattern, maskstr) {
                if (!/^\\d+\\.\\d+\\.\\d+\\.\\d+$/.test(ipaddr)) {
                    ipaddr = dnsResolve(ipaddr);
                    if (ipaddr == null) return false;
                }
                var mask = __convertirIp(maskstr);
                return (__convertirIp(ipaddr) & mask) == (__convertirIp(pattern) & mask);
            }
            """;

    private final ConfigProperties properties;

    // Estado del PAC: se lee y modifica siempre dentro de métodos synchronized (el ScriptEngine no
    // es seguro para hilos y la evaluación es rara gracias a la caché por host).
    private ScriptEngine motorPac;
    private long proximaRecarga;
    private final Map<String, Proxy> proxyPorHost = new HashMap<>();

    @EventListener(ApplicationReadyEvent.class)
    public void inicializar() {
        if (!Boolean.TRUE.equals(properties.getProxyGoogleEnabled())) {
            logger.info("[Proxy PAC] Deshabilitado (sij.proxy.google.enabled=false): Google sale sin proxy");
            return;
        }
        // Carga temprana: el primer usuario no paga la descarga y un PAC inaccesible se ve en el log.
        resolverPorPac("https", HOST_REFERENCIA);
    }

    @Override
    public NetHttpTransport crearTransporteGoogle() {
        return new NetHttpTransport.Builder()
                .setConnectionFactory(url -> (HttpURLConnection) url.openConnection(resolverProxy(url)))
                .build();
    }

    @Override
    public Proxy resolverProxy(URL url) {
        String host = url.getHost().toLowerCase(Locale.ROOT);

        if (Boolean.TRUE.equals(properties.getProxyVectorSearchEnabled())
                && host.endsWith(properties.getProxyVectorSearchDominio())) {
            return new Proxy(Proxy.Type.HTTP,
                    new InetSocketAddress(properties.getProxyVectorSearchHost(), properties.getProxyVectorSearchPort()));
        }
        if (!Boolean.TRUE.equals(properties.getProxyGoogleEnabled())) {
            return Proxy.NO_PROXY;
        }
        return resolverPorPac(url.getProtocol(), host);
    }

    // ====================================================================
    // Evaluación del PAC
    // ====================================================================
    private synchronized Proxy resolverPorPac(String protocolo, String host) {
        recargarSiCorresponde();
        if (motorPac == null) {
            return proxyRespaldo();
        }

        Proxy cacheado = proxyPorHost.get(host);
        if (cacheado != null) {
            return cacheado;
        }

        Proxy proxy;
        try {
            // Como los navegadores, al PAC solo se le pasa esquema y host: la ruta puede llevar datos.
            Object resultado = ((Invocable) motorPac).invokeFunction(
                    "FindProxyForURL", protocolo + "://" + host + "/", host);
            proxy = interpretar(String.valueOf(resultado));
            logger.info("[Proxy PAC] {} -> {} (PAC: \"{}\")", host, describir(proxy), resultado);
        } catch (Exception e) {
            proxy = proxyRespaldo();
            logger.warn("[Proxy PAC] No se pudo evaluar el PAC para {}: {}. Se usa el respaldo {}",
                    host, e.getMessage(), describir(proxy));
        }
        proxyPorHost.put(host, proxy);
        return proxy;
    }

    private void recargarSiCorresponde() {
        long ahora = System.currentTimeMillis();
        if (ahora < proximaRecarga) {
            return;
        }

        String pacUrl = properties.getProxyGooglePacUrl();
        try {
            String script = descargarPac(pacUrl);
            String ipLocal = detectarIpLocal();
            motorPac = crearMotor(script, ipLocal);
            proxyPorHost.clear();
            proximaRecarga = ahora + properties.getProxyGoogleRecargaMinutos() * 60_000L;
            logger.info("[Proxy PAC] PAC cargado desde {} ({} bytes), IP local para myIpAddress(): {}",
                    pacUrl, script.length(), ipLocal);
        } catch (Exception e) {
            proximaRecarga = ahora + ESPERA_TRAS_FALLO_MS;
            if (motorPac != null) {
                logger.warn("[Proxy PAC] No se pudo recargar el PAC desde {}: {}. Se mantiene el último válido",
                        pacUrl, e.getMessage());
            } else {
                logger.error("[Proxy PAC] No se pudo cargar el PAC desde {}: {}. Se usa el respaldo {}",
                        pacUrl, e.getMessage(), describir(proxyRespaldo()));
            }
        }
    }

    private String descargarPac(String pacUrl) throws IOException {
        // El servidor PAC es interno: se consulta directo, nunca a través de un proxy.
        HttpURLConnection conexion = (HttpURLConnection) URI.create(pacUrl).toURL().openConnection(Proxy.NO_PROXY);
        conexion.setConnectTimeout(TIMEOUT_DESCARGA_MS);
        conexion.setReadTimeout(TIMEOUT_DESCARGA_MS);
        try {
            int status = conexion.getResponseCode();
            if (status != HttpURLConnection.HTTP_OK) {
                throw new IOException("HTTP " + status);
            }
            try (InputStream in = conexion.getInputStream()) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        } finally {
            conexion.disconnect();
        }
    }

    private ScriptEngine crearMotor(String script, String ipLocal) throws Exception {
        ScriptEngine motor = new NashornScriptEngineFactory().getScriptEngine(
                new String[]{"--no-java"}, ProxyPacServiceImpl.class.getClassLoader(), nombreClase -> false);
        motor.put("__ipLocal", ipLocal);
        motor.put("dnsResolve", new DnsResolve());
        motor.eval(FUNCIONES_PAC);
        motor.eval(script);
        return motor;
    }

    /**
     * IP que el PAC usa para elegir el nodo. Si no está configurada, es la de la interfaz por la que
     * sale el tráfico hacia el servidor PAC (un UDP connect no envía nada, solo resuelve la ruta).
     */
    private String detectarIpLocal() {
        String configurada = properties.getProxyGoogleIpLocal();
        if (configurada != null && !configurada.isBlank()) {
            return configurada.trim();
        }
        try (DatagramSocket socket = new DatagramSocket()) {
            URL pac = URI.create(properties.getProxyGooglePacUrl()).toURL();
            int puerto = pac.getPort() > 0 ? pac.getPort() : pac.getDefaultPort();
            socket.connect(InetAddress.getByName(pac.getHost()), puerto);
            InetAddress local = socket.getLocalAddress();
            if (local instanceof Inet4Address && !local.isAnyLocalAddress()) {
                return local.getHostAddress();
            }
        } catch (IOException e) {
            logger.warn("[Proxy PAC] No se pudo detectar la IP local por la ruta al PAC: {}", e.getMessage());
        }
        try {
            return InetAddress.getLocalHost().getHostAddress();
        } catch (IOException e) {
            return "127.0.0.1";
        }
    }

    /**
     * Interpreta la respuesta del PAC ("PROXY h:p; SOCKS h:p; DIRECT"), tomando la primera entrada
     * soportada. Sin ninguna soportada se usa el respaldo.
     */
    private Proxy interpretar(String resultado) {
        for (String entrada : resultado.split(";")) {
            String[] partes = entrada.trim().split("\\s+");
            String tipo = partes[0].toUpperCase(Locale.ROOT);
            if ("DIRECT".equals(tipo)) {
                return Proxy.NO_PROXY;
            }
            if (partes.length < 2) {
                continue;
            }
            Proxy.Type tipoProxy = switch (tipo) {
                case "PROXY", "HTTP" -> Proxy.Type.HTTP;
                case "SOCKS", "SOCKS4", "SOCKS5" -> Proxy.Type.SOCKS;
                default -> null; // HTTPS (proxy con TLS) no lo soporta HttpURLConnection
            };
            int dosPuntos = partes[1].lastIndexOf(':');
            if (tipoProxy == null || dosPuntos < 0) {
                continue;
            }
            String host = partes[1].substring(0, dosPuntos);
            int puerto = Integer.parseInt(partes[1].substring(dosPuntos + 1));
            return new Proxy(tipoProxy, new InetSocketAddress(host, puerto));
        }
        return proxyRespaldo();
    }

    private Proxy proxyRespaldo() {
        return new Proxy(Proxy.Type.HTTP,
                new InetSocketAddress(properties.getProxyGoogleRespaldoHost(), properties.getProxyGoogleRespaldoPort()));
    }

    private String describir(Proxy proxy) {
        return proxy.type() == Proxy.Type.DIRECT ? "DIRECT" : proxy.type() + " " + proxy.address();
    }

    /** {@code dnsResolve(host)} del PAC: IPv4 del host o {@code null} si no resuelve. */
    private static final class DnsResolve extends AbstractJSObject {
        @Override
        public boolean isFunction() {
            return true;
        }

        @Override
        public Object call(Object thiz, Object... args) {
            if (args.length == 0 || args[0] == null) {
                return null;
            }
            try {
                for (InetAddress direccion : InetAddress.getAllByName(String.valueOf(args[0]))) {
                    if (direccion instanceof Inet4Address) {
                        return direccion.getHostAddress();
                    }
                }
            } catch (IOException e) {
                // Host no resoluble: el PAC lo trata como null.
            }
            return null;
        }
    }
}
