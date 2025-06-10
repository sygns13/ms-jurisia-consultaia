package pj.gob.pe.consultaia.controller.test;

import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Test kafka Controller", description = "API para realizar Tests kafka Controller")
@RestController
@RequestMapping("/testkafka")
@RequiredArgsConstructor
public class KafkaTestController {

    private final KafkaTemplate<String, String> kafkaTemplate;

    @GetMapping("/send")
    public String sendTestMessage() {
        kafkaTemplate.send("judicial-metrics", "key", "Hello Kafka");
        return "Message sent!";
    }
}
