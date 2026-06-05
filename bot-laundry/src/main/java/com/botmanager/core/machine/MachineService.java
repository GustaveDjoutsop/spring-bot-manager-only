package com.botmanager.core.machine;

import com.botmanager.bots.laundry.LaundryBotConfig;
import com.botmanager.core.payment.PaymentEventPublisher;
import com.botmanager.core.payment.PaymentRecord;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class MachineService {

    private final MachineStore machineStore;

    private final RestTemplate restTemplate;

    private final ObjectMapper objectMapper;

    @Value("${microservice.machine-state-service-url:http://localhost:8082}")
    private String machineStateServiceUrl;

    @Value("${microservice.machine-state-service-token:}")
    private String machineStateServiceToken;

    private final Map<String, LaundryBotConfig> botConfigs = new ConcurrentHashMap<>();

    public void registerBot(LaundryBotConfig botConfig) {
        if (botConfig.getMachines() == null || botConfig.getMachines().isEmpty()) {
            return;
        }

        botConfigs.put(botConfig.getBotId(), botConfig);
        seedMachines(botConfig);

        log.info("Registered {} machines for bot {}", botConfig.getMachines().size(), botConfig.getBotId());
    }

    public List<MachineRecord> getMachines(String botId) {
        try {
            ResponseEntity<Map> response = restTemplate.exchange(
                    machineStateServiceUrl + "/api/machines",
                    HttpMethod.GET,
                    new HttpEntity<>(buildAuthHeaders()),
                    Map.class);

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                return mapMachineListFromResponse(botId, response.getBody());
            }

            throw new MachineServiceUnavailableException(
                    "MachineStateService returned non-2xx status: " + response.getStatusCode());
        } catch (MachineServiceUnavailableException e) {
            throw e;
        } catch (Exception exception) {
            log.warn("Failed to get machines from MachineStateService: {}", exception.getMessage());
            throw new MachineServiceUnavailableException("MachineStateService unreachable", exception);
        }
    }

    public Optional<MachineRecord> getMachine(String botId, String machineId) {
        try {
            ResponseEntity<Map> response = restTemplate.exchange(
                    machineStateServiceUrl + "/api/machines/" + machineId,
                    HttpMethod.GET,
                    new HttpEntity<>(buildAuthHeaders()),
                    Map.class);

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                return Optional.of(mapMachineFromResponse(botId, response.getBody()));
            }

            throw new MachineServiceUnavailableException(
                    "MachineStateService returned non-2xx status: " + response.getStatusCode());
        } catch (MachineServiceUnavailableException e) {
            throw e;
        } catch (Exception exception) {
            log.warn("Failed to get machine {} from MachineStateService: {}", machineId, exception.getMessage());
            throw new MachineServiceUnavailableException("MachineStateService unreachable for machine " + machineId, exception);
        }
    }

    public List<MachineRecord> getAvailableMachines(String botId) {
        return getMachines(botId).stream()
                .filter(machine -> machine.getStatus() == MachineStatus.AVAILABLE)
                .toList();
    }

    public void startMachine(String botId, String machineId, String program, String transactionId) {
        try {
            Map<String, Object> body = new HashMap<>();
            body.put("machineId", machineId);
            body.put("cycleType", program != null ? program : "NORMAL");
            body.put("durationMinutes", resolveDuration(botId, program));
            body.put("pulseCount", resolvePulseCount(botId, program));
            body.put("transactionReference", transactionId);

            HttpHeaders headers = new HttpHeaders();
            headers.set("Content-Type", "application/json");
            applyBearerAuth(headers);
            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);

            restTemplate.exchange(
                    machineStateServiceUrl + "/api/machines/start-cycle",
                    HttpMethod.POST, entity, Map.class);

            log.info("Sent start-cycle to MachineStateService: machine={}, program={}", machineId, program);

        } catch (Exception exception) {
            log.error("Failed to start machine {} via MachineStateService: {}",
                    machineId, exception.getMessage());
        }
    }

    public void stopMachine(String botId, String machineId, String transactionId) {
        try {
            HttpEntity<Void> entity = new HttpEntity<>(buildAuthHeaders());
            restTemplate.exchange(
                    machineStateServiceUrl + "/api/machines/" + machineId + "/command/stop",
                HttpMethod.POST,
                entity,
                Map.class);

            log.info("Sent STOP command to machine {} via MachineStateService", machineId);
        } catch (Exception exception) {
            log.error("Failed to stop machine {}: {}", machineId, exception.getMessage());
        }
    }

    public void requestStatus(String botId, String machineId) {
        try {
            HttpEntity<Void> entity = new HttpEntity<>(buildAuthHeaders());
            restTemplate.exchange(
                    machineStateServiceUrl + "/api/machines/" + machineId + "/command/status",
                    HttpMethod.POST,
                    entity,
                    Map.class);
        } catch (Exception exception) {
            log.warn("Failed to request status for machine {}: {}", machineId, exception.getMessage());
        }
    }

    private HttpHeaders buildAuthHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        applyBearerAuth(headers);
        return headers;
    }

    private void applyBearerAuth(HttpHeaders headers) {
        if (machineStateServiceToken != null && !machineStateServiceToken.isBlank()) {
            headers.setBearerAuth(machineStateServiceToken);
        }
    }

    @EventListener
    public void onPaymentCompleted(PaymentEventPublisher.PaymentCompletedEvent event) {
        PaymentRecord record = event.getRecord();

        if (record.getMetadata() == null) {
            return;
        }

        String machineId = (String) record.getMetadata().get("machineId");
        String program = (String) record.getMetadata().get("program");

        if (machineId != null) {
            startMachine(record.getBotId(), machineId,
                    program != null ? program : "NORMAL",
                    record.getTransactionId());
        }
    }

    private void seedMachines(LaundryBotConfig botConfig) {
        for (MachineConfig machineConfig : botConfig.getMachines()) {
            MachineRecord record = MachineRecord.builder()
                    .botId(botConfig.getBotId())
                    .machineId(machineConfig.getId())
                    .type(machineConfig.getType())
                    .name(machineConfig.getName())
                    .status(MachineStatus.AVAILABLE)
                    .build();

            machineStore.upsertMachine(record);
        }
    }

    @SuppressWarnings("unchecked")
    private List<MachineRecord> mapMachineListFromResponse(String botId, Map<String, Object> responseBody) {
        List<MachineRecord> records = new ArrayList<>();

        Object machinesObj = responseBody.get("machines");
        if (machinesObj instanceof List<?> machinesList) {
            for (Object item : machinesList) {
                if (item instanceof Map) {
                    records.add(mapMachineFromResponse(botId, (Map<String, Object>) item));
                }
            }
        }

        return records;
    }

    private MachineRecord mapMachineFromResponse(String botId, Map<String, Object> data) {
        String machineId = (String) data.get("machineId");
        String displayName = (String) data.get("displayName");
        String statusStr = (String) data.get("status");
        String typeStr = (String) data.get("type");
        Boolean available = (Boolean) data.get("available");
        Object remainingObj = data.get("remainingMinutes");
        Integer remainingMinutes = remainingObj instanceof Number ? ((Number) remainingObj).intValue() : null;

        MachineStatus status;
        if (Boolean.TRUE.equals(available)) {
            status = MachineStatus.AVAILABLE;
        } else if ("RUNNING".equalsIgnoreCase(statusStr)) {
            status = MachineStatus.IN_USE;
        } else if ("FINISHED".equalsIgnoreCase(statusStr)) {
            status = MachineStatus.COMPLETING;
        } else if ("ERROR".equalsIgnoreCase(statusStr)) {
            status = MachineStatus.ERROR;
        } else if ("MAINTENANCE".equalsIgnoreCase(statusStr)) {
            status = MachineStatus.MAINTENANCE;
        } else {
            status = MachineStatus.fromValue(statusStr);
        }

        MachineType type = null;
        if ("WASHER".equalsIgnoreCase(typeStr)) {
            type = MachineType.WASHER;
        } else if ("DRYER".equalsIgnoreCase(typeStr)) {
            type = MachineType.DRYER;
        }

        MachineRecord record = MachineRecord.builder()
                .botId(botId)
                .machineId(machineId)
                .type(type)
                .name(displayName != null ? displayName : machineId)
                .status(status)
                .remainingSeconds(remainingMinutes != null ? remainingMinutes * 60 : null)
                .lastHeartbeatAt(Instant.now())
                .build();

        machineStore.upsertMachine(record);

        return record;
    }

    private int resolveDuration(String botId, String program) {
        LaundryBotConfig config = botConfigs.get(botId);
        if (config != null && config.getShortCycle() != null && config.getLongCycle() != null) {
            if ("cycle_long".equalsIgnoreCase(program) || "HEAVY".equalsIgnoreCase(program)) {
                return config.getLongCycle().getDuration();
            }
            return config.getShortCycle().getDuration();
        }
        return 30;
    }

    private int resolvePulseCount(String botId, String program) {
        LaundryBotConfig config = botConfigs.get(botId);
        if (config != null && config.getShortCycle() != null && config.getLongCycle() != null) {
            if ("cycle_long".equalsIgnoreCase(program) || "HEAVY".equalsIgnoreCase(program)) {
                return config.getLongCycle().getPulseCount();
            }
            return config.getShortCycle().getPulseCount();
        }
        return 1;
    }

}
