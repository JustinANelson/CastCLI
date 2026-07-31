package dev.justnels.castcli.agent;

import dev.justnels.castcli.config.HarnessConfig;
import dev.justnels.castcli.config.ModelTier;
import dev.justnels.castcli.memory.SessionAction;
import dev.justnels.castcli.memory.SessionMemorySummarizer;
import dev.justnels.castcli.memory.SqliteMemoryStore;
import dev.justnels.castcli.orchestration.CostSavingsEstimator;
import dev.justnels.castcli.orchestration.HarnessOrchestrator;
import dev.justnels.castcli.orchestration.TaskRequest;
import dev.justnels.castcli.orchestration.TokenUsageReport;
import dev.justnels.castcli.orchestration.Workload;
import dev.justnels.castcli.observability.CastTelemetry;
import io.opentelemetry.context.Context;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.DoubleAdder;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Hierarchical PM/skilled-labor pipeline. A FRONTIER_CLOUD model decomposes the goal into a plan,
 * independent worker subtasks run concurrently in waves, a REVIEWER's rejection triggers one bounded
 * rework pass of the wave it reviewed, progress is checkpointed after every wave for crash recovery,
 * and prior-subtask context handed to later workers is capped instead of growing without bound.
 */
public final class AgentTeam {
    private static final Pattern SUBTASK_PATTERN = Pattern.compile(
            "TITLE:\\s*(.*?)\\s*\nROLE:\\s*(CODER|TESTER|REVIEWER|GENERAL_LABOR)\\s*\nPROMPT:\\s*(.*?)(?=\nTITLE:|\\z)",
            Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    private static final Pattern REJECTED_PATTERN = Pattern.compile("VERDICT:\\s*REJECTED", Pattern.CASE_INSENSITIVE);
    private static final Set<AgentRole> PARALLEL_ROLES = Set.of(AgentRole.CODER, AgentRole.GENERAL_LABOR);
    private static final int MAX_PER_SUBTASK_CONTEXT_CHARS = 4_000;
    private static final int SUMMARY_CONTEXT_CHARS = 150;

    private final HarnessConfig config;
    private final HarnessOrchestrator orchestrator;
    private final CheckpointStore checkpointStore;
    private final CostSavingsEstimator savingsEstimator;
    private final SessionMemorySummarizer sessionSummarizer;

    public AgentTeam(HarnessConfig config) {
        this(config, new HarnessOrchestrator(config));
    }

    public AgentTeam(HarnessConfig config, HarnessOrchestrator orchestrator) {
        this(config, orchestrator, new CheckpointStore(Path.of(".cast", "checkpoints")));
    }

    public AgentTeam(HarnessConfig config, HarnessOrchestrator orchestrator, CheckpointStore checkpointStore) {
        this.config = config;
        this.orchestrator = orchestrator;
        this.checkpointStore = checkpointStore;
        this.savingsEstimator = new CostSavingsEstimator(config);
        if (config.memory().enabled()) {
            Path workspace = Path.of(config.tools().workspaceRoot()).toAbsolutePath().normalize();
            Path configuredMemory = Path.of(config.memory().databasePath());
            Path dbPath = configuredMemory.isAbsolute() ? configuredMemory : workspace.resolve(configuredMemory);
            this.sessionSummarizer = new SessionMemorySummarizer(
                    new SqliteMemoryStore(dbPath), orchestrator, config.memory().defaultNamespace());
        } else {
            this.sessionSummarizer = null;
        }
    }

    /** Mutable per-run accumulators, folded across every PM and worker call in a single commission run. */
    private static final class RunMetrics {
        final AtomicLong inputTokens = new AtomicLong();
        final AtomicLong outputTokens = new AtomicLong();
        final DoubleAdder costUsd = new DoubleAdder();
        final AtomicLong tokensOffloadedToLocal = new AtomicLong();
        final DoubleAdder frontierCostAvoidedUsd = new DoubleAdder();
        final TokenUsageReport tokenUsageReport = new TokenUsageReport();
    }

    public CommissioningResult commission(String goal) {
        return commission(goal, null);
    }

    /** Resumes a prior run from a checkpoint file written by {@link #commission(String)} if {@code resumeFrom}
     * is non-null; completed subtasks are skipped and only the remaining plan re-executes. */
    public CommissioningResult commission(String goal, Path resumeFrom) {
        try (var span = CastTelemetry.current().span("castcli.agent.commission")
                .attribute("castcli.agent.resume", resumeFrom != null)) {
            if (goal != null) CastTelemetry.current().annotatePrompt(span, goal);
            CommissioningResult result = commissionCore(goal, resumeFrom);
            span.attribute("castcli.agent.duration_ms", result.totalDurationMs())
                    .attribute("gen_ai.usage.input_tokens", result.totalInputTokens())
                    .attribute("gen_ai.usage.output_tokens", result.totalOutputTokens())
                    .attribute("castcli.estimated.cost", result.estimatedCostUsd());
            return result;
        }
    }

    private CommissioningResult commissionCore(String goal, Path resumeFrom) {
        long startTime = System.currentTimeMillis();
        RunMetrics metrics = new RunMetrics();

        ProjectPlan plan;
        List<SubTask> completed = new ArrayList<>();
        String effectiveGoal = goal;
        if (resumeFrom != null) {
            Checkpoint checkpoint = loadCheckpoint(goal, resumeFrom);
            effectiveGoal = checkpoint.goal();
            plan = checkpoint.plan();
            completed.addAll(checkpoint.completedTasks());
        } else {
            if (goal == null || goal.isBlank()) {
                throw new IllegalArgumentException("goal must not be blank when not resuming from a checkpoint");
            }
            plan = generatePlan(goal, metrics);
        }
        String finalGoal = effectiveGoal;

        Set<Integer> alreadyDoneIds = completed.stream().map(SubTask::id).collect(Collectors.toSet());
        List<List<SubTask>> waves = buildWaves(plan.subtasks());
        List<SubTask> lastCodeWave = null;
        Path checkpointPath = checkpointStore.pathFor(finalGoal);

        for (List<SubTask> wave : waves) {
            if (wave.stream().allMatch(t -> alreadyDoneIds.contains(t.id()))) {
                if (isCodeWave(wave)) {
                    lastCodeWave = resolveFromCompleted(completed, wave);
                }
                continue;
            }

            List<SubTask> finishedWave = executeWave(wave, completed, Map.of(), metrics);
            completed.addAll(finishedWave);
            checkpointPath = persistCheckpoint(finalGoal, plan, completed);

            if (isCodeWave(wave)) {
                lastCodeWave = finishedWave;
                continue;
            }

            if (isReviewWave(wave) && lastCodeWave != null) {
                SubTask review = finishedWave.get(0);
                int reworkCount = 0;
                while (reworkCount < config.routing().maxReworkIterations() && isRejected(review)) {
                    reworkCount++;
                    String feedback = review.output();
                    int reviewId = review.id();

                    List<Integer> codeIds = lastCodeWave.stream().map(SubTask::id).collect(Collectors.toList());
                    completed.removeIf(t -> codeIds.contains(t.id()) || t.id() == reviewId);

                    Map<Integer, String> reworkNotes = new HashMap<>();
                    for (SubTask codeTask : lastCodeWave) {
                        reworkNotes.put(codeTask.id(), "\n\nPRIOR ATTEMPT (rejected by reviewer):\n" + codeTask.output()
                                + "\n\nREVIEWER FEEDBACK TO ADDRESS:\n" + feedback
                                + "\n\nRevise your work to resolve this feedback.");
                    }
                    List<SubTask> reworked = executeWave(lastCodeWave, completed, reworkNotes, metrics);
                    completed.addAll(reworked);
                    lastCodeWave = reworked;
                    checkpointPath = persistCheckpoint(finalGoal, plan, completed);

                    List<SubTask> reReviewed = executeWave(List.of(review), completed, Map.of(), metrics);
                    completed.addAll(reReviewed);
                    review = reReviewed.get(0);
                    checkpointPath = persistCheckpoint(finalGoal, plan, completed);
                }
            }
        }

        String commissioningSummary = generateCommissioningReport(finalGoal, completed, metrics);
        checkpointPath = persistCheckpoint(finalGoal, plan, completed);
        long totalDurationMs = System.currentTimeMillis() - startTime;

        if (sessionSummarizer != null && !completed.isEmpty()) {
            List<SessionAction> sessionActions = completed.stream()
                    .map(t -> new SessionAction(
                            finalGoal,
                            t.assignedRole().name(),
                            t.title(),
                            t.output()))
                    .toList();
            sessionSummarizer.summarizeSessionAsync(finalGoal, "PM", sessionActions);
        }

        return new CommissioningResult(
                plan, completed, commissioningSummary, totalDurationMs,
                metrics.inputTokens.get(), metrics.outputTokens.get(), metrics.costUsd.sum(), checkpointPath,
                metrics.tokensOffloadedToLocal.get(), metrics.frontierCostAvoidedUsd.sum(),
                savingsEstimator.referenceProvider().map(p -> p.modelName()).orElse(null),
                metrics.tokenUsageReport.summarize());
    }

    private Checkpoint loadCheckpoint(String goal, Path resumeFrom) {
        try {
            Checkpoint checkpoint = checkpointStore.load(resumeFrom)
                    .orElseThrow(() -> new IllegalArgumentException("No checkpoint found at " + resumeFrom));
            if (goal != null && !goal.isBlank() && !checkpoint.goal().equals(goal)) {
                throw new IllegalArgumentException(
                        "Checkpoint goal '" + checkpoint.goal() + "' does not match requested goal '" + goal + "'");
            }
            return checkpoint;
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Failed to read checkpoint: " + resumeFrom, e);
        }
    }

    private Path persistCheckpoint(String goal, ProjectPlan plan, List<SubTask> completed) {
        try {
            return checkpointStore.save(new Checkpoint(goal, plan, List.copyOf(completed), System.currentTimeMillis()));
        } catch (java.io.IOException e) {
            return checkpointStore.pathFor(goal);
        }
    }

    private List<SubTask> executeWave(
            List<SubTask> wave,
            List<SubTask> completedSoFar,
            Map<Integer, String> extraNotes,
            RunMetrics metrics) {
        try (var span = CastTelemetry.current().span("castcli.agent.wave")
                .attribute("castcli.agent.wave.size", wave.size())) {
        span.event("agent.wave.started");
        String context = buildContext(completedSoFar);

        if (wave.size() == 1) {
            return List.of(executeSubtask(wave.get(0), context, extraNotes.get(wave.get(0).id()), metrics));
        }

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Context parent = Context.current();
            List<Callable<SubTask>> jobs = wave.stream()
                    .map(subtask -> parent.wrap((Callable<SubTask>) () -> executeSubtask(
                            subtask, context, extraNotes.get(subtask.id()), metrics)))
                    .toList();
            List<Future<SubTask>> futures = executor.invokeAll(jobs);
            List<SubTask> results = new ArrayList<>(futures.size());
            for (Future<SubTask> future : futures) {
                results.add(future.get());
            }
            return results;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while executing parallel wave", e);
        } catch (java.util.concurrent.ExecutionException e) {
            throw new IllegalStateException("Worker subtask failed", e.getCause());
        }
        }
    }

