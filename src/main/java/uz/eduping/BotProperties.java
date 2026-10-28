package uz.eduping;

import org.springframework.boot.context.properties.ConfigurationProperties;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

@ConfigurationProperties("app")
public record BotProperties(boolean workersEnabled, String token, String teacherIds, String telegramBaseUrl, String botUsername, String timeZone) {
    /** Real teacher IDs; the placeholder 0 used during first setup grants nothing. */
    public Set<Long> teachers() {
        return Arrays.stream(teacherIds.split(",")).map(String::trim).filter(s -> !s.isEmpty())
            .map(Long::parseLong).filter(id -> id > 0).collect(Collectors.toUnmodifiableSet());
    }
    public boolean isTeacher(long id) { return teachers().contains(id); }
    /** No teacher configured yet: the bot only tells people their Telegram ID. */
    public boolean setupMode() { return teachers().isEmpty(); }
}
