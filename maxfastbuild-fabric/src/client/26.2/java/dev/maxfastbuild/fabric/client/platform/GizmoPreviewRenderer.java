package dev.maxfastbuild.fabric.client.platform;

import dev.maxfastbuild.api.BlockPos;
import dev.maxfastbuild.fabric.client.PreviewFace;
import net.minecraft.core.Direction;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** 26.2 world preview renderer backed by the debug gizmo API. */
final class GizmoPreviewRenderer {
    private static final int PLACE_STROKE = 0xFF4DE4FF;
    private static final int PLACE_FIRST = 0x664DE4FF;
    private static final int PLACE_FACE = 0x3365E08A;
    private static final int BREAK_STROKE = 0xFFFF8A4D;
    private static final int BREAK_FIRST = 0x66FF8A4D;
    private static final int BREAK_FACE = 0x33E08A65;
    private static final int FACE_DRAW_LIMIT = 6000;

    private GizmoPreviewRenderer() {}

    static void render(PreviewSnapshot snapshot) {
        if (snapshot == null) return;
        if (!snapshot.breaking() && !snapshot.placing()) {
            if (snapshot.hovered() != null) {
                Gizmos.cuboid(toMc(snapshot.hovered()), GizmoStyle.stroke(0xFFB7C0CC, 1.5f));
            }
            return;
        }
        int stroke = snapshot.breaking() ? BREAK_STROKE : PLACE_STROKE;
        int firstFill = snapshot.breaking() ? BREAK_FIRST : PLACE_FIRST;
        int faceFill = snapshot.breaking() ? BREAK_FACE : PLACE_FACE;
        int boundsStroke = snapshot.breaking() ? 0xFFFFB48E : 0xFF8EE9FF;
        if (snapshot.first() != null) {
            Gizmos.cuboid(toMc(snapshot.first()), GizmoStyle.strokeAndFill(stroke, 2f, firstFill));
        }
        if (snapshot.hovered() != null && snapshot.first() == null) {
            Gizmos.cuboid(toMc(snapshot.hovered()), GizmoStyle.stroke(stroke, 1.5f));
            return;
        }
        if (snapshot.first() == null || snapshot.bounds() == null) return;

        // Axis-aligned cuboid shapes: one outer box only (no per-block grid).
        if (snapshot.simpleCuboid()) {
            Gizmos.cuboid(snapshot.bounds(), GizmoStyle.strokeAndFill(boundsStroke, 2.5f, faceFill));
            return;
        }

        // General shapes: only outward faces (no shared faces between neighbors).
        GizmoStyle faceStyle = GizmoStyle.fill(faceFill);
        int drawn = 0;
        for (PreviewFace face : snapshot.faces()) {
            if (drawn++ >= FACE_DRAW_LIMIT) break;
            drawExposedFace(face.pos(), face.face(), faceStyle);
        }
        Gizmos.cuboid(snapshot.bounds(), GizmoStyle.stroke(boundsStroke, 2.5f));
    }

    private static net.minecraft.core.BlockPos toMc(BlockPos pos) {
        return new net.minecraft.core.BlockPos(pos.x(), pos.y(), pos.z());
    }

    private static void drawExposedFace(BlockPos pos, Direction face, GizmoStyle style) {
        Vec3 min = new Vec3(pos.x(), pos.y(), pos.z());
        Vec3 max = new Vec3(pos.x() + 1, pos.y() + 1, pos.z() + 1);
        Gizmos.rect(min, max, face, style);
    }
}