    private SubTask executeSubtask(
            SubTask subtask,
            String context,
            String extraNote,
            RunMetrics metrics) {
        try (var span = CastTelemetry.current().span("castcli.agent.subtask")
                .attribute("castcli.agent.subtask.id", subtask.id())
                .attribute("castcli.agent.subtask.title", subtask.title())
                .attribute("castcli.agent.role", subtask.assignedRole().name())) {
        Workload workload = mapRoleToWorkload(subtask.assignedRole());
        StringBuilder prompt = new StringBuilder(subtask.prompt());
        if (extraNote != null) {
            prompt.append(extraNote);
        }
        if (subtask.assignedRole() == AgentRole.REVIEWER) {
            prompt.append("\n\nConclude your response with exactly one final line in this form:\n")
                    .append("VERDICT: APPROVED\nor\nVERDICT: REJECTED: <one-sentence reason>");
        }
        prompt.append(context);

        HarnessOrchestrator.Outcome outcome = orchestrator.run(new TaskRequest(prompt.toString(), workload, null));
        span.attribute("gen_ai.provider.name", outcome.provider().id())
                .attribute("gen_ai.usage.input_tokens", outcome.inputTokens())
                .attribute("gen_ai.usage.output_tokens", outcome.outputTokens());
        record(outcome, metrics);
        return subtask.withOutput(outcome.answer(), "COMPLETED");
        }
    }

