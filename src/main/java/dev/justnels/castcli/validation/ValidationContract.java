package dev.justnels.castcli.validation;

import java.nio.file.Path;

/**
 * Interface for deterministic output validation contracts.
 */
public interface ValidationContract {

    /**
     * Unique name identifying the validator.
     */
    String name();

    /**
     * Evaluates model output or resulting workspace state against deterministic rules.
     */
    ValidationResult validate(String modelOutput, Path workspaceRoot);
}
