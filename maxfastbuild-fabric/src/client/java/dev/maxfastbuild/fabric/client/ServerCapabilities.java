package dev.maxfastbuild.fabric.client;

import com.google.gson.JsonObject;
import dev.maxfastbuild.core.protocol.ProtocolEnvelope;

/** Server-authoritative limits received from the v4 hello handshake. */
final class ServerCapabilities {
    private static volatile Limits current;

    private ServerCapabilities() {}

    static void update(JsonObject hello) {
        if (hello.has("protocolVersion") && hello.get("protocolVersion").getAsInt() != ProtocolEnvelope.CURRENT_VERSION) {
            current = null;
            return;
        }
        if (!hello.has("limits") || !hello.get("limits").isJsonObject()) {
            current = null;
            return;
        }
        JsonObject limits = hello.getAsJsonObject("limits");
        try {
            current = new Limits(
                    requiredLong(limits, "maxRegionBlocks"),
                    requiredLong(limits, "maxAffectedBlocks"),
                    requiredLong(limits, "maxSizeX"),
                    requiredLong(limits, "maxSizeY"),
                    requiredLong(limits, "maxSizeZ"),
                    requiredInt(limits, "maxPasteParts"),
                    requiredInt(limits, "maxBlocksPerPart"),
                    requiredInt(limits, "maxPasteTotalBlocks"),
                    requiredInt(limits, "maxPayloadBytes"),
                    requiredInt(limits, "maxInstantEntities"),
                    requiredInt(limits, "maxInstantEntitiesPerChunk"),
                    requiredInt(limits, "maxNormalEntities"),
                    requiredInt(limits, "maxNormalEntitiesPerChunk"));
        } catch (RuntimeException ex) {
            current = null;
        }
    }

    static Limits current() {
        return current;
    }

    private static long requiredLong(JsonObject object, String key) {
        long value = object.get(key).getAsLong();
        if (value < 1) throw new IllegalArgumentException("invalid_server_limit_" + key);
        return value;
    }

    private static int requiredInt(JsonObject object, String key) {
        int value = object.get(key).getAsInt();
        if (value < 0) throw new IllegalArgumentException("invalid_server_limit_" + key);
        return value;
    }

    record Limits(long maxRegionBlocks, long maxAffectedBlocks, long maxSizeX, long maxSizeY, long maxSizeZ,
                  int maxPasteParts, int maxBlocksPerPart, int maxPasteTotalBlocks, int maxPayloadBytes,
                  int maxInstantEntities, int maxInstantEntitiesPerChunk,
                  int maxNormalEntities, int maxNormalEntitiesPerChunk) {}
}