    private ProjectPlan generatePlan(String goal, RunMetrics metrics) {
        String pmPrompt = """
                You are the Lead Architect and Project Manager.
                Break down the following goal into 2 to 4 sequential subtasks for your skilled labor agents.
                Format each subtask EXACTLY as shown below:

                TITLE: <short title>
                ROLE: CODER | TESTER | REVIEWER | GENERAL_LABOR
                PROMPT: <detailed instructions for worker>

                GOAL: %s
                """.formatted(goal);

        TaskRequest request = new TaskRequest(pmPrompt, Workload.REASONING, ModelTier.FRONTIER_CLOUD, true);
        HarnessOrchestrator.Outcome pmOutcome = orchestrator.run(request);
        record(pmOutcome, metrics);

        List<SubTask> subtasks = parseSubtasks(pmOutcome.answer());
        if (subtasks.isEmpty()) {
            subtasks = List.of(
                    new SubTask(1, "Implementation", AgentRole.CODER, "Implement the solution for: " + goal, "PENDING", null),
                    new SubTask(2, "Review & Polish", AgentRole.REVIEWER, "Review and refine the implementation for: " + goal, "PENDING", null)
            );
        }

        return new ProjectPlan(goal, pmOutcome.answer(), subtasks);
    }

    private String generateCommissioningReport(String goal, List<SubTask> completedTasks, RunMetrics metrics) {
        StringBuilder reportPrompt = new StringBuilder();
        reportPrompt.append("You are the Lead Architect and Commissioning Agent.\n");
        reportPrompt.append("Review the final completed subtasks submitted by your skilled labor workers for the goal:\n");
        reportPrompt.append("GOAL: ").append(goal).append("\n\n");
        reportPrompt.append("SUBTASK DELIVERABLES:\n");

        for (SubTask task : completedTasks) {
            reportPrompt.append("\n[Subtask #").append(task.id()).append(" - ").append(task.title())
                    .append(" (").append(task.assignedRole()).append(")]\n")
                    .append(task.output()).append("\n");
        }

        boolean anyUnresolvedRejection = completedTasks.stream()
                .anyMatch(t -> t.assignedRole() == AgentRole.REVIEWER && isRejected(t));
        reportPrompt.append(anyUnresolvedRejection
                ? "\nAt least one reviewer subtask above is still REJECTED after the rework budget was exhausted. "
                        + "Do NOT certify readiness or approve the deliverable. Summarize what was reviewed and "
                        + "call out exactly which reviewer feedback remains unresolved."
                : "\nProvide a concise commissioning report approving the deliverable, summarizing key features, and certifying readiness.");

        TaskRequest request = new TaskRequest(reportPrompt.toString(), Workload.REASONING, ModelTier.FRONTIER_CLOUD, true);
        HarnessOrchestrator.Outcome outcome = orchestrator.run(request);
        record(outcome, metrics);
        return outcome.answer();
    }

