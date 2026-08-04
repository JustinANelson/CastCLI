package dev.justnels.castcli.config;

/**
 * Optional exact-provider assignments for the hierarchical commissioning pipeline.
 *
 * <p>Null assignments preserve policy-based routing. A configured assignment is strict: the
 * commission run fails instead of silently substituting another provider when the requested
 * provider is unavailable.
 */
public record CommissioningConfig(
        String projectManagerProviderId,
        String coderProviderId,
        String testerProviderId,
        String reviewerProviderId,
        String generalLaborProviderId) {

    public CommissioningConfig {
        projectManagerProviderId = normalize(projectManagerProviderId);
        coderProviderId = normalize(coderProviderId);
        testerProviderId = normalize(testerProviderId);
        reviewerProviderId = normalize(reviewerProviderId);
        generalLaborProviderId = normalize(generalLaborProviderId);
    }

    public static CommissioningConfig automatic() {
        return new CommissioningConfig(null, null, null, null, null);
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
