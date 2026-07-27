package dev.justnels.castcli.doctor;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BuildInfoTest {

    @Test
    void versionIsNeverBlank() {
        assertThat(BuildInfo.version()).isNotBlank();
    }
}
