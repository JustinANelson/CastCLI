package dev.justnels.castcli.tools;

/**
 * Human-in-the-loop checkpoint invoked before any tool with side effects (file writes, process
 * execution) runs. Implementations must be safe to call from tool-execution threads.
 */
public interface ApprovalGate {
    /**
     * @param action short verb phrase, e.g. "write file" or "run command"
     * @param detail the concrete operation, e.g. the file path and content preview, or the command line
     * @return true if the operation may proceed
     */
    boolean approve(String action, String detail);
}

