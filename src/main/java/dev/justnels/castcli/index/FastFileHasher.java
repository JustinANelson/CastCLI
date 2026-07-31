package dev.justnels.castcli.index;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * High-performance, low-allocation file digest computation for incremental workspace indexing.
 * Streams file bytes directly into SHA-256 without instantiating intermediate Strings or line lists,
 * automatically normalizing line endings (CRLF to LF) on the fly.
 */
public final class FastFileHasher {

    private FastFileHasher() {
    }

    public static String hashFile(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] readBuffer = new byte[8192];
            byte[] normBuffer = new byte[8192];
            int normCount = 0;
            try (InputStream in = Files.newInputStream(file)) {
                int bytesRead;
                while ((bytesRead = in.read(readBuffer)) != -1) {
                    for (int i = 0; i < bytesRead; i++) {
                        byte b = readBuffer[i];
                        if (b != '\r') {
                            normBuffer[normCount++] = b;
                            if (normCount == normBuffer.length) {
                                digest.update(normBuffer, 0, normCount);
                                normCount = 0;
                            }
                        }
                    }
                }
                if (normCount > 0) {
                    digest.update(normBuffer, 0, normCount);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm not available", e);
        }
    }
}
