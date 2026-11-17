package pj.gob.pe.consultaia.service.externals;

import com.google.api.client.http.javanet.NetHttpTransport;

import java.net.Proxy;
import java.net.URL;

/**
 * Punto único de salida por proxy para todo el tráfico hacia Google (Vertex AI / Gemini,
 * embeddings, Vector Search, Cloud Storage y el refresh del token OAuth).
 *
 * Con {@code sij.proxy.google.modo=pac} el proxy lo decide, host por host, el PAC institucional
 * ({@code sij.proxy.google.pacUrl}); con {@code modo=fijo} todo sale por {@code sij.proxy.google.fijo}.
 * En ambos modos el dominio de Vector Search ({@code sij.proxy.vectorSearch}), que los proxys de Google
 * del PAC rechazan, sale por su propio proxy fijo.
 */
public interface ProxyPacService {

    /**
     * Transporte HTTP para las llamadas a Google. El proxy se resuelve en cada conexión según el
     * host de destino, de modo que un mismo transporte sirve para Vertex, GCS y Vector Search.
     */
    NetHttpTransport crearTransporteGoogle();

    /**
     * Proxy que corresponde a la URL: el de Vector Search para su dominio; para el resto, el fijo en
     * modo fijo, o el que indique el PAC (o el de respaldo si el PAC aún no se pudo cargar).
     */
    Proxy resolverProxy(URL url);
}
