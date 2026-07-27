package dev.justnels.castcli.agent;

public record SubTask(
        int id,
        String title,
        AgentRole assignedRole,
        String prompt,
        String status,
        String output) {

    public SubTask {
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("title must not be blank");
        }
        if (assignedRole == null) {
            assignedRole = AgentRole.GENERAL_LABOR;
        }
        if (status == null) {
            status = "PENDING";
        }
    }

    public SubTask withOutput(String newOutput, String newStatus) {
        return new SubTask(id, title, assignedRole, prompt, newStatus, newOutput);
    }
}

