package pj.gob.pe.consultaia;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class MsJurisiaConsultaiaApplication {

	/**
	 * Segundos que la JVM conserva una conexión HTTP ociosa en su pool antes de descartarla.
	 *
	 * Es la contramedida de RAÍZ frente al error
	 * {@code java.net.SocketException: Unexpected end of file from server}: el proxy institucional
	 * cierra por su cuenta las conexiones ociosas, la JVM no se entera y las reutiliza en la
	 * siguiente llamada, encontrando EOF donde esperaba la línea de estado. Con un valor bajo la
	 * JVM las descarta antes que el proxy, de modo que el error deja de producirse en vez de tener
	 * que reintentarlo ({@code utils/ReintentoUtil} sigue como red de seguridad).
	 *
	 * Solo afecta al tráfico que va por {@code HttpURLConnection} (el SDK de Vertex AI y las
	 * llamadas REST crudas a Google); MySQL, Redis, Kafka y RestClient usan sus propios pools.
	 * El costo es rehacer el handshake TLS en cada fase, despreciable frente a llamadas que duran
	 * minutos. Se puede sobrescribir al arrancar con {@code -Dhttp.keepAlive.time.proxy=...}.
	 */
	private static final String KEEP_ALIVE_SEGUNDOS = "5";

	public static void main(String[] args) {
		// Debe ejecutarse ANTES de que Spring levante nada: la caché de conexiones persistentes de
		// la JVM lee estas propiedades al cargarse su clase, y una vez leídas ya no cambian.
		fijarSiNoDefinida("http.keepAlive.time.proxy", KEEP_ALIVE_SEGUNDOS);
		fijarSiNoDefinida("http.keepAlive.time.server", KEEP_ALIVE_SEGUNDOS);

		SpringApplication.run(MsJurisiaConsultaiaApplication.class, args);
	}

	/** Fija la propiedad de sistema solo si no vino ya definida en la línea de arranque. */
	private static void fijarSiNoDefinida(String clave, String valor) {
		if (System.getProperty(clave) == null) {
			System.setProperty(clave, valor);
		}
	}

}
