package dev.justnels.castcli.validation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ValidationContractTest {

    @Test
    void jsonSchemaValidatorPassesValidJson() {
        JsonSchemaValidator validator = new JsonSchemaValidator(List.of("name", "version"));
        ValidationResult result = validator.validate("{\"name\": \"cast-cli\", \"version\": \"0.2.0\"}", Path.of("."));
        assertTrue(result.isPass());
    }

    @Test
    void jsonSchemaValidatorFailsMissingProperty() {
        JsonSchemaValidator validator = new JsonSchemaValidator(List.of("name", "version"));
        ValidationResult result = validator.validate("{\"name\": \"cast-cli\"}", Path.of("."));
        assertFalse(result.isPass());
        assertTrue(result.diagnostic().toLowerCase().contains("missing required json property 'version'"));
    }

    @Test
    void compileValidatorPassesValidSyntax(@TempDir Path tempDir) {
        CompileValidator validator = new CompileValidator();
        String validJava = """
                ```java
                public class Hello {
                    public static void main(String[] args) {
                        System.out.println("Hello");
                    }
                }
                ```
                """;
        ValidationResult result = validator.validate(validJava, tempDir);
        assertTrue(result.isPass());
    }

    @Test
    void compileValidatorFailsInvalidSyntax(@TempDir Path tempDir) {
        CompileValidator validator = new CompileValidator();
        String invalidJava = """
                ```java
                public class Broken {
                    public static void main(String[] args) {
                        System.out.println("Missing quote)
                    }
                }
                ```
                """;
        ValidationResult result = validator.validate(invalidJava, tempDir);
        assertFalse(result.isPass());
    }
}
