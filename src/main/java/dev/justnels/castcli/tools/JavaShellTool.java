package dev.justnels.castcli.tools;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import jdk.jshell.JShell;
import jdk.jshell.SnippetEvent;

import java.util.List;

public final class JavaShellTool {
    private final boolean enabled;

    public JavaShellTool(boolean enabled) {
        this.enabled = enabled;
    }

    @Tool("Evaluates a Java snippet in JShell. Use only for calculation and isolated Java experiments.")
    public String evaluateJava(@P("Java expression, statement, declaration, or import") String snippet) {
        if (!enabled) {
            return "JShell is disabled. Set tools.jshellEnabled=true only in a trusted environment.";
        }
        try (JShell shell = JShell.create()) {
            List<SnippetEvent> events = shell.eval(snippet);
            return events.stream()
                    .map(event -> event.status() + (event.value() == null ? "" : ": " + event.value())
                            + (event.exception() == null ? "" : ": " + event.exception()))
                    .reduce((left, right) -> left + System.lineSeparator() + right)
                    .orElse("No JShell result");
        }
    }
}

