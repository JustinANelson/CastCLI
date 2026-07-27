package dev.justnels.castcli.doctor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Opt-in check of the latest published GitHub release against the running build's version.
 * Never throws -- any network/parse failure is reported as an unchecked result rather than
 * surfacing an exception to the caller, since this is a best-effort convenience, not a
 * required diagnostic.
 */
public final class UpdateChecker {
    private static final String RELEASES_API =
            "https://api.github.com/repos/JustinANelson/CastCLI/releases/latest";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HttpClient client;
    private final URI releasesApi;

    public record Result(boolean checked, boolean updateAvailable, String currentVersion, String latestVersion,
                          String detail) { }

    public UpdateChecker() {
        this(URI.create(RELEASES_API));
    }

    UpdateChecker(URI releasesApi) {
        this.releasesApi = releasesApi;
        this.client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    }

    public Result check(String currentVersion) {
        try {
            HttpRequest request = HttpRequest.newBuilder(releasesApi)
                    .timeout(Duration.ofSeconds(5))
                    .header("Accept", "application/vnd.github+json")
                    .header("User-Agent", "cast-cli-update-check")
                    .GET().build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                return new Result(false, false, currentVersion, null,
                        "GitHub API returned HTTP " + response.statusCode());
            }
            JsonNode body = MAPPER.readTree(response.body());
            String tag = body.path("tag_name").asText(null);
            if (tag == null || tag.isBlank()) {
                return new Result(false, false, currentVersion, null, "no release tag found");
            }
            String latest = tag.startsWith("v") ? tag.substring(1) : tag;
            boolean newer = isNewer(latest, currentVersion);
            return new Result(true, newer, currentVersion, latest, newer ? "update available" : "up to date");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Result(false, false, currentVersion, null, "interrupted");
        } catch (Exception e) {
            return new Result(false, false, currentVersion, null,
                    e.getClass().getSimpleName() + (e.getMessage() != null ? ": " + e.getMessage() : ""));
        }
    }

    /** True when {@code latest} is a strictly newer major.minor.patch than {@code current}. */
    static boolean isNewer(String latest, String current) {
        int[] l = parseMajorMinorPatch(latest);
        int[] c = parseMajorMinorPatch(current);
        for (int i = 0; i < 3; i++) {
            if (l[i] != c[i]) return l[i] > c[i];
        }
        return false;
    }

    private static int[] parseMajorMinorPatch(String version) {
        String core = version.split("-", 2)[0];
        String[] parts = core.split("\\.");
        int[] out = new int[3];
        for (int i = 0; i < 3 && i < parts.length; i++) {
            String digits = parts[i].replaceAll("[^0-9]", "");
            out[i] = digits.isEmpty() ? 0 : Integer.parseInt(digits);
        }
        return out;
    }
}
