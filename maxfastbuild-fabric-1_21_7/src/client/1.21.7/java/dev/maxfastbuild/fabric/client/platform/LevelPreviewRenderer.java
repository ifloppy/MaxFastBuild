package dev.maxfastbuild.fabric.client.platform;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.maxfastbuild.api.BlockPos;
import dev.maxfastbuild.fabric.client.PreviewFace;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix3f;
import org.joml.Matrix4f;

/**
 * 1.21.7 world preview renderer: classic render events + {@link VertexConsumer}.
 * Mirrors the 26.2 gizmo-based renderer (same colors and geometry decisions).
 */
final class LevelPreviewRenderer {
    private static final int PLACE_STROKE = 0xFF4DE4FF;
    private static final int PLACE_FIRST = 0x664DE4FF;
    private static final int PLACE_FACE = 0x3365E08A;
    private static final int BREAK_STROKE = 0xFFFF8A4D;
    private static final int BREAK_FIRST = 0x66FF8A4D;
    private static final int BREAK_FACE = 0x33E08A65;
    private static final int FACE_DRAW_LIMIT = 6000;

    private LevelPreviewRenderer() {}

    static void render(WorldRenderContext context, PreviewSnapshot snapshot) {
        if (snapshot == null) return;
        PoseStack poseStack = context.matrixStack();
        Camera camera = context.camera();
        Vec3 cam = camera.getPosition();
        Matrix4f matrix = poseStack.last().pose();
        Matrix3f normal = poseStack.last().normal();
        VertexConsumer lines = context.consumers().getBuffer(RenderType.lines());
        // debugFilledBox() uses TRIANGLE_STRIP, so separate quads would connect into each other.
        // debugQuads() uses independent QUADS with cull disabled, which is what we need here.
        VertexConsumer faces = context.consumers().getBuffer(RenderType.debugQuads());

        if (!snapshot.breaking() && !snapshot.placing()) {
            if (snapshot.hovered() != null) {
                drawBox(lines, faces, matrix, normal, boxOf(snapshot.hovered()), 0xFFB7C0CC, 0, false, cam);
            }
            return;
        }
        int stroke = snapshot.breaking() ? BREAK_STROKE : PLACE_STROKE;
        int firstFill = snapshot.breaking() ? BREAK_FIRST : PLACE_FIRST;
        int faceFill = snapshot.breaking() ? BREAK_FACE : PLACE_FACE;
        int boundsStroke = snapshot.breaking() ? 0xFFFFB48E : 0xFF8EE9FF;
        if (snapshot.first() != null) {
            drawBox(lines, faces, matrix, normal, boxOf(snapshot.first()), stroke, firstFill, false, cam);
        }
        if (snapshot.hovered() != null && snapshot.first() == null) {
            drawBox(lines, faces, matrix, normal, boxOf(snapshot.hovered()), stroke, 0, false, cam);
            return;
        }
        if (snapshot.first() == null || snapshot.bounds() == null) return;

        // Axis-aligned cuboid shapes: one outer box only (no per-block grid).
        if (snapshot.simpleCuboid()) {
            drawBox(lines, faces, matrix, normal, snapshot.bounds(), boundsStroke, faceFill, true, cam);
            return;
        }

        // General shapes: only outward faces (no shared faces between neighbors).
        int drawn = 0;
        for (PreviewFace face : snapshot.faces()) {
            if (drawn++ >= FACE_DRAW_LIMIT) break;
            drawFace(lines, faces, matrix, normal, face.pos(), face.face(), faceFill, cam);
        }
        drawBox(lines, faces, matrix, normal, snapshot.bounds(), boundsStroke, 0, false, cam);
    }

    private static AABB boxOf(BlockPos pos) {
        return new AABB(pos.x(), pos.y(), pos.z(), pos.x() + 1, pos.y() + 1, pos.z() + 1);
    }

    private static void drawFace(VertexConsumer lines, VertexConsumer faces, Matrix4f matrix, Matrix3f normal,
                                 BlockPos pos, Direction face, int fill, Vec3 cam) {
        float x0 = (float) (pos.x() - cam.x);
        float y0 = (float) (pos.y() - cam.y);
        float z0 = (float) (pos.z() - cam.z);
        float x1 = x0 + 1;
        float y1 = y0 + 1;
        float z1 = z0 + 1;
        drawQuad(faces, matrix, normal, face, x0, y0, z0, x1, y1, z1, fill);
    }

