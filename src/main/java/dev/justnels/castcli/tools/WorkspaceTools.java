package dev.justnels.castcli.tools;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

public final class WorkspaceTools {
    private static final int MAX_SEARCH_LINE_CHARS = 400;
    private static final List<String> MANDATORY_DENY_GLOBS = List.of(
            ".env", ".env.*", "**/.env", "**/.env.*", "**/*.env",
            ".git/**", "**/.git/**", ".cast/**", "**/.cast/**",
            "**/build/**", "**/.gradle/**", "**/out/**", "**/target/**", "**/node_modules/**",
            "config/harness.local.json", "**/harness.local.json",
            "**/credentials.json", "**/credentials*.json", "**/*-credentials.*", "**/*_credentials.*",
            "**/secret.*", "**/secrets.*", "**/*-secret.*", "**/*_secret.*",
            "**/password.*", "**/passwords.*", "**/*-password.*", "**/*_password.*",
            "**/token.*", "**/tokens.*", "**/*-token.*", "**/*_token.*",
            "**/*.pem", "**/*.key", "**/*.p12", "**/*.pfx", "**/*.jks", "**/*.keystore",
            ".npmrc", "**/.npmrc", ".pypirc", "**/.pypirc", ".netrc", "**/.netrc"
    );

    private final Path root;
    private final long maxFileBytes;
    private final boolean writesAllowed;
    private final ApprovalGate approvalGate;
    private final List<PathMatcher> denyMatchers;
    private final Path realRoot;

    public WorkspaceTools(Path root, long maxFileBytes) {
        this(root, maxFileBytes, false, DenyApprovalGate.INSTANCE);
    }

    public WorkspaceTools(Path root, long maxFileBytes, boolean writesAllowed, ApprovalGate approvalGate) {
        this.root = root.toAbsolutePath().normalize();
        this.maxFileBytes = maxFileBytes;
        this.writesAllowed = writesAllowed;
        this.approvalGate = approvalGate == null ? DenyApprovalGate.INSTANCE : approvalGate;
        this.denyMatchers = buildMatchers(MANDATORY_DENY_GLOBS);
        this.realRoot = realPathOrNormalized(this.root);
    }

    @Tool("Reads a UTF-8 text file inside the configured workspace")
    public String readWorkspaceFile(@P("workspace-relative file path") String path) throws IOException {
        Path resolved = resolve(path);
        if (!Files.isRegularFile(resolved)) {
            throw new IOException("Not a regular file: " + path);
        }
        long size = Files.size(resolved);
        if (size > maxFileBytes) {
            throw new IOException("File exceeds configured limit of " + maxFileBytes + " bytes");
        }
        return Files.readString(resolved, StandardCharsets.UTF_8);
    }

    public String readWorkspaceFile(String path, int maxChars) throws IOException {
        if (maxChars < 1) throw new IllegalArgumentException("maxChars must be positive");
        Path resolved = resolve(path);
        if (!Files.isRegularFile(resolved)) throw new IOException("Not a regular file: " + path);
        if (Files.size(resolved) > maxFileBytes) {
            throw new IOException("File exceeds configured limit of " + maxFileBytes + " bytes");
        }
        int limit = (int) Math.min(maxChars, Math.min(maxFileBytes, Integer.MAX_VALUE - 1L));
        StringBuilder text = new StringBuilder(Math.min(limit + 1, 8_192));
        char[] buffer = new char[Math.min(limit + 1, 8_192)];
        try (var reader = Files.newBufferedReader(resolved, StandardCharsets.UTF_8)) {
            int read;
            while (text.length() <= limit && (read = reader.read(buffer)) >= 0) {
                int remaining = limit + 1 - text.length();
                text.append(buffer, 0, Math.min(read, remaining));
            }
        }
        if (text.length() <= limit) return text.toString();
        String marker = "\n...[file content omitted by retrieval budget]";
        if (limit <= marker.length()) return text.substring(0, limit);
        return text.substring(0, limit - marker.length()) + marker;
    }

