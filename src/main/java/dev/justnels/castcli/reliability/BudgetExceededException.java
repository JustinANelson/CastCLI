package dev.justnels.castcli.reliability;

/**
 * Thrown when a task request or overall execution exceeds configured cost budget thresholds.
 */
public class BudgetExceededException extends RuntimeException {
    @java.io.Serial
    private static final long serialVersionUID = 1L;
    private final double limitUsd;
    private final double actualUsd;

    public BudgetExceededException(String message, double limitUsd, double actualUsd) {
        super(message);
        this.limitUsd = limitUsd;
        this.actualUsd = actualUsd;
    }

    public double getLimitUsd() {
        return limitUsd;
    }

    public double getActualUsd() {
        return actualUsd;
    }
}
