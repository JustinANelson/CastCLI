package dev.justnels.castcli.persistence;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

public final class AtomicFileWriter {
    private AtomicFileWriter() { }

    public static Path backupPath(Path target) {
        return target.resolveSibling(target.getFileName() + ".bak");
    }

    public static void write(Path target, byte[] content) throws IOException {
        Path absolute = target.toAbsolutePath().normalize();
        Path parent = absolute.getParent();
        if (parent == null) throw new IOException("Target must have a parent directory");
        Files.createDirectories(parent);
        Path temp = Files.createTempFile(parent, absolute.getFileName() + ".", ".tmp");
        boolean moved = false;
        try {
            writeDurable(temp, content);
            backupExisting(absolute);
            replace(temp, absolute);
            moved = true;
            forceDirectory(parent);
        } finally {
            if (!moved) Files.deleteIfExists(temp);
        }
    }

    private static void writeDurable(Path path, byte[] content) throws IOException {
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING)) {
            ByteBuffer buffer = ByteBuffer.wrap(content);
            while (buffer.hasRemaining()) channel.write(buffer);
            channel.force(true);
        }
    }

    private static void backupExisting(Path target) throws IOException {
        if (!Files.isRegularFile(target)) return;
        Path backup = backupPath(target);
        Files.copy(target, backup, StandardCopyOption.REPLACE_EXISTING);
        try (FileChannel channel = FileChannel.open(backup, StandardOpenOption.WRITE)) {
            channel.force(true);
        }
    }

    private static void replace(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void forceDirectory(Path directory) {
        try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
            channel.force(true);
        } catch (IOException | UnsupportedOperationException ignored) {
            // Directory fsync is not supported on every platform or filesystem.
        }
    }
}