    @Tool("Lists files inside the workspace using a glob such as **/*.java")
    public List<String> listWorkspaceFiles(
            @P("glob matched against workspace-relative paths") String glob,
            @P("maximum number of results, from 1 to 500") int maxResults) throws IOException {
        int limit = checkedLimit(maxResults);
        PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + glob);
        PathMatcher rootMatcher = glob.startsWith("**/")
                ? FileSystems.getDefault().getPathMatcher("glob:" + glob.substring(3))
                : matcher;
        try (Stream<Path> paths = Files.walk(root)) {
            return paths.filter(Files::isRegularFile)
                    .filter(this::isAllowed)
                    .map(root::relativize)
                    .filter(path -> matcher.matches(path) || rootMatcher.matches(path))
                    .limit(limit)
                    .map(Path::toString)
                    .toList();
        }
    }

    @Tool("Searches UTF-8 workspace files for a literal string and returns file:line matches")
    public List<String> searchWorkspace(
            @P("literal text to find") String query,
            @P("maximum number of matches, from 1 to 500") int maxResults) throws IOException {
        if (query == null || query.isEmpty()) {
            throw new IllegalArgumentException("query must not be empty");
        }
        int limit = checkedLimit(maxResults);
        try (Stream<Path> paths = Files.walk(root)) {
            return paths.filter(Files::isRegularFile)
                    .filter(this::isAllowed)
                    .filter(path -> isSmallEnough(path))
                    .flatMap(path -> matches(path, query))
                    .limit(limit)
                    .toList();
        }
    }

    @Tool("Writes UTF-8 text content to a file inside the configured workspace, creating parent directories as needed. "
            + "Requires tools.allowWrites=true and, unless auto-approved, interactive confirmation.")
    public String writeWorkspaceFile(
            @P("workspace-relative file path") String path,
            @P("full UTF-8 text content to write") String content) throws IOException {
        if (!writesAllowed) {
            return "Write denied: tools.allowWrites is false in the harness configuration.";
        }
        Path resolved = resolve(path);
        long newSize = content == null ? 0 : content.getBytes(StandardCharsets.UTF_8).length;
        if (newSize > maxFileBytes) {
            throw new IOException("Content exceeds configured limit of " + maxFileBytes + " bytes");
        }
        boolean existed = Files.isRegularFile(resolved);
        String detail = (existed ? "Overwrite " : "Create ") + path + " (" + newSize + " bytes)";
        if (!approvalGate.approve("write file", detail)) {
            dev.justnels.castcli.audit.AuditLogger.getInstance().log("FILE_WRITE", "harness", "writeWorkspaceFile", path, "DENIED", java.util.Map.of("detail", detail));
            return "Write denied by approval gate: " + path;
        }
        Files.createDirectories(resolved.getParent());
        Files.writeString(resolved, content == null ? "" : content, StandardCharsets.UTF_8);
        dev.justnels.castcli.audit.AuditLogger.getInstance().log("FILE_WRITE", "harness", "writeWorkspaceFile", path, "SUCCESS", java.util.Map.of("bytes", String.valueOf(newSize)));
        return (existed ? "Overwrote " : "Created ") + path + " (" + newSize + " bytes)";
    }

    private Path resolve(String path) {
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("Path must not be null or blank");
        }
        Path normalizedInput = Path.of(path).normalize();
        if (normalizedInput.isAbsolute()) {
            throw new SecurityException("Absolute paths are not allowed: " + path);
        }
        Path resolved = root.resolve(normalizedInput).normalize();
        if (!resolved.startsWith(root)) {
            throw new SecurityException("Path escapes workspace: " + path);
        }
        Path relative = root.relativize(resolved);
        if (isDenied(relative)) {
            throw new SecurityException("Access to sensitive workspace path is denied: " + path);
        }
        try {
            Path rootReal = Files.exists(root) ? root.toRealPath() : root.toAbsolutePath().normalize();
            Path checkPath = resolved;
            while (checkPath != null && !Files.exists(checkPath) && checkPath.startsWith(root)) {
                checkPath = checkPath.getParent();
            }
            if (checkPath != null && Files.exists(checkPath)) {
                Path realPath = checkPath.toRealPath();
                if (!realPath.startsWith(rootReal)) {
                    throw new SecurityException("Path escapes workspace via symlink: " + path);
                }
            }
        } catch (IOException e) {
            throw new SecurityException("Could not verify path security: " + path, e);
        }
        return resolved;
    }

    private boolean isAllowed(Path path) {
        Path relative = root.relativize(path.toAbsolutePath().normalize());
        if (isDenied(relative)) {
            return false;
        }
        try {
            return path.toRealPath().startsWith(realRoot);
        } catch (IOException | SecurityException ignored) {
            return false;
        }
    }

    private boolean isDenied(Path relative) {
        Path lowerCase = Path.of(relative.toString().toLowerCase(Locale.ROOT));
        return denyMatchers.stream().anyMatch(matcher ->
                matcher.matches(relative) || matcher.matches(lowerCase));
    }

    private static Path realPathOrNormalized(Path path) {
        try {
            return Files.exists(path) ? path.toRealPath() : path.toAbsolutePath().normalize();
        } catch (IOException e) {
            throw new IllegalArgumentException("Could not resolve workspace root: " + path, e);
        }
    }

    private static List<PathMatcher> buildMatchers(List<String> globs) {
        List<PathMatcher> matchers = new ArrayList<>();
        for (String glob : globs) {
            matchers.add(FileSystems.getDefault().getPathMatcher("glob:" + glob));
            if (glob.startsWith("**/")) {
                matchers.add(FileSystems.getDefault().getPathMatcher("glob:" + glob.substring(3)));
            }
        }
        return List.copyOf(matchers);
    }

    private boolean isSmallEnough(Path path) {
        try {
            return Files.size(path) <= maxFileBytes;
        } catch (IOException ignored) {
            return false;
        }
    }

    private Stream<String> matches(Path path, String query) {
        try {
            List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
            String relative = root.relativize(path).toString();
            return java.util.stream.IntStream.range(0, lines.size())
                    .filter(index -> lines.get(index).contains(query))
                    .mapToObj(index -> relative + ":" + (index + 1) + ":"
                            + boundedSearchLine(lines.get(index).trim()));
        } catch (IOException | RuntimeException ignored) {
            return Stream.empty();
        }
    }

    private static int checkedLimit(int maxResults) {
        if (maxResults < 1 || maxResults > 500) {
            throw new IllegalArgumentException("maxResults must be between 1 and 500");
        }
        return maxResults;
    }

    private static String boundedSearchLine(String line) {
        if (line.length() <= MAX_SEARCH_LINE_CHARS) return line;
        return line.substring(0, MAX_SEARCH_LINE_CHARS) + "...[truncated]";
    }
}

