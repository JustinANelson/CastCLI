package dev.justnels.castcli.memory;

import java.util.List;
import java.util.regex.Pattern;

/** Rejects common credentials before they enter durable shared memory. */
public final class MemorySecurity {
    private static final List<Pattern> SECRET_PATTERNS = List.of(
            Pattern.compile("(?i)\\b(api[_-]?key|access[_-]?token|client[_-]?secret|password)\\s*[:=]\\s*[^\\s]{8,}"),
            Pattern.compile("\\bsk-[A-Za-z0-9_-]{16,}"),
            Pattern.compile("-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----"));

    private MemorySecurity() { }

    public static void rejectSecrets(String content) {
        if (content != null && SECRET_PATTERNS.stream().anyMatch(pattern -> pattern.matcher(content).find())) {
            throw new IllegalArgumentException("Memory appears to contain a credential or private key; refusing to persist it");
        }
    }
}