    /** Stroke edges of a box plus optional translucent fill (drawn on both windings so it shows from any side). */
    private static void drawBox(VertexConsumer lines, VertexConsumer faces, Matrix4f matrix, Matrix3f normal,
                                AABB box, int stroke, int fill, boolean filled, Vec3 cam) {
        float x0 = (float) (box.minX - cam.x);
        float y0 = (float) (box.minY - cam.y);
        float z0 = (float) (box.minZ - cam.z);
        float x1 = (float) (box.maxX - cam.x);
        float y1 = (float) (box.maxY - cam.y);
        float z1 = (float) (box.maxZ - cam.z);
        if (filled) {
            for (Direction face : Direction.values()) {
                drawQuad(faces, matrix, normal, face, x0, y0, z0, x1, y1, z1, fill);
            }
        }
        int r = (stroke >> 16) & 0xFF;
        int g = (stroke >> 8) & 0xFF;
        int b = stroke & 0xFF;
        int a = (stroke >> 24) & 0xFF;
        edge(lines, matrix, x0, y0, z0, x1, y0, z0, r, g, b, a);
        edge(lines, matrix, x0, y0, z1, x1, y0, z1, r, g, b, a);
        edge(lines, matrix, x0, y1, z0, x1, y1, z0, r, g, b, a);
        edge(lines, matrix, x0, y1, z1, x1, y1, z1, r, g, b, a);
        edge(lines, matrix, x0, y0, z0, x0, y1, z0, r, g, b, a);
        edge(lines, matrix, x1, y0, z0, x1, y1, z0, r, g, b, a);
        edge(lines, matrix, x0, y0, z1, x0, y1, z1, r, g, b, a);
        edge(lines, matrix, x1, y0, z1, x1, y1, z1, r, g, b, a);
        edge(lines, matrix, x0, y0, z0, x0, y0, z1, r, g, b, a);
        edge(lines, matrix, x1, y0, z0, x1, y0, z1, r, g, b, a);
        edge(lines, matrix, x0, y1, z0, x0, y1, z1, r, g, b, a);
        edge(lines, matrix, x1, y1, z0, x1, y1, z1, r, g, b, a);
    }

    private static void edge(VertexConsumer lines, Matrix4f matrix,
                             float x0, float y0, float z0, float x1, float y1, float z1,
                             int r, int g, int b, int a) {
        // RenderType.lines() format includes Normal. Zero normal collapses the line in the
        // line shader, so use an axis-aligned normal matching this box edge (same as vanilla).
        float nx = x1 != x0 ? 1f : 0f;
        float ny = y1 != y0 ? 1f : 0f;
        float nz = z1 != z0 ? 1f : 0f;
        lines.addVertex(matrix, x0, y0, z0).setColor(r, g, b, a).setNormal(nx, ny, nz);
        lines.addVertex(matrix, x1, y1, z1).setColor(r, g, b, a).setNormal(nx, ny, nz);
    }

    /** One axis-aligned quad. debugQuads() uses independent QUADS, so only one winding is needed. */
    private static void drawQuad(VertexConsumer faces, Matrix4f matrix, Matrix3f normal, Direction face,
                                 float x0, float y0, float z0, float x1, float y1, float z1, int fill) {
        int r = (fill >> 16) & 0xFF;
        int g = (fill >> 8) & 0xFF;
        int b = fill & 0xFF;
        int a = (fill >> 24) & 0xFF;
        float[][] corners = switch (face) {
            case DOWN -> new float[][]{{x0, y0, z1}, {x1, y0, z1}, {x1, y0, z0}, {x0, y0, z0}};
            case UP -> new float[][]{{x0, y1, z0}, {x1, y1, z0}, {x1, y1, z1}, {x0, y1, z1}};
            case NORTH -> new float[][]{{x1, y0, z0}, {x0, y0, z0}, {x0, y1, z0}, {x1, y1, z0}};
            case SOUTH -> new float[][]{{x0, y0, z1}, {x1, y0, z1}, {x1, y1, z1}, {x0, y1, z1}};
            case WEST -> new float[][]{{x0, y0, z0}, {x0, y0, z1}, {x0, y1, z1}, {x0, y1, z0}};
            case EAST -> new float[][]{{x1, y0, z1}, {x1, y0, z0}, {x1, y1, z0}, {x1, y1, z1}};
        };
        for (float[] c : corners) {
            faces.addVertex(matrix, c[0], c[1], c[2]).setColor(r, g, b, a);
        }
    }
}