    private void record(HarnessOrchestrator.Outcome outcome, RunMetrics metrics) {
        metrics.inputTokens.addAndGet(outcome.inputTokens());
        metrics.outputTokens.addAndGet(outcome.outputTokens());
        metrics.costUsd.add(outcome.estimatedCostUsd());
        metrics.tokenUsageReport.record(outcome);
        if (savingsEstimator.isOffloaded(outcome.provider())) {
            metrics.tokensOffloadedToLocal.addAndGet(outcome.inputTokens() + outcome.outputTokens());
            metrics.frontierCostAvoidedUsd.add(
                    savingsEstimator.estimateAvoidedCostUsd(outcome.provider(), outcome.inputTokens(), outcome.outputTokens()));
        }
    }

    /** Bounds context handed to later workers to {@code routing.maxContextChars}: the most recent
     * subtask outputs are kept in full (up to a per-subtask cap), older ones are collapsed to a
     * one-line summary so the prompt sent to small local models cannot grow without bound. */
    String buildContext(List<SubTask> completed) {
        if (completed.isEmpty()) {
            return "";
        }
        int budget = config.routing().maxContextChars();
        int n = completed.size();
        List<String> fullBlocks = new ArrayList<>(n);
        List<String> summaryBlocks = new ArrayList<>(n);
        for (SubTask t : completed) {
            String output = t.output() == null ? "" : t.output();
            fullBlocks.add(renderBlock(t, truncate(output, MAX_PER_SUBTASK_CONTEXT_CHARS)));
            summaryBlocks.add(renderBlock(t, truncate(output, SUMMARY_CONTEXT_CHARS)));
        }
        boolean[] useFull = new boolean[n];
        int used = 0;
        for (int i = n - 1; i >= 0; i--) {
            int len = fullBlocks.get(i).length();
            if (used + len <= budget) {
                useFull[i] = true;
                used += len;
            } else {
                used += summaryBlocks.get(i).length();
            }
        }
        StringBuilder sb = new StringBuilder("\n\nCONTEXT FROM PRIOR SUBTASKS:\n");
        for (int i = 0; i < n; i++) {
            sb.append(useFull[i] ? fullBlocks.get(i) : summaryBlocks.get(i));
        }
        return sb.toString();
    }

