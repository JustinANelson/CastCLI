package dev.justnels.castcli.tools;

/** Fail-closed approval gate used when a caller has not explicitly selected an approval policy. */
public enum DenyApprovalGate implements ApprovalGate {
    INSTANCE;

    @Override
    public boolean approve(String action, String detail) {
        return false;
    }
}
