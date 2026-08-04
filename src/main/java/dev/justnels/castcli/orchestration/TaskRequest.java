package dev.justnels.castcli.orchestration;

import dev.justnels.castcli.config.ModelTier;

/**
 * @param toolsDisabled Skips tool selection for this call entirely, regardless of what marker words
 *                      the prompt contains. Set this for calls that are pure text generation over
 *                      already-gathered content (plan/report generation, log compression, memory
 *                      consolidation) -- {@code DefaultToolSelector} scans the whole prompt text for
 *                      trigger words, including any embedded prior output, so a report prompt that
 *                      quotes a worker's "wrote to /docs/foo.md" can otherwise get tools offered by
 *                      accident. A local model offered tools it has no real use for will sometimes
 *                      emit an unexecuted tool-call-shaped JSON blob as its answer instead of prose.
 * @param requestedProviderId Exact provider ID to select. When non-null, routing does not substitute
 *                            another provider. Mutually exclusive with {@code requestedTier}.
 */
public record TaskRequest(
        String prompt,
        Workload workload,
        ModelTier requestedTier,
        String requestedProviderId,
        boolean strict,
        boolean toolsDisabled) {
    public TaskRequest {
        if (prompt == null || prompt.isBlank()) {
            throw new IllegalArgumentException("prompt must not be blank");
        }
        dev.justnels.castcli.security.PromptSanitizer.validate(prompt);
        if (workload == null) {
            workload = Workload.AUTO;
        }
        requestedProviderId = requestedProviderId == null || requestedProviderId.isBlank()
                ? null : requestedProviderId.trim();
        if (requestedTier != null && requestedProviderId != null) {
            throw new IllegalArgumentException("requestedTier and requestedProviderId are mutually exclusive");
        }
        if (strict && requestedTier == null && requestedProviderId == null) {
            throw new IllegalArgumentException("strict requires an explicit requestedTier or requestedProviderId");
        }
    }

    public TaskRequest(String prompt, Workload workload, ModelTier requestedTier,
                       boolean strict, boolean toolsDisabled) {
        this(prompt, workload, requestedTier, null, strict, toolsDisabled);
    }

    public TaskRequest(String prompt, Workload workload, ModelTier requestedTier, boolean strict) {
        this(prompt, workload, requestedTier, null, strict, false);
    }

    public TaskRequest(String prompt, Workload workload, ModelTier requestedTier) {
        this(prompt, workload, requestedTier, null, false, false);
    }
}
