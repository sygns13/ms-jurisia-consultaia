package pj.gob.pe.consultaia.utils;

import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.multipdf.Splitter;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.graphics.image.JPEGFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Acondiciona el PDF de un expediente para que Gemini pueda leerlo.
 *
 * Vertex AI rechaza los PDF que superan los <b>50 MB (52 428 800 bytes)</b> por archivo, tanto si
 * viajan en línea como si se referencian por URI {@code gs://}, y también los que exceden las
 * <b>1000 páginas</b>. Como un expediente se arma uniendo 1..n documentos digitalizados
 * ({@link PdfMergeUtil}), el PDF resultante puede superar ese límite y la llamada falla con
 * "The file size of gs://... exceeds max allowed file size of 52428800 for application/pdf files".
 *
 * La estrategia es escalonada y se detiene en cuanto el documento cabe dentro del límite:
 * <ol>
 *   <li><b>Recompresión de imágenes</b> (por defecto a 150 DPI / JPEG 0.6): un expediente pesado es
 *       casi siempre un escaneo a alta resolución. Se remuestrean únicamente las imágenes más
 *       grandes de lo necesario para la página y solo se reemplazan si el resultado pesa menos que
 *       el original. La capa de texto, los marcadores y la paginación se conservan.</li>
 *   <li><b>Recompresión agresiva</b> (2/3 del DPI y menor calidad) si aún excede.</li>
 *   <li><b>Partición</b> por mitades sucesivas hasta que cada parte entre en el límite de bytes y
 *       de páginas. Las partes se envían como varios documentos en la MISMA petición a Gemini,
 *       en orden, de modo que el modelo sigue viendo el expediente completo.</li>
 * </ol>
 *
 * Ninguna etapa puede romper el flujo: ante cualquier error de PDFBox se registra el problema y se
 * continúa con el documento tal como estaba.
 */
public final class PdfOptimizerUtil {

    private static final Logger logger = LoggerFactory.getLogger(PdfOptimizerUtil.class);

    /** Límite duro de Gemini/Vertex AI por archivo PDF: 50 MB. */
    public static final long LIMITE_GEMINI_BYTES = 52_428_800L;

    /** Límite duro de Gemini/Vertex AI de páginas por documento PDF. */
    public static final int LIMITE_GEMINI_PAGINAS = 1000;

    /** Profundidad máxima de la partición recursiva (2^8 partes; en la práctica bastan 1 o 2 niveles). */
    private static final int MAX_PROFUNDIDAD_PARTICION = 8;

    /** Profundidad máxima al recorrer XObjects de formulario anidados. */
    private static final int MAX_PROFUNDIDAD_RECURSOS = 6;

    private PdfOptimizerUtil() {
    }

    /**
     * Resultado del acondicionamiento: las partes a enviar (normalmente una sola) y los datos
     * de diagnóstico para el log.
     *
     * @param partes           PDFs listos para subir a GCS, en orden; nunca vacío
     * @param bytesOriginales  tamaño del PDF recibido
     * @param bytesFinales     suma del tamaño de las partes
     * @param paginas          número de páginas del PDF recibido (0 si no se pudo determinar)
     * @param estrategia       descripción legible de lo que se aplicó, para el log
     */
    public record ResultadoOptimizacion(List<byte[]> partes,
                                        long bytesOriginales,
                                        long bytesFinales,
                                        int paginas,
                                        String estrategia) {
    }

    /**
     * Deja el PDF en condiciones de ser aceptado por Gemini.
     *
     * @param pdf                 PDF del expediente (ya unido)
     * @param limiteBytes         límite de bytes por parte; conviene dejar margen respecto de
     *                            {@link #LIMITE_GEMINI_BYTES} (por defecto se usan 48 MB)
     * @param dpiObjetivo         resolución objetivo de las imágenes al recomprimir (p.ej. 150)
     * @param calidadJpeg         calidad JPEG al recomprimir, 0.0 - 1.0 (p.ej. 0.6)
     * @param maxPaginasPorParte  páginas máximas por parte (tope de Gemini: 1000)
     * @param comprimirSiempre    si es {@code true} se recomprime aunque el PDF ya quepa, para
     *                            reducir el tiempo de subida a GCS a través del proxy
     */
    public static ResultadoOptimizacion prepararParaGemini(byte[] pdf,
                                                           long limiteBytes,
                                                           int dpiObjetivo,
                                                           float calidadJpeg,
                                                           int maxPaginasPorParte,
                                                           boolean comprimirSiempre) {

        if (pdf == null || pdf.length == 0) {
            throw new IllegalArgumentException("Se requiere el PDF de la demanda para enviarlo a Gemini");
        }

        long limite = limiteBytes > 0 ? Math.min(limiteBytes, LIMITE_GEMINI_BYTES) : LIMITE_GEMINI_BYTES;
        int maxPaginas = maxPaginasPorParte > 0
                ? Math.min(maxPaginasPorParte, LIMITE_GEMINI_PAGINAS)
                : LIMITE_GEMINI_PAGINAS;

        int paginas = contarPaginas(pdf);
        byte[] actual = pdf;
        List<String> pasos = new ArrayList<>();

        boolean excede = pdf.length > limite || (paginas > 0 && paginas > maxPaginas);

        if (excede || comprimirSiempre) {
            byte[] comprimido = comprimirImagenes(actual, dpiObjetivo, calidadJpeg);
            if (comprimido.length < actual.length) {
                pasos.add(String.format("recompresión %dppp/q%.2f: %s -> %s",
                        dpiObjetivo, calidadJpeg, mb(actual.length), mb(comprimido.length)));
                actual = comprimido;
            } else {
                pasos.add(String.format("recompresión %dppp sin ganancia", dpiObjetivo));
            }
        }

        if (actual.length > limite) {
            int dpiAgresivo = Math.max(72, (dpiObjetivo * 2) / 3);
            float calidadAgresiva = Math.max(0.35f, calidadJpeg * 0.75f);
            byte[] comprimido = comprimirImagenes(actual, dpiAgresivo, calidadAgresiva);
            if (comprimido.length < actual.length) {
                pasos.add(String.format("recompresión agresiva %dppp/q%.2f: %s -> %s",
                        dpiAgresivo, calidadAgresiva, mb(actual.length), mb(comprimido.length)));
                actual = comprimido;
            } else {
                pasos.add(String.format("recompresión agresiva %dppp sin ganancia", dpiAgresivo));
            }
        }

        List<byte[]> partes;
        if (actual.length > limite || (paginas > 0 && paginas > maxPaginas)) {
            partes = dividir(actual, limite, maxPaginas);
            pasos.add("partición en " + partes.size() + " archivo(s)");
        } else {
            partes = List.of(actual);
        }

        long bytesFinales = partes.stream().mapToLong(p -> p.length).sum();
        String estrategia = pasos.isEmpty() ? "sin cambios (dentro del límite)" : String.join(" | ", pasos);

        logger.info("[PDF Gemini] original={} ({} páginas) | final={} en {} parte(s) | límite={} | {}",
                mb(pdf.length), paginas, mb(bytesFinales), partes.size(), mb(limite), estrategia);

        return new ResultadoOptimizacion(partes, pdf.length, bytesFinales, paginas, estrategia);
    }

    // ====================================================================
    // Etapa 1 y 2 - Recompresión de las imágenes del PDF
    // ====================================================================

    /**
     * Remuestrea y recomprime como JPEG las imágenes del PDF que estén por encima de la resolución
     * objetivo. Devuelve el PDF original si algo falla o si no se logra reducirlo.
     */
    public static byte[] comprimirImagenes(byte[] pdf, int dpiObjetivo, float calidadJpeg) {

        try (PDDocument documento = PDDocument.load(pdf)) {

            // Algunos escaneos vienen con cifrado de solo lectura; sin esto no se pueden guardar.
            documento.setAllSecurityToBeRemoved(true);

            Map<COSStream, PDImageXObject> reemplazos = new IdentityHashMap<>();
            Set<Object> visitados = Collections.newSetFromMap(new IdentityHashMap<>());
            int[] contadores = new int[]{0, 0}; // [0] recomprimidas, [1] omitidas

            for (PDPage pagina : documento.getPages()) {
                float anchoPulgadas = pagina.getMediaBox().getWidth() / 72f;
                float altoPulgadas = pagina.getMediaBox().getHeight() / 72f;
                int maxPixeles = Math.max(320,
                        Math.round(Math.max(anchoPulgadas, altoPulgadas) * dpiObjetivo));

                procesarRecursos(documento, pagina.getResources(), maxPixeles, calidadJpeg,
                        reemplazos, visitados, contadores, 0);
            }

            if (contadores[0] == 0) {
                logger.info("[PDF Gemini] no se recomprimió ninguna imagen (omitidas={})", contadores[1]);
                return pdf;
            }

            ByteArrayOutputStream salida = new ByteArrayOutputStream();
            documento.save(salida);
            byte[] resultado = salida.toByteArray();

            logger.info("[PDF Gemini] imágenes recomprimidas={} omitidas={} | {} -> {}",
                    contadores[0], contadores[1], mb(pdf.length), mb(resultado.length));

            return resultado.length < pdf.length ? resultado : pdf;

        } catch (Throwable ex) {
            // La compresión es una mejora, nunca un requisito: si falla se sigue con el PDF original.
            logger.warn("[PDF Gemini] no se pudo recomprimir el PDF ({}): se continúa con el original",
                    ex.getMessage());
            return pdf;
        }
    }

    private static void procesarRecursos(PDDocument documento,
                                         PDResources recursos,
                                         int maxPixeles,
                                         float calidadJpeg,
                                         Map<COSStream, PDImageXObject> reemplazos,
                                         Set<Object> visitados,
                                         int[] contadores,
                                         int profundidad) {

        if (recursos == null || profundidad > MAX_PROFUNDIDAD_RECURSOS) {
            return;
        }

        // Se copian los nombres: dentro del bucle se reemplazan entradas del mismo diccionario.
        List<COSName> nombres = new ArrayList<>();
        for (COSName nombre : recursos.getXObjectNames()) {
            nombres.add(nombre);
        }

        for (COSName nombre : nombres) {

            PDXObject xObject;
            try {
                xObject = recursos.getXObject(nombre);
            } catch (Exception ex) {
                contadores[1]++;
                continue;
            }

            if (xObject instanceof PDFormXObject formulario) {
                if (visitados.add(formulario.getCOSObject())) {
                    procesarRecursos(documento, formulario.getResources(), maxPixeles, calidadJpeg,
                            reemplazos, visitados, contadores, profundidad + 1);
                }
                continue;
            }

            if (!(xObject instanceof PDImageXObject imagen)) {
                continue;
            }

            COSStream cos = imagen.getCOSObject();

            // La misma imagen puede estar referenciada desde varias páginas: se recomprime una vez.
            PDImageXObject yaRecomprimida = reemplazos.get(cos);
            if (yaRecomprimida != null) {
                recursos.put(nombre, yaRecomprimida);
                continue;
            }

            if (!esRecomprimible(imagen, maxPixeles)) {
                contadores[1]++;
                continue;
            }

            PDImageXObject nueva = recomprimir(documento, imagen, maxPixeles, calidadJpeg);
            if (nueva == null) {
                contadores[1]++;
                continue;
            }

            reemplazos.put(cos, nueva);
            recursos.put(nombre, nueva);
            contadores[0]++;
        }
    }

    /**
     * Descarta los casos en los que recomprimir a JPEG no aplica o empeoraría el documento:
     * máscaras y stencils (perderían la transparencia), imágenes bitonales (CCITT/JBIG2, que ya
     * comprimen mucho mejor que JPEG) e imágenes que ya están por debajo de la resolución objetivo.
     */
    private static boolean esRecomprimible(PDImageXObject imagen, int maxPixeles) {
        try {
            if (imagen.isStencil() || imagen.getBitsPerComponent() == 1) {
                return false;
            }
            if (imagen.getSoftMask() != null) {
                return false;
            }
            if (imagen.getCOSObject().containsKey(COSName.MASK)) {
                return false;
            }
            return Math.max(imagen.getWidth(), imagen.getHeight()) > maxPixeles;
        } catch (Exception ex) {
            return false;
        }
    }

    /**
     * Devuelve la imagen remuestreada y recomprimida, o {@code null} si no se pudo o si el
     * resultado no pesa menos que el original.
     */
    private static PDImageXObject recomprimir(PDDocument documento,
                                              PDImageXObject imagen,
                                              int maxPixeles,
                                              float calidadJpeg) {
        try {
            int maxDimension = Math.max(imagen.getWidth(), imagen.getHeight());

            // El submuestreo evita decodificar el escaneo completo en memoria (una página a 600 ppp
            // puede ocupar cientos de MB); el ajuste fino se hace después sobre la imagen ya reducida.
            int submuestreo = Math.max(1, maxDimension / maxPixeles);

            BufferedImage original = imagen.getImage(null, submuestreo);
            if (original == null) {
                return null;
            }

            double factor = (double) maxPixeles / Math.max(original.getWidth(), original.getHeight());
            int ancho = factor < 1.0 ? Math.max(1, (int) Math.round(original.getWidth() * factor)) : original.getWidth();
            int alto = factor < 1.0 ? Math.max(1, (int) Math.round(original.getHeight() * factor)) : original.getHeight();

            // JPEG no admite canal alfa: se compone sobre blanco (el fondo del papel).
            BufferedImage destino = new BufferedImage(ancho, alto, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = destino.createGraphics();
            try {
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
                g.setColor(Color.WHITE);
                g.fillRect(0, 0, ancho, alto);
                g.drawImage(original, 0, 0, ancho, alto, null);
            } finally {
                g.dispose();
                original.flush();
            }

            PDImageXObject nueva = JPEGFactory.createFromImage(documento, destino, calidadJpeg);
            destino.flush();

            long tamanioOriginal = imagen.getCOSObject().getLength();
            long tamanioNuevo = nueva.getCOSObject().getLength();

            if (tamanioOriginal > 0 && tamanioNuevo >= tamanioOriginal) {
                return null; // no aporta nada: se conserva la imagen original
            }

            return nueva;

        } catch (Throwable ex) {
            logger.debug("[PDF Gemini] imagen omitida al recomprimir: {}", ex.getMessage());
            return null;
        }
    }

    // ====================================================================
    // Etapa 3 - Partición del PDF
    // ====================================================================

    /**
     * Divide el PDF por mitades sucesivas hasta que cada parte respete el límite de bytes y de
     * páginas. Si una página suelta excediera el límite se devuelve igualmente (no hay forma de
     * partirla) y será Gemini quien la rechace.
     */
    public static List<byte[]> dividir(byte[] pdf, long limiteBytes, int maxPaginasPorParte) {
        List<byte[]> partes = new ArrayList<>();
        try {
            agregarParte(pdf, limiteBytes, maxPaginasPorParte, partes, 0);
        } catch (Throwable ex) {
            logger.warn("[PDF Gemini] no se pudo particionar el PDF ({}): se envía completo", ex.getMessage());
            partes.clear();
            partes.add(pdf);
        }
        return partes.isEmpty() ? List.of(pdf) : partes;
    }

    private static void agregarParte(byte[] pdf,
                                     long limiteBytes,
                                     int maxPaginasPorParte,
                                     List<byte[]> salida,
                                     int profundidad) throws IOException {

        int paginas = contarPaginas(pdf);
        boolean cabe = pdf.length <= limiteBytes && (paginas == 0 || paginas <= maxPaginasPorParte);

        if (cabe || paginas <= 1 || profundidad >= MAX_PROFUNDIDAD_PARTICION) {
            salida.add(pdf);
            return;
        }

        List<byte[]> mitades = new ArrayList<>(2);
        try (PDDocument documento = PDDocument.load(pdf)) {
            documento.setAllSecurityToBeRemoved(true);
            Splitter splitter = new Splitter();
            splitter.setSplitAtPage((paginas + 1) / 2);

            for (PDDocument mitad : splitter.split(documento)) {
                try (PDDocument cerrable = mitad) {
                    ByteArrayOutputStream salidaMitad = new ByteArrayOutputStream();
                    cerrable.save(salidaMitad);
                    mitades.add(salidaMitad.toByteArray());
                }
            }
        }

        for (byte[] mitad : mitades) {
            agregarParte(mitad, limiteBytes, maxPaginasPorParte, salida, profundidad + 1);
        }
    }

    // ====================================================================
    // Utilidades
    // ====================================================================

    /** Número de páginas del PDF, o 0 si el documento no se puede abrir. */
    public static int contarPaginas(byte[] pdf) {
        try (PDDocument documento = PDDocument.load(pdf)) {
            return documento.getNumberOfPages();
        } catch (Throwable ex) {
            logger.warn("[PDF Gemini] no se pudo determinar el número de páginas: {}", ex.getMessage());
            return 0;
        }
    }

    private static String mb(long bytes) {
        return String.format("%.2f MB", bytes / (1024.0 * 1024.0));
    }
}
