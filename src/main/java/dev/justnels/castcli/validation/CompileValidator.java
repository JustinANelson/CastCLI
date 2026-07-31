package dev.justnels.castcli.validation;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic validator enforcing Java syntax and compilation validity on model code outputs.
 */
public final class CompileValidator implements ValidationContract {

    private static final Pattern JAVA_CODE_BLOCK = Pattern.compile("```(?:java)?\\s*(.*?)```", Pattern.DOTALL);
    private static final Pattern CLASS_NAME_PATTERN = Pattern.compile("(?:public\\s+)?class\\s+(\\w+)");

    @Override
    public String name() {
        return "CompileValidator";
    }

    @Override
    public ValidationResult validate(String modelOutput, Path workspaceRoot) {
        long startTime = System.currentTimeMillis();
        if (modelOutput == null || modelOutput.isBlank()) {
            long duration = System.currentTimeMillis() - startTime;
            return ValidationResult.fail(name(), "Empty output", "Please provide a valid code response.", duration);
        }

        List<String> codeSnippets = extractJavaCode(modelOutput);
        if (codeSnippets.isEmpty()) {
            // Check for un-fenced raw Java code
            if (modelOutput.contains("class ") && modelOutput.contains("{")) {
                codeSnippets.add(modelOutput);
            } else {
                long duration = System.currentTimeMillis() - startTime;
                return ValidationResult.pass(name(), duration);
            }
        }

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            // Fallback basic syntax check
            for (String snippet : codeSnippets) {
                ValidationResult basic = checkBasicBraceBalance(snippet, startTime);
                if (!basic.isPass()) {
                    return basic;
                }
            }
            long duration = System.currentTimeMillis() - startTime;
            return ValidationResult.pass(name(), duration);
        }

        List<String> errors = new ArrayList<>();
        for (String snippet : codeSnippets) {
            String className = extractClassName(snippet);
            if (className == null) {
                className = "DraftCode";
                snippet = "public class DraftCode {\n" + snippet + "\n}";
            }

            DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
            JavaFileObject fileObj = new StringJavaFileObject(className, snippet);

            JavaCompiler.CompilationTask task = compiler.getTask(
                    null, null, diagnostics, List.of("-proc:none"), null, List.of(fileObj));
            Boolean success = task.call();

            if (Boolean.FALSE.equals(success)) {
                for (Diagnostic<? extends JavaFileObject> diag : diagnostics.getDiagnostics()) {
                    if (diag.getKind() == Diagnostic.Kind.ERROR) {
                        errors.add("Line " + diag.getLineNumber() + ": " + diag.getMessage(null));
                    }
                }
            }
        }

        long duration = System.currentTimeMillis() - startTime;
        if (!errors.isEmpty()) {
            String diagStr = String.join("; ", errors);
            String hint = "Your previous code failed compilation: " + diagStr + ". Correct the syntax and imports.";
            return ValidationResult.fail(name(), diagStr, hint, duration);
        }

        return ValidationResult.pass(name(), duration);
    }

    private static ValidationResult checkBasicBraceBalance(String snippet, long startTime) {
        int openBraces = 0;
        for (char c : snippet.toCharArray()) {
            if (c == '{') openBraces++;
            else if (c == '}') openBraces--;
        }
        long duration = System.currentTimeMillis() - startTime;
        if (openBraces != 0) {
            return ValidationResult.fail("CompileValidator",
                    "Unbalanced curly braces (balance count: " + openBraces + ")",
                    "Fix curly brace opening and closing pairs in your code.", duration);
        }
        return ValidationResult.pass("CompileValidator", duration);
    }

    private static List<String> extractJavaCode(String text) {
        List<String> snippets = new ArrayList<>();
        Matcher matcher = JAVA_CODE_BLOCK.matcher(text);
        while (matcher.find()) {
            snippets.add(matcher.group(1).trim());
        }
        return snippets;
    }

    private static String extractClassName(String code) {
        Matcher matcher = CLASS_NAME_PATTERN.matcher(code);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return null;
    }

    private static final class StringJavaFileObject extends SimpleJavaFileObject {
        private final String code;

        StringJavaFileObject(String name, String code) {
            super(URI.create("string:///" + name.replace('.', '/') + Kind.SOURCE.extension), Kind.SOURCE);
            this.code = code;
        }

        @Override
        public CharSequence getCharContent(boolean ignoreEncodingErrors) {
            return code;
        }
    }
}
