package dev.justnels.castcli.index;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.List;

/**
 * Parses and evaluates .gitignore rules for workspace files.
 */
public final class GitIgnoreMatcher {

    private record Rule(boolean negation, List<PathMatcher> matchers) {
        boolean matches(Path relativePath) {
            for (PathMatcher matcher : matchers) {
                if (matcher.matches(relativePath)) {
                    return true;
                }
            }
            return false;
        }
    }

    private final List<Rule> rules;

    private GitIgnoreMatcher(List<Rule> rules) {
        this.rules = List.copyOf(rules);
    }

    public static GitIgnoreMatcher load(Path workspaceRoot) {
        List<Rule> compiledRules = new ArrayList<>();
        Path gitIgnorePath = workspaceRoot.resolve(".gitignore").toAbsolutePath().normalize();
        if (Files.isRegularFile(gitIgnorePath)) {
            try {
                List<String> lines = Files.readAllLines(gitIgnorePath, StandardCharsets.UTF_8);
                for (String line : lines) {
                    Rule rule = parseLine(line);
                    if (rule != null) {
                        compiledRules.add(rule);
                    }
                }
            } catch (IOException ignored) {
                // Return empty matcher if unreadable
            }
        }
        return new GitIgnoreMatcher(compiledRules);
    }

    public boolean isIgnored(String relativePathStr, boolean isDir) {
        String normalized = relativePathStr.replace('\\', '/');
        if (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        Path path = Path.of(normalized);

        boolean ignored = false;
        for (Rule rule : rules) {
            if (rule.matches(path)) {
                ignored = !rule.negation();
            }
        }
        return ignored;
    }

    private static Rule parseLine(String rawLine) {
        String line = rawLine.trim();
        if (line.isEmpty() || line.startsWith("#")) {
            return null;
        }

        boolean negation = false;
        if (line.startsWith("!")) {
            negation = true;
            line = line.substring(1).trim();
        }

        if (line.isEmpty()) {
            return null;
        }

        if (line.endsWith("/")) {
            line = line.substring(0, line.length() - 1);
        }

        boolean anchored = false;
        if (line.startsWith("/")) {
            anchored = true;
            line = line.substring(1);
        } else if (line.contains("/")) {
            anchored = true;
        }

        List<PathMatcher> matchers = new ArrayList<>();
        List<String> globPatterns = new ArrayList<>();

        if (anchored) {
            globPatterns.add(line);
            globPatterns.add(line + "/**");
            if (!line.startsWith("**/")) {
                globPatterns.add("**/" + line);
                globPatterns.add("**/" + line + "/**");
            }
        } else {
            globPatterns.add(line);
            globPatterns.add(line + "/**");
            globPatterns.add("**/" + line);
            globPatterns.add("**/" + line + "/**");
        }

        for (String pattern : globPatterns) {
            try {
                matchers.add(FileSystems.getDefault().getPathMatcher("glob:" + pattern));
            } catch (Exception ignored) {
            }
        }

        if (matchers.isEmpty()) {
            return null;
        }
        return new Rule(negation, matchers);
    }
}
