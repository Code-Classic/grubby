package com.codeclassic.grubby.service.notification;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

@Slf4j
@Service
public class SlackNotificationService {

    private final RestTemplate restTemplate = new RestTemplate();

    /**
     * Posts a message to a Slack Incoming Webhook URL.
     * If webhookUrl is blank, this is a no-op.
     */
    public void send(String webhookUrl, String text) {
        if (webhookUrl == null || webhookUrl.isBlank()) return;
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            Map<String, String> body = Map.of("text", text);
            ResponseEntity<String> resp = restTemplate.postForEntity(
                    webhookUrl, new HttpEntity<>(body, headers), String.class);
            if (!resp.getStatusCode().is2xxSuccessful()) {
                log.warn("Slack webhook returned {}: {}", resp.getStatusCode(), resp.getBody());
            }
        } catch (Exception e) {
            log.warn("Failed to send Slack notification: {}", e.getMessage());
        }
    }

    public void sendBrdCompleted(String webhookUrl, String brdTitle, String brdId, String frontendUrl) {
        String text = String.format(
                ":white_check_mark: *BRD Generated Successfully*\n*%s*\n<%s/brd/%s|View BRD>",
                brdTitle, frontendUrl, brdId);
        send(webhookUrl, text);
    }

    public void sendBrdFailed(String webhookUrl, String brdTitle, String brdId, String frontendUrl) {
        String text = String.format(
                ":x: *BRD Generation Failed*\n*%s*\n<%s/brd/%s|View Details>",
                brdTitle, frontendUrl, brdId);
        send(webhookUrl, text);
    }
}
