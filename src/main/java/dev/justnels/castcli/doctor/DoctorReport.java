package dev.justnels.castcli.doctor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.util.List;

/**
 * Report containing results of system diagnostic checks.
 */
public record DoctorReport(List<CheckResult> results) {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    public enum Status {
        OK,
        WARNING,
        ERROR
    }

    public record CheckResult(String category, String name, Status status, String message) {
        public String statusSymbol() {
            return switch (status) {
                case OK -> "✔";
                case WARNING -> "⚠";
                case ERROR -> "✖";
            };
        }
    }

    public boolean isHealthy() {
        return results.stream().noneMatch(r -> r.status() == Status.ERROR);
    }

    public String toJson() {
        try {
            return MAPPER.writeValueAsString(this);
        } catch (Exception e) {
            return "{\"healthy\":" + isHealthy() + ",\"error\":\"" + e.getMessage() + "\"}";
        }
    }
}
