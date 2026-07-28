package dev.maxfastbuild.core.protocol;

import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

class SecureProtocolTest {
    @Test void authenticatesAndRejectsReplay() {
        SecureProtocol protocol = new SecureProtocol(Clock.systemUTC(), Duration.ofMinutes(5), 1024);
        UUID player = UUID.randomUUID();
        SecureProtocol.Session session = protocol.issue(player);
        ProtocolEnvelope envelope = protocol.sign(session, 0, "{}".getBytes(StandardCharsets.UTF_8));

        assertThat(protocol.verify(player, envelope)).isEqualTo("{}".getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(() -> protocol.verify(player, envelope)).hasMessage("replayed_request");
    }

    @Test void rejectsTampering() {
        SecureProtocol protocol = new SecureProtocol(Clock.systemUTC(), Duration.ofMinutes(5), 1024);
        UUID player = UUID.randomUUID();
        ProtocolEnvelope valid = protocol.sign(protocol.issue(player), 0, "{}".getBytes(StandardCharsets.UTF_8));
        ProtocolEnvelope changed = new ProtocolEnvelope(valid.version(), valid.sessionId(), valid.sequence(), valid.payload() + "A", valid.mac());
        assertThatThrownBy(() -> protocol.verify(player, changed)).hasMessage("invalid_mac");
    }
}
