package dev.justnels.castcli.tools;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.List;
import java.util.stream.Stream;

public final class WorkspaceTools {
    private final Path root;
    private final long maxFileBytes;
    private final boolean writesAllowed;
    private final ApprovalGate approvalGate;

    public WorkspaceTools(Path root, long maxFileBytes) {
        this(root, maxFileBytes, false, AutoApprovalGate.INSTANCE);
    }

    public WorkspaceTools(Path root, long maxFileBytes, boolean writesAllowed, ApprovalGate approvalGate) {
        this.root = root.toAbsolutePath().normalize();
        this.maxFileBytes = maxFileBytes;
        this.writesAllowed = writesAllowed;
        this.approvalGate = approvalGate == null ? AutoApprovalGate.INSTANCE : approvalGate;
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

    @Tool("Lists files inside the workspace using a glob such as **/*.java")
    public List<String> listWorkspaceFiles(
            @P("glob matched against workspace-relative paths") String glob,
            @P("maximum number of results, from 1 to 500") int maxResults) throws IOException {
        int limit = checkedLimit(maxResults);
        PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + glob);
        try (Stream<Path> paths = Files.walk(root)) {
            return paths.filter(Files::isRegularFile)
                    .map(root::relativize)
                    .filter(matcher::matches)
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
                    .mapToObj(index -> relative + ":" + (index + 1) + ":" + lines.get(index).trim());
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
}

