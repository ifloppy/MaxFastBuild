package dev.maxfastbuild.core.protocol;

import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class CommandChunkAssemblerTest {
    @Test void splitsBelowPacketLimitAndReassemblesOutOfOrder() {
        CommandChunkAssembler assembler = new CommandChunkAssembler(Clock.systemUTC(), Duration.ofSeconds(10));
        String envelope = "x".repeat(1800);
        List<String> commands = new ArrayList<>(assembler.split(envelope));
        assertThat(commands).allMatch(command -> command.length() <= CommandChunkAssembler.MAX_COMMAND_LENGTH);
        Collections.reverse(commands);
        UUID player = UUID.randomUUID();
        Optional<String> result = Optional.empty();
        for (String command : commands) {
            String[] parts = command.split(" ", 7);
            result = assembler.accept(player, parts[2], Integer.parseInt(parts[3]), Integer.parseInt(parts[4]), parts[5]);
        }
        assertThat(result).contains(envelope);
    }
}
