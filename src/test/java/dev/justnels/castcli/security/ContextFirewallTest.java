package dev.justnels.castcli.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContextFirewallTest {

    @Test
    void allowsNormalPromptWithPublicClassification() {
        ContextFirewall firewall = new ContextFirewall();
        ContextFirewall.FirewallDecision decision = firewall.inspect("What time is it in Tokyo?", List.of("src/Main.java"));

        assertTrue(decision.allowed());
        assertEquals(ContextClassification.PUBLIC, decision.classification());
        assertEquals("What time is it in Tokyo?", decision.sanitizedPrompt());
    }

    @Test
    void classifiesConfidentialPromptAndRedactsSecrets() {
        ContextFirewall firewall = new ContextFirewall();
        ContextFirewall.FirewallDecision decision = firewall.inspect("Here is a secret token ghp_1234567890abcdef1234567890abcdef", List.of());

        assertTrue(decision.allowed());
        assertEquals(ContextClassification.CONFIDENTIAL, decision.classification());
        assertTrue(decision.sanitizedPrompt().contains("[REDACTED"));
    }

    @Test
    void blocksDeniedFileGlobs() {
        ContextFirewall firewall = new ContextFirewall();
        ContextFirewall.FirewallDecision decision = firewall.inspect("Read file", List.of("config/credentials.json"));

        assertFalse(decision.allowed());
        assertEquals(ContextClassification.RESTRICTED, decision.classification());
        assertTrue(decision.denialReason().contains("matches privacy firewall deny-glob"));
    }

    @Test
    void blocksUnredactablePrivateKeys() {
        ContextFirewall firewall = new ContextFirewall();
        ContextFirewall.FirewallDecision decision = firewall.inspect("Key: -----BEGIN PRIVATE KEY-----\nMIIE...", List.of());

        assertFalse(decision.allowed());
        assertEquals(ContextClassification.RESTRICTED, decision.classification());
        assertTrue(decision.denialReason().contains("unredactable private key"));
    }

    @Test
    void savesEgressManifest(@TempDir Path tempDir) throws IOException {
        EgressManifest manifest = new EgressManifest(
                "trace-101", null, "cloud-openai", "gpt-4o", ContextClassification.CONFIDENTIAL,
                2, 1024, 250, "hash123", List.of("fileHash1", "fileHash2"), true);

        Path savedFile = manifest.saveTo(tempDir.resolve(".cast/egress"));
        assertTrue(Files.isRegularFile(savedFile));
        String json = Files.readString(savedFile);
        assertTrue(json.contains("trace-101"));
        assertTrue(json.contains("cloud-openai"));
        assertTrue(json.contains("gpt-4o"));
    }
}
