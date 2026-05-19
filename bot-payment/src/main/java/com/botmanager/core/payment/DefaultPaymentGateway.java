package com.botmanager.core.payment;

import com.botmanager.config.MicroserviceProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class DefaultPaymentGateway implements PaymentGateway {

    private final RestTemplate restTemplate;

    private final MicroserviceProperties microserviceProperties;

    private final PaymentStore paymentStore;

    private final PaymentEventPublisher paymentEventPublisher;

    @Override
    public PaymentResult initiatePayment(PaymentRequest request) {
        String url = microserviceProperties.getPaymentServiceUrl() + "/api/payments/initiate";

        try {
            Map<String, Object> body = new HashMap<>();
            body.put("phoneNumber", request.phoneNumber());
            body.put("amount", request.amount());
            body.put("machineId", extractMachineId(request));
            body.put("pulseCount", extractPulseCount(request));
            body.put("cycleDuration", extractCycleDuration(request));
            body.put("provider", resolveProvider(request.phoneNumber()));
            body.put("description", request.description());

            HttpHeaders headers = new HttpHeaders();
            headers.set("Content-Type", "application/json");
            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);

            ResponseEntity<Map> response = restTemplate.exchange(url, HttpMethod.POST, entity, Map.class);

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                Map<String, Object> responseBody = response.getBody();
                boolean success = Boolean.TRUE.equals(responseBody.get("success"));
                String externalRef = (String) responseBody.get("externalReference");
                String providerRef = (String) responseBody.get("providerReference");
                String status = (String) responseBody.get("status");

                PaymentResult result = PaymentResult.builder()
                        .success(success)
                        .transactionId(providerRef)
                        .externalRef(externalRef)
                        .status(PaymentStatus.fromValue(status))
                        .raw(responseBody)
                        .build();

                if (success) {
                    PaymentRecord record = PaymentRecord.builder()
                            .botId(request.botId())
                            .provider(resolveProvider(request.phoneNumber()))
                            .transactionId(externalRef)
                            .externalRef(externalRef)
                            .customerPhone(request.phoneNumber())
                            .amount(request.amount())
                            .currency(request.currency())
                            .status(PaymentStatus.fromValue(status))
                            .metadata(request.metadata())
                            .createdAt(Instant.now())
                            .raw(responseBody)
                            .build();

                    paymentStore.upsertPayment(record);
                    paymentEventPublisher.publishInitiated(record);
                }

                return result;
            }

            return PaymentResult.builder()
                    .success(false)
                    .errorMessage("Payment service returned unexpected response")
                    .build();

        } catch (Exception exception) {
            log.error("Failed to initiate payment via PaymentManagementService: {}", exception.getMessage());

            return PaymentResult.builder()
                    .success(false)
                    .errorMessage(exception.getMessage())
                    .build();
        }
    }

    @Override
    public PaymentStatus checkStatus(String botId, String provider, String transactionId) {
        String url = microserviceProperties.getPaymentServiceUrl()
                + "/api/payments/transaction/" + transactionId;

        try {
            ResponseEntity<Map> response = restTemplate.getForEntity(url, Map.class);

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                String status = (String) response.getBody().get("status");
                return PaymentStatus.fromValue(status);
            }

            return PaymentStatus.PENDING;
        } catch (Exception exception) {
            log.error("Failed to check payment status: {}", exception.getMessage());
            return PaymentStatus.PENDING;
        }
    }

    @Override
    public PaymentResult handleWebhook(String botId, String providerName, Map<String, Object> payload) {
        String webhookUrl = microserviceProperties.getPaymentServiceUrl()
                + "/api/webhook/" + providerName;

        try {
            HttpHeaders headers = new HttpHeaders();
            headers.set("Content-Type", "application/json");
            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(payload, headers);

            ResponseEntity<Map> response = restTemplate.exchange(
                    webhookUrl, HttpMethod.POST, entity, Map.class);

            String externalRef = (String) payload.get("external_reference");
            if (externalRef == null) {
                externalRef = (String) payload.get("externalId");
            }

            String transactionId = (String) payload.get("reference");
            String status = (String) payload.get("status");
            if (status == null) {
                status = (String) payload.get("state");
            }

            PaymentResult result = PaymentResult.builder()
                    .success(true)
                    .transactionId(transactionId)
                    .externalRef(externalRef)
                    .status(PaymentStatus.fromValue(status))
                    .raw(payload)
                    .build();

            if (result.transactionId() != null) {
                paymentStore.getPayment(botId, result.transactionId())
                        .or(() -> paymentStore.getPaymentByExternalRef(botId, result.externalRef()))
                        .ifPresent(record -> {
                            record.setStatus(result.status());
                            record.setRaw(result.raw());
                            paymentStore.upsertPayment(record);
                            paymentEventPublisher.publishStatusUpdate(record);
                        });
            }

            return result;
        } catch (Exception exception) {
            log.error("Failed to forward webhook to PaymentManagementService: {}", exception.getMessage());

            return PaymentResult.builder()
                    .success(false)
                    .errorMessage(exception.getMessage())
                    .build();
        }
    }

    private String extractMachineId(PaymentRequest request) {
        if (request.metadata() != null && request.metadata().containsKey("machineId")) {
            return (String) request.metadata().get("machineId");
        }
        return "unknown";
    }

    private int extractPulseCount(PaymentRequest request) {
        if (request.metadata() != null && request.metadata().containsKey("pulseCount")) {
            return ((Number) request.metadata().get("pulseCount")).intValue();
        }
        return 1;
    }

    private int extractCycleDuration(PaymentRequest request) {
        if (request.metadata() != null && request.metadata().containsKey("duration")) {
            return ((Number) request.metadata().get("duration")).intValue();
        }
        return 30;
    }

    private String resolveProvider(String phoneNumber) {
        if (phoneNumber == null) return "CAMPAY";
        String cleaned = phoneNumber.replaceAll("[^0-9]", "");
        if (cleaned.startsWith("237")) {
            cleaned = cleaned.substring(3);
        }
        if (cleaned.startsWith("69") || cleaned.startsWith("65")) {
            return "ORANGE_MONEY";
        }
        return "CAMPAY";
    }

}
