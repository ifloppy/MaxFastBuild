package dev.maxfastbuild.core.protocol;

import java.util.Objects;

public record ProtocolEnvelope(int version, String sessionId, long sequence, String payload, String mac) {
    public static final int CURRENT_VERSION = 1;
    public static final String INTERNAL_COMMAND = "/__mfb";
    public static final String MESSAGE_MARKER = "\u2063MFB1:";

    public ProtocolEnvelope {
        Objects.requireNonNull(sessionId);
        Objects.requireNonNull(payload);
        Objects.requireNonNull(mac);
    }

    public String signingInput() { return version + "\n" + sessionId + "\n" + sequence + "\n" + payload; }
}
