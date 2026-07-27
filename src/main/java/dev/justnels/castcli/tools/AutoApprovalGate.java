package dev.justnels.castcli.tools;

import dev.justnels.castcli.observability.CastTelemetry;
import io.opentelemetry.api.common.Attributes;
/** Approves every request. Suitable for library defaults, tests, and unattended pipelines that
 * have already gated write access at the {@code ToolConfig.allowWrites}/{@code allowShellExec} level. */
public final class AutoApprovalGate implements ApprovalGate {
    public static final AutoApprovalGate INSTANCE = new AutoApprovalGate();

    @Override
    public boolean approve(String action, String detail) {
        try (var span = CastTelemetry.current().span("castcli.approval")
                .attribute("castcli.approval.action", action)
                .attribute("castcli.approval.mode", "automatic")
                .attribute("castcli.approval.approved", true)) {
            span.event("approval.auto_approved");
            CastTelemetry.current().approval(true, Attributes.builder()
                    .put("castcli.approval.action", action)
                    .put("castcli.approval.mode", "automatic").build());
        return true;
        }
    }
}

