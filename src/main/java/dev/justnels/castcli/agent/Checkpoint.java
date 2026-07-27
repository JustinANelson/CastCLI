package dev.justnels.castcli.agent;

import java.util.List;

/** Durable snapshot of in-progress commission work, written after each wave so a crash or restart
 * can resume from the last completed subtask instead of re-running the whole plan. */
public record Checkpoint(String goal, ProjectPlan plan, List<SubTask> completedTasks, long updatedAtEpochMillis) {
}

