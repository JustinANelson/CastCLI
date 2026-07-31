package dev.justnels.castcli.validation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Path;
import java.util.List;

/**
 * Deterministic validator enforcing valid JSON structure and required top-level fields.
 */
public final class JsonSchemaValidator implements ValidationContract {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final List<String> requiredFields;

    public JsonSchemaValidator(List<String> requiredFields) {
        this.requiredFields = requiredFields == null ? List.of() : List.copyOf(requiredFields);
    }

    @Override
    public String name() {
        return "JsonSchemaValidator";
    }

    @Override
    public ValidationResult validate(String modelOutput, Path workspaceRoot) {
        long startTime = System.currentTimeMillis();
        if (modelOutput == null || modelOutput.isBlank()) {
            long duration = System.currentTimeMillis() - startTime;
            return ValidationResult.fail(name(), "Model output is empty", "Please provide a valid non-empty JSON response.", duration);
        }

        String jsonCandidate = extractJsonSnippet(modelOutput);
        try {
            JsonNode root = MAPPER.readTree(jsonCandidate);
            for (String field : requiredFields) {
                if (!root.has(field) || root.get(field).isNull()) {
                    long duration = System.currentTimeMillis() - startTime;
                    return ValidationResult.fail(name(),
                            "Missing required JSON property '" + field + "'",
                            "Your previous output was missing property '" + field + "'. Ensure the JSON output contains key '" + field + "'.",
                            duration);
                }
            }
            long duration = System.currentTimeMillis() - startTime;
            return ValidationResult.pass(name(), duration);
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            return ValidationResult.fail(name(),
                    "Invalid JSON syntax: " + e.getMessage(),
                    "Your previous response was not valid JSON: " + e.getMessage() + ". Return raw JSON with proper syntax.",
                    duration);
        }
    }

    private static String extractJsonSnippet(String text) {
        int startObj = text.indexOf('{');
        int startArr = text.indexOf('[');
        int start = -1;
        if (startObj != -1 && startArr != -1) {
            start = Math.min(startObj, startArr);
        } else if (startObj != -1) {
            start = startObj;
        } else if (startArr != -1) {
            start = startArr;
        }

        if (start == -1) {
            return text.trim();
        }

        int endObj = text.lastIndexOf('}');
        int endArr = text.lastIndexOf(']');
        int end = Math.max(endObj, endArr);
        if (end > start) {
            return text.substring(start, end + 1);
        }
        return text.substring(start);
    }
}
