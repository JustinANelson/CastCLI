package dev.justnels.castcli.tools;

import dev.justnels.castcli.observability.CastTelemetry;
import io.opentelemetry.api.common.Attributes;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

/** Prompts on stdin/stdout for interactive CLI use before any write or shell-exec tool runs. */
public final class ConsoleApprovalGate implements ApprovalGate {
    private final BufferedReader in;
    private final PrintStream out;

    public ConsoleApprovalGate() {
        this(new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8)), System.out);
    }

    ConsoleApprovalGate(BufferedReader in, PrintStream out) {
        this.in = in;
        this.out = out;
    }

    @Override
    public synchronized boolean approve(String action, String detail) {
        try (var span = CastTelemetry.current().span("castcli.approval")
                .attribute("castcli.approval.action", action)
                .attribute("castcli.approval.detail.sha256", CastTelemetry.current().promptHash(detail))) {
        out.println();
        out.println("=== Approval required: " + action + " ===");
        out.println(detail);
        out.print("Allow? [y/N] ");
        out.flush();
        try {
            String line = in.readLine();
            boolean approved = line != null && (line.trim().equalsIgnoreCase("y") || line.trim().equalsIgnoreCase("yes"));
            span.attribute("castcli.approval.approved", approved);
            CastTelemetry.current().approval(approved, Attributes.builder()
                    .put("castcli.approval.action", action).build());
            return approved;
        } catch (Exception e) {
            span.error(e);
            CastTelemetry.current().approval(false, Attributes.builder()
                    .put("castcli.approval.action", action).build());
            return false;
        }
        }
    }
}

