package dev.justnels.castcli.index;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class GitIgnoreMatcherTest {
    @TempDir
    Path workspace;

    @Test
    void matchesStandardGitIgnorePatterns() throws IOException {
        Files.writeString(workspace.resolve(".gitignore"), """
                # Comments and blank lines should be ignored
                
                target/
                *.log
                credentials.json
                !important.log
                """);

        GitIgnoreMatcher matcher = GitIgnoreMatcher.load(workspace);

        assertThat(matcher.isIgnored("target/app.jar", false)).isTrue();
        assertThat(matcher.isIgnored("build/app.log", false)).isTrue();
        assertThat(matcher.isIgnored("credentials.json", false)).isTrue();
        assertThat(matcher.isIgnored("sub/credentials.json", false)).isTrue();
        assertThat(matcher.isIgnored("important.log", false)).isFalse();
        assertThat(matcher.isIgnored("src/Main.java", false)).isFalse();
    }
}
