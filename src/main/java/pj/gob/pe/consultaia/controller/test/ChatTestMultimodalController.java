package pj.gob.pe.consultaia.controller.test;

import com.google.api.gax.core.FixedCredentialsProvider;
import com.google.api.gax.retrying.RetrySettings;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.cloud.vertexai.Transport;
import com.google.cloud.vertexai.VertexAI;
import com.google.cloud.vertexai.api.*;
import com.google.cloud.vertexai.generativeai.ContentMaker;
import com.google.cloud.vertexai.generativeai.GenerativeModel;
import com.google.cloud.vertexai.generativeai.PartMaker;
import com.google.cloud.vertexai.generativeai.ResponseHandler;
import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.extractor.WordExtractor;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.threeten.bp.Duration;
import pj.gob.pe.consultaia.configuration.ConfigProperties;
import pj.gob.pe.consultaia.utils.beans.responses.ApiResponse;

import com.google.api.client.http.javanet.NetHttpTransport;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;

@RestController
@RequestMapping("/api/v1/chat")
public class ChatTestMultimodalController {

    private final String projectId = "apubot-v1";
    private final String location = "global";

    private final ConfigProperties properties;

    public ChatTestMultimodalController(ConfigProperties properties) {
        this.properties = properties;
    }

    @PostMapping(value = "/interactuar")
    public Callable<ResponseEntity<ApiResponse<String>>> interactuarMultimodal(
            @RequestParam(value = "files", required = false) MultipartFile[] files,
            @RequestParam("prompt") String prompt) throws IOException {

        List<FileDataHolder> fileDataList = new ArrayList<>();
        if (files != null) {
            for (MultipartFile file : files) {
                if (!file.isEmpty()) {
                    fileDataList.add(new FileDataHolder(file.getContentType(), file.getBytes()));
                }
            }
        }

        return () -> {
            long start = System.nanoTime();
            try {
                GoogleCredentials credentials = GoogleCredentials.fromStream(
                        new ByteArrayInputStream(properties.getGcpCredentialsContent().getBytes(StandardCharsets.UTF_8))
                ).createScoped(Collections.singletonList("https://www.googleapis.com/auth/cloud-platform"));

                PredictionServiceSettings.Builder settingsBuilder =
                        PredictionServiceSettings.newHttpJsonBuilder();
                settingsBuilder.setEndpoint("aiplatform.googleapis.com:443");
                settingsBuilder.setCredentialsProvider(FixedCredentialsProvider.create(credentials));

                RetrySettings retry = settingsBuilder
                        .generateContentSettings()
                        .getRetrySettings()
                        .toBuilder()
                        .setInitialRpcTimeout(Duration.ofMinutes(5))
                        .setMaxRpcTimeout(Duration.ofMinutes(5))
                        .setTotalTimeout(Duration.ofMinutes(10))
                        .build();
                settingsBuilder.generateContentSettings().setRetrySettings(retry);

                if (properties.getProxyEnabled()) {
                    Proxy proxy = new Proxy(Proxy.Type.HTTP,
                            new InetSocketAddress(properties.getProxyURL(), properties.getProxyPort()));
                    settingsBuilder.setTransportChannelProvider(
                            PredictionServiceSettings.defaultHttpJsonTransportProviderBuilder()
                                    .setHttpTransport(new NetHttpTransport.Builder().setProxy(proxy).build())
                                    .build()
                    );
                }

                PredictionServiceSettings predictionSettings = settingsBuilder.build();

                try (VertexAI vertexAI = new VertexAI.Builder()
                        .setProjectId(projectId)
                        .setLocation(location)
                        .setCredentials(credentials)
                        .setTransport(Transport.REST)
                        .setApiEndpoint("aiplatform.googleapis.com")
                        .setPredictionClientSupplier(() -> {
                            try {
                                return PredictionServiceClient.create(predictionSettings);
                            } catch (IOException ex) {
                                throw new RuntimeException(ex);
                            }
                        })
                        .build()) {

                    GenerativeModel model = new GenerativeModel("gemini-3.5-flash", vertexAI)
                            .withSystemInstruction(ContentMaker.fromString(
                                    "Eres un abogado experto en derecho peruano con amplia experiencia en legislación, normativas y jurisprudencia en áreas como derecho penal, civil, constitucional, laboral y empresarial. Respondes preguntas legales de manera clara y precisa, citando leyes y artículos relevantes del Código Civil, Código Penal, Constitución Política del Perú y demás normativas vigentes. No das consejos legales definitivos, pero brindas información detallada y explicas los procedimientos legales aplicables. Adicionalmente solo respondes consultas asociadas a temáticas legales, jurídicas o relacionados, en otros casos respondes amablemente que no atiendes esas clases de consultas."
                            ));

                    GenerationConfig generationConfig = GenerationConfig.newBuilder()
                            .setTemperature(0.4f)
                            .setMaxOutputTokens(4096)
                            .build();

                    GenerativeModel configuredModel = model.withGenerationConfig(generationConfig);

                    List<Object> multiModalData = new ArrayList<>();
                    for (FileDataHolder holder : fileDataList) {
                        String extractedText = extractWordText(holder);
                        if (extractedText != null) {
                            multiModalData.add(
                                    "\n--- Contenido del documento Word adjunto ---\n"
                                            + extractedText
                                            + "\n--------------------------------------------\n");
                        } else {
                            multiModalData.add(PartMaker.fromMimeTypeAndData(holder.mimeType(), holder.bytes()));
                        }
                    }
                    multiModalData.add(prompt);

                    Content inputContent = ContentMaker.fromMultiModalData(multiModalData.toArray());

                    GenerateContentResponse response = configuredModel.generateContent(inputContent);
                    String fullText = ResponseHandler.getText(response);

                    double seconds = (System.nanoTime() - start) / 1_000_000_000.0;
                    return ResponseEntity.ok(ApiResponse.ok(fullText, seconds));
                }
            } catch (Exception e) {
                Throwable cause = e;
                while (cause.getCause() != null) cause = cause.getCause();
                double seconds = (System.nanoTime() - start) / 1_000_000_000.0;
                return ResponseEntity.internalServerError()
                        .body(ApiResponse.error(cause.getMessage(), seconds));
            }
        };
    }

    private String extractWordText(FileDataHolder holder) throws IOException {
        String mime = holder.mimeType();
        if ("application/vnd.openxmlformats-officedocument.wordprocessingml.document".equals(mime)) {
            try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(holder.bytes()));
                 XWPFWordExtractor extractor = new XWPFWordExtractor(doc)) {
                return extractor.getText();
            }
        }
        if ("application/msword".equals(mime)) {
            try (HWPFDocument doc = new HWPFDocument(new ByteArrayInputStream(holder.bytes()));
                 WordExtractor extractor = new WordExtractor(doc)) {
                return extractor.getText();
            }
        }
        return null;
    }

    private record FileDataHolder(String mimeType, byte[] bytes) {}
}
