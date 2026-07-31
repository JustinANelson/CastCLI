package dev.justnels.castcli.orchestration;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LocalContextCompressorTest {

    @Test
    void returnsOriginalTextWhenShort() {
        LocalContextCompressor compressor = new LocalContextCompressor(null);
        String shortText = "Short log content";
        assertThat(compressor.compress(shortText, 500)).isEqualTo(shortText);
    }

    @Test
    void compressesLongLogToTargetSize() {
        LocalContextCompressor compressor = new LocalContextCompressor(null);
        StringBuilder longLog = new StringBuilder();
        for (int i = 0; i < 200; i++) {
            longLog.append("Line ").append(i).append(": Normal operational log output\n");
        }
        longLog.append("ERROR: Critical failure on line 150 in Service.java\n");
        for (int i = 200; i < 400; i++) {
            longLog.append("Line ").append(i).append(": Normal operational log output\n");
        }

        String compressed = compressor.compress(longLog.toString(), 500);

        assertThat(compressed.length()).isLessThanOrEqualTo(500);
        assertThat(compressed).contains("ERROR");
    }
}
