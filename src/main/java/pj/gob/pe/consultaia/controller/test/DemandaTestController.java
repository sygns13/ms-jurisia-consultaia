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
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
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
import java.util.Collections;
import java.util.concurrent.Callable;

@RestController
public class DemandaTestController {

    private final String projectId = "gen-lang-client-0987988254";
    private final String location = "global";
    private final String dataStoreId = "codigos-procesales-demo-1_1768362350794";

    private final ConfigProperties properties;

    public DemandaTestController(ConfigProperties properties) {
        this.properties = properties;
    }

    @PostMapping(value = "/analizar-demanda")
    public Callable<ResponseEntity<ApiResponse<String>>> analizarDemanda(
            @RequestParam("file") MultipartFile file,
            @RequestParam("prompt") String prompt) throws IOException {

        byte[] documentBytes = file.getBytes();

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

                    String datastorePath = String.format(
                            "projects/%s/locations/global/collections/default_collection/dataStores/%s",
                            projectId, dataStoreId);
                    VertexAISearch vertexAiSearch = VertexAISearch.newBuilder().setDatastore(datastorePath).build();
                    Retrieval retrieval = Retrieval.newBuilder().setVertexAiSearch(vertexAiSearch).build();
                    Tool herramientaLeyes = Tool.newBuilder().setRetrieval(retrieval).build();

                    GenerativeModel model = new GenerativeModel("gemini-3.1-pro-preview", vertexAI)
                            .withSystemInstruction(ContentMaker.fromString(
                                    /*"Eres un Asistente Judicial del Perú experto en Derecho Procesal.\n" +
                                            "    TU TAREA: Analizar la demanda adjunta y cruzarla con la normativa legal recuperada.\n" +
                                            "    REGLAS:\n" +
                                            "    1. Responde basándote ESTRICTAMENTE en los documentos legales encontrados en tu base de conocimiento.\n" +
                                            "    2. Cita el Artículo exacto y el Código de donde proviene la información.\n" +
                                            "    3. Si la demanda incumple un requisito legal explícito, indícalo claramente."
                                            */
                                     "Eres un Asistente Judicial Virtual experto en derecho peruano con amplia experiencia en legislación, normativas y jurisprudencia en áreas como derecho penal, civil, constitucional, laboral y empresarial. Realizas calificaciones de Demandas en base a la normativa peruana, generas automáticamente el borrador de la resolución de calificación de demanda (Auto Admisorio o Resolución de Inadmisibilidad/Improcedencia) tras el análisis de una demanda ingresada. Por lo que tu respuesta debe de ser directamente el texto de la resolución de calificación de demanda."
                            ))
                            .withTools(java.util.Arrays.asList(herramientaLeyes));

                    GenerationConfig generationConfig = GenerationConfig.newBuilder()
                            .setTemperature(0.0f)
                            .setMaxOutputTokens(8192)
                            .build();

                    GenerativeModel configuredModel = model.withGenerationConfig(generationConfig);

                    var documentPart = PartMaker.fromMimeTypeAndData("application/pdf", documentBytes);
                    Content inputContent = ContentMaker.fromMultiModalData(documentPart, prompt);

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
}