    private static String renderBlock(SubTask t, String output) {
        return "\n--- Subtask " + t.id() + ": " + t.title() + " (" + t.assignedRole() + ") ---\n" + output + "\n";
    }

    private static String truncate(String text, int maxChars) {
        if (text.length() <= maxChars) {
            return text;
        }
        return text.substring(0, maxChars) + "...[truncated " + (text.length() - maxChars) + " chars]";
    }

    private static List<List<SubTask>> buildWaves(List<SubTask> subtasks) {
        List<List<SubTask>> waves = new ArrayList<>();
        List<SubTask> currentWave = new ArrayList<>();
        for (SubTask subtask : subtasks) {
            if (PARALLEL_ROLES.contains(subtask.assignedRole())) {
                currentWave.add(subtask);
            } else {
                if (!currentWave.isEmpty()) {
                    waves.add(List.copyOf(currentWave));
                    currentWave = new ArrayList<>();
                }
                waves.add(List.of(subtask));
            }
        }
        if (!currentWave.isEmpty()) {
            waves.add(List.copyOf(currentWave));
        }
        return waves;
    }

    private static boolean isCodeWave(List<SubTask> wave) {
        return wave.stream().allMatch(t -> PARALLEL_ROLES.contains(t.assignedRole()));
    }

    private static boolean isReviewWave(List<SubTask> wave) {
        return wave.size() == 1 && wave.get(0).assignedRole() == AgentRole.REVIEWER;
    }

    private static boolean isRejected(SubTask review) {
        return review.output() != null && REJECTED_PATTERN.matcher(review.output()).find();
    }

    private static List<SubTask> resolveFromCompleted(List<SubTask> completed, List<SubTask> wave) {
        Set<Integer> ids = wave.stream().map(SubTask::id).collect(Collectors.toSet());
        return completed.stream().filter(t -> ids.contains(t.id())).toList();
    }

    private static List<SubTask> parseSubtasks(String pmText) {
        List<SubTask> list = new ArrayList<>();
        Matcher matcher = SUBTASK_PATTERN.matcher(pmText);
        int id = 1;
        while (matcher.find()) {
            String title = matcher.group(1).trim();
            String roleStr = matcher.group(2).trim().toUpperCase();
            String prompt = matcher.group(3).trim();

            AgentRole role;
            try {
                role = AgentRole.valueOf(roleStr);
            } catch (Exception e) {
                role = AgentRole.GENERAL_LABOR;
            }

            list.add(new SubTask(id++, title, role, prompt, "PENDING", null));
        }
        return list;
    }

    private static Workload mapRoleToWorkload(AgentRole role) {
        return switch (role) {
            case CODER, TESTER -> Workload.CODE;
            case REVIEWER, PROJECT_MANAGER -> Workload.REASONING;
            case GENERAL_LABOR -> Workload.AUTO;
        };
    }
}

