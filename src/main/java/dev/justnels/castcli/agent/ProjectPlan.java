package dev.justnels.castcli.agent;

import java.util.List;

public record ProjectPlan(String goal, String architectureOverview, List<SubTask> subtasks) {
    public ProjectPlan {
        if (goal == null || goal.isBlank()) {
            throw new IllegalArgumentException("goal must not be blank");
        }
        if (subtasks == null) {
            subtasks = List.of();
        }
    }
}

