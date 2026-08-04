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
 */
public record TaskRequest(String prompt, Workload workload, ModelTier requestedTier, boolean strict, boolean toolsDisabled) {
    public TaskRequest {
        if (prompt == null || prompt.isBlank()) {
            throw new IllegalArgumentException("prompt must not be blank");
        }
        dev.justnels.castcli.security.PromptSanitizer.validate(prompt);
        if (workload == null) {
            workload = Workload.AUTO;
        }
        if (strict && requestedTier == null) {
            throw new IllegalArgumentException("strict requires an explicit requestedTier");
        }
    }

    public TaskRequest(String prompt, Workload workload, ModelTier requestedTier, boolean strict) {
        this(prompt, workload, requestedTier, strict, false);
    }

    public TaskRequest(String prompt, Workload workload, ModelTier requestedTier) {
        this(prompt, workload, requestedTier, false, false);
    }
}

