package dev.justnels.castcli.doctor;

/**
 * Resolves the running build's version from the jar manifest ({@code jpackage}/{@code distZip}
 * builds set {@code Implementation-Version} to the Gradle project version). Falls back to a
 * fixed dev placeholder when run from the classpath (e.g. {@code gradlew run}) where no manifest
 * is present.
 */
public final class BuildInfo {
    private static final String DEV_FALLBACK_VERSION = "0.1.1-SNAPSHOT";

    private BuildInfo() { }

    public static String version() {
        String implVersion = BuildInfo.class.getPackage().getImplementationVersion();
        return implVersion != null && !implVersion.isBlank() ? implVersion : DEV_FALLBACK_VERSION;
    }
}
