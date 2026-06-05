package pj.gob.pe.consultaia.utils;

import org.apache.pdfbox.io.MemoryUsageSetting;
import org.apache.pdfbox.multipdf.PDFMergerUtility;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

/**
 * Utilitario para unir varios documentos PDF (en memoria) en un único PDF,
 * respetando el orden de la lista recibida.
 */
public final class PdfMergeUtil {

    private PdfMergeUtil() {
    }

    /**
     * Une los PDFs recibidos en un solo documento. Si la lista contiene un único
     * archivo, lo retorna sin modificar.
     *
     * @param pdfs lista de PDFs en formato byte[], en el orden en que deben unirse
     * @return byte[] del PDF resultante
     */
    public static byte[] unir(List<byte[]> pdfs) throws IOException {

        if (pdfs == null || pdfs.isEmpty()) {
            throw new IllegalArgumentException("Se requiere al menos un PDF para unir");
        }

        if (pdfs.size() == 1) {
            return pdfs.get(0);
        }

        PDFMergerUtility merger = new PDFMergerUtility();
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        merger.setDestinationStream(outputStream);

        for (byte[] pdf : pdfs) {
            if (pdf == null || pdf.length == 0) {
                throw new IllegalArgumentException("Uno de los archivos PDF a unir está vacío");
            }
            merger.addSource(new ByteArrayInputStream(pdf));
        }

        merger.mergeDocuments(MemoryUsageSetting.setupMainMemoryOnly());

        return outputStream.toByteArray();
    }
}
