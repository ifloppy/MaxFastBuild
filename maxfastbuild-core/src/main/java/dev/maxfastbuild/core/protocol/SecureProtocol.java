package dev.maxfastbuild.core.protocol;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class SecureProtocol {
    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder B64D = Base64.getUrlDecoder();
    private final SecureRandom random = new SecureRandom();
    private final Clock clock;
    private final Duration lifetime;
    private final int maxPayloadBytes;
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();

    public SecureProtocol(Clock clock, Duration lifetime, int maxPayloadBytes) {
        this.clock = clock;
        this.lifetime = lifetime;
        this.maxPayloadBytes = maxPayloadBytes;
    }

    public Session issue(UUID playerId) {
        byte[] secret = new byte[32];
        random.nextBytes(secret);
        Session session = new Session(UUID.randomUUID().toString(), playerId, secret, clock.instant().plus(lifetime), -1);
        sessions.put(session.id(), session);
        return session;
    }

    public ProtocolEnvelope sign(Session session, long sequence, byte[] json) {
        if (json.length > maxPayloadBytes) throw new ProtocolException("payload_too_large");
        String payload = B64.encodeToString(json);
        ProtocolEnvelope unsigned = new ProtocolEnvelope(ProtocolEnvelope.CURRENT_VERSION, session.id(), sequence, payload, "");
        return new ProtocolEnvelope(unsigned.version(), unsigned.sessionId(), unsigned.sequence(), payload, mac(session.secret(), unsigned.signingInput()));
    }

    public byte[] verify(UUID playerId, ProtocolEnvelope envelope) {
        Session current = sessions.get(envelope.sessionId());
        if (envelope.version() != ProtocolEnvelope.CURRENT_VERSION) throw new ProtocolException("unsupported_version");
        if (current == null || !current.playerId().equals(playerId)) throw new ProtocolException("invalid_session");
        if (clock.instant().isAfter(current.expiresAt())) { sessions.remove(current.id()); throw new ProtocolException("expired_session"); }
        if (envelope.sequence() <= current.lastSequence()) throw new ProtocolException("replayed_request");
        String expected = mac(current.secret(), new ProtocolEnvelope(envelope.version(), envelope.sessionId(), envelope.sequence(), envelope.payload(), "").signingInput());
        if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII), envelope.mac().getBytes(StandardCharsets.US_ASCII))) throw new ProtocolException("invalid_mac");
        byte[] decoded;
        try { decoded = B64D.decode(envelope.payload()); } catch (IllegalArgumentException ex) { throw new ProtocolException("invalid_payload"); }
        if (decoded.length > maxPayloadBytes) throw new ProtocolException("payload_too_large");
        sessions.put(current.id(), current.withLastSequence(envelope.sequence()));
        return decoded;
    }

    private static String mac(byte[] secret, String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return B64.encodeToString(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException ex) { throw new IllegalStateException(ex); }
    }

    public record Session(String id, UUID playerId, byte[] secret, Instant expiresAt, long lastSequence) {
        @Override public byte[] secret() { return secret.clone(); }
        Session withLastSequence(long value) { return new Session(id, playerId, secret, expiresAt, value); }
    }

    public static final class ProtocolException extends RuntimeException {
        public ProtocolException(String code) { super(code); }
    }
}
