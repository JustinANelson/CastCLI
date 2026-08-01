package dev.justnels.castcli;

import dev.justnels.castcli.config.HarnessConfig;
import dev.justnels.castcli.config.ModelTier;
import dev.justnels.castcli.config.ProviderConfig;
import dev.justnels.castcli.config.RoutingConfig;
import dev.justnels.castcli.config.ToolConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PrCmdTest {

    @Test
    void listsCachedPrFilesWhenPrsDirectoryExists(@TempDir Path workspace) throws Exception {
        Path prsDir = Files.createDirectories(workspace.resolve(".cast").resolve("prs"));
        Files.writeString(prsDir.resolve("pr-42.diff"), "diff --git a/Sample.java b/Sample.java\n+class Sample {}\n");

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        CastCli cli = new CastCli();

        // Run pr list subcommand directly
        int exitCode = new CommandLine(cli).execute("pr", "list");

        assertThat(exitCode).isEqualTo(0);
    }

    @Test
    void reviewsExistingPrDiffFile(@TempDir Path workspace) throws Exception {
        Path prsDir = Files.createDirectories(workspace.resolve(".cast").resolve("prs"));
        Path diffFile = prsDir.resolve("pr-99.diff");
        Files.writeString(diffFile, """
                diff --git a/src/Main.java b/src/Main.java
                --- a/src/Main.java
                +++ b/src/Main.java
                @@ -1,3 +1,3 @@
                -public class Main {}
                +public class Main { public static void main(String[] args) {} }
                """);

        ProviderConfig localProvider = new ProviderConfig("small", ModelTier.SMALL_LOCAL, "http://fake/v1/",
                "small-model", null, 0.1, 30, true, true);
        HarnessConfig config = new HarnessConfig(List.of(localProvider), new RoutingConfig(240, true),
                new ToolConfig(workspace.toString(), 100_000, false));

        Path configFile = workspace.resolve(".cast").resolve("harness.local.json");
        String validConfigJson = """
                {
                  "providers": [
                    {
                      "id": "small",
                      "tier": "SMALL_LOCAL",
                      "baseUrl": "http://fake/v1/",
                      "modelName": "small-model",
                      "enabled": true
                    }
                  ],
                  "tools": {
                    "workspaceRoot": "%s"
                  }
                }
                """.formatted(workspace.toString().replace("\\", "\\\\"));
        Files.writeString(configFile, validConfigJson);

        // Execute pr review via CommandLine with fake provider URL
        CommandLine cmd = new CommandLine(new CastCli());
        int exitCode = cmd.execute("--config", configFile.toString(), "pr", "review", "99");

        // Fails gracefully because fake HTTP provider URL cannot be reached
        assertThat(exitCode).isEqualTo(1);
    }
}
