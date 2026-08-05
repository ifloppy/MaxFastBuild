package dev.maxfastbuild.fabric.client;

/**
 * Neutral absolute entity entry extracted from a Litematica placement. {@code type} is the
 * namespaced entity id (e.g. {@code minecraft:minecart}), {@code x/y/z} the absolute spawn
 * position after the client applied the placement mirror/rotation, and {@code nbt} the entity's
 * SNBT with {@code id}/{@code Pos}/{@code UUID} stripped (the server re-derives them).
 */
public record PasteEntity(String type, double x, double y, double z, String nbt) {
    public PasteEntity {
        if (type == null || type.isBlank()) throw new IllegalArgumentException("blank entity type");
        if (nbt == null || nbt.isBlank()) throw new IllegalArgumentException("blank entity nbt");
    }
}
