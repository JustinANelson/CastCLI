package dev.justnels.castcli.tools;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

public final class SystemTools {
    @Tool("Returns the current date and time in an IANA time zone such as America/New_York")
    public String currentTime(@P("IANA time-zone ID") String zoneId) {
        ZoneId zone = ZoneId.of(zoneId);
        return DateTimeFormatter.ISO_ZONED_DATE_TIME.format(Instant.now().atZone(zone));
    }
}

