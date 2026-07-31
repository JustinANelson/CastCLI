package dev.justnels.castcli.security;

import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Hard privacy firewall enforcing context classification, secret redaction, path deny-globs,
 * and data boundary checks prior to cloud dispatch.
 */
public final class ContextFirewall {

    public static final List<String> DEFAULT_DENY_GLOBS = List.of(
            "**/credentials*.json", "**/*.pem", "**/*.key", "**/*.p12", "**/*.pfx",
            "**/.env", "**/.env.*", "**/shadow", "**/passwd", "**/.netrc");

    public record FirewallDecision(
            boolean allowed,
            String denialReason,
            String sanitizedPrompt,
            ContextClassification classification) {}

    private final List<PathMatcher> denyMatchers;

    public ContextFirewall() {
        this(DEFAULT_DENY_GLOBS);
    }

    public ContextFirewall(List<String> denyGlobs) {
        List<String> globs = denyGlobs == null || denyGlobs.isEmpty() ? DEFAULT_DENY_GLOBS : denyGlobs;
        List<PathMatcher> matchers = new ArrayList<>();
        for (String g : globs) {
            matchers.add(FileSystems.getDefault().getPathMatcher("glob:" + g));
            if (g.startsWith("**/")) {
                matchers.add(FileSystems.getDefault().getPathMatcher("glob:" + g.substring(3)));
            }
        }
        this.denyMatchers = List.copyOf(matchers);
    }

    public FirewallDecision inspect(String prompt, List<String> filePaths) {
        if (prompt == null) {
            prompt = "";
        }
        List<String> paths = filePaths == null ? List.of() : filePaths;

        // Check file deny-globs
        for (String pathStr : paths) {
            Path path = Path.of(pathStr);
            for (PathMatcher matcher : denyMatchers) {
                if (matcher.matches(path)) {
                    return new FirewallDecision(
                            false,
                            "File path '" + pathStr + "' matches privacy firewall deny-glob",
                            prompt,
                            ContextClassification.RESTRICTED);
                }
            }
        }

        // Check for raw unredactable private keys / certificates
        if (prompt.contains("-----BEGIN PRIVATE KEY-----") || prompt.contains("-----BEGIN RSA PRIVATE KEY-----")) {
            return new FirewallDecision(
                    false,
                    "Prompt contains raw unredactable private key material",
                    prompt,
                    ContextClassification.RESTRICTED);
        }

        // Perform secret redaction
        String sanitized = GuardrailFilter.filter(prompt);

        // Determine classification level
        ContextClassification classification;
        String lower = prompt.toLowerCase(Locale.ROOT);
        if (lower.contains("confidential") || lower.contains("secret") || lower.contains("password") || lower.contains("token")) {
            classification = ContextClassification.CONFIDENTIAL;
        } else if (lower.contains("internal") || lower.contains("private")) {
            classification = ContextClassification.INTERNAL;
        } else {
            classification = ContextClassification.PUBLIC;
        }

        return new FirewallDecision(true, null, sanitized, classification);
    }
}
