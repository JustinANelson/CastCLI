package dev.justnels.castcli.orchestration;

import dev.justnels.castcli.config.ModelTier;
import dev.justnels.castcli.config.ProviderConfig;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class TokenUsageReportTest {
    private static final ProviderConfig SMALL = new ProviderConfig(
            "small", ModelTier.SMALL_LOCAL, "http://fake/v1/", "small-model", null, 0.1, 30, true, true, null, 0.0, 0.0);
    private static final ProviderConfig LARGE = new ProviderConfig(
            "large", ModelTier.LARGE_LOCAL, "http://fake/v1/", "large-model", null, 0.1, 30, true, true, null, 0.0, 0.0);
    private static final ProviderConfig CLOUD = new ProviderConfig(
            "cloud", ModelTier.FRONTIER_CLOUD, "http://fake/v1/", "cloud-model", null, 0.1, 30, true, true, null, 1.0, 2.0);

    @Test
    void emptyReportHasNoProvidersAndZeroTotals() {
        TokenUsageReport.Summary summary = new TokenUsageReport().summarize();

        assertThat(summary.byProvider()).isEmpty();
        assertThat(summary.cloudTokens()).isZero();
        assertThat(summary.localTokens()).isZero();
        assertThat(summary.totalTokens()).isZero();
        assertThat(summary.cloudShare()).isZero(); // must not divide by zero
        assertThat(summary.localMinusCloudTokens()).isZero();
    }

    @Test
    void compilesPerProviderTotalsAcrossMultipleCallsToTheSameProvider() {
        TokenUsageReport report = new TokenUsageReport();
        report.record(outcome(SMALL, 10, 5));
        report.record(outcome(SMALL, 20, 10));

        TokenUsageReport.Summary summary = report.summarize();

        assertThat(summary.byProvider()).hasSize(1);
        TokenUsageReport.ProviderUsage small = summary.byProvider().get(0);
        assertThat(small.providerId()).isEqualTo("small");
        assertThat(small.tier()).isEqualTo(ModelTier.SMALL_LOCAL);
        assertThat(small.calls()).isEqualTo(2);
        assertThat(small.inputTokens()).isEqualTo(30);
        assertThat(small.outputTokens()).isEqualTo(15);
        assertThat(small.totalTokens()).isEqualTo(45);
    }

    @Test
    void comparesCloudAgainstBothLocalTiersCombined() {
        TokenUsageReport report = new TokenUsageReport();
        report.record(outcome(SMALL, 10, 5));   // 15 local
        report.record(outcome(LARGE, 20, 10));  // 30 local
        report.record(outcome(CLOUD, 40, 20));  // 60 cloud

        TokenUsageReport.Summary summary = report.summarize();

        assertThat(summary.byProvider()).extracting(TokenUsageReport.ProviderUsage::providerId)
                .containsExactly("cloud", "large", "small"); // sorted by provider id
        assertThat(summary.localTokens()).isEqualTo(45);
        assertThat(summary.cloudTokens()).isEqualTo(60);
        assertThat(summary.totalTokens()).isEqualTo(105);
        assertThat(summary.cloudShare()).isCloseTo(60.0 / 105.0, within(1e-9));
        assertThat(summary.localMinusCloudTokens()).isEqualTo(45 - 60);
    }

    @Test
    void computesEstimatedCostPerProviderFromItsOwnRate() {
        TokenUsageReport report = new TokenUsageReport();
        report.record(outcome(CLOUD, 1_000_000, 1_000_000)); // $1 in + $2 out = $3

        TokenUsageReport.ProviderUsage cloud = report.summarize().byProvider().get(0);
        assertThat(cloud.estimatedCostUsd()).isCloseTo(3.0, within(1e-9));
    }

    @Test
    void recordIsSafeUnderConcurrentUse() throws Exception {
        TokenUsageReport report = new TokenUsageReport();
        int callsPerProvider = 200;

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Callable<Void>> jobs = new java.util.ArrayList<>();
            for (int i = 0; i < callsPerProvider; i++) {
                jobs.add(() -> {
                    report.record(outcome(SMALL, 1, 1));
                    return null;
                });
                jobs.add(() -> {
                    report.record(outcome(CLOUD, 1, 1));
                    return null;
                });
            }
            List<Future<Void>> futures = executor.invokeAll(jobs);
            for (Future<Void> future : futures) {
                future.get();
            }
        }

        TokenUsageReport.Summary summary = report.summarize();
        assertThat(summary.localTokens()).isEqualTo(callsPerProvider * 2L);
        assertThat(summary.cloudTokens()).isEqualTo(callsPerProvider * 2L);
    }

    private static HarnessOrchestrator.Outcome outcome(ProviderConfig provider, long inputTokens, long outputTokens) {
        return new HarnessOrchestrator.Outcome(
                provider, "answer", List.of(), List.of(), 0L, false,
                inputTokens, outputTokens, provider.estimatedCostUsd(inputTokens, outputTokens));
    }
}

