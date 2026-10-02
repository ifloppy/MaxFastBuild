package dev.maxfastbuild.paper;

import dev.maxfastbuild.api.BlockPos;
import org.bukkit.Axis;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Orientable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Detects complete, vanilla-sized Nether portal openings in a bulk paste. */
final class NetherPortalPaste {
    private static final int MIN_WIDTH = 2;
    private static final int MIN_HEIGHT = 3;
    private static final int MAX_WIDTH = 21;
    private static final int MAX_HEIGHT = 21;

    record Cell(BlockPos position, String targetState) {}

    record Shape(Axis axis, int plane, int minU, int maxU, int minY, int maxY, List<Cell> cells) {
        Shape {
            cells = List.copyOf(cells);
        }

        int width() { return maxU - minU + 1; }
        int height() { return maxY - minY + 1; }
    }

    private record Plane(Axis axis, int coordinate) {}

    private NetherPortalPaste() {}

    static boolean isPortalState(String state) {
        if (state == null) return false;
        int properties = state.indexOf('[');
        String material = properties < 0 ? state : state.substring(0, properties);
        return "minecraft:nether_portal".equals(material) || "nether_portal".equals(material);
    }

    static List<Shape> findValid(World world, List<Cell> cells, Map<BlockPos, String> targetStates) {
        if (world == null || cells == null || cells.isEmpty()) return List.of();

        Map<Plane, Set<BlockPos>> byPlane = new LinkedHashMap<>();
        Map<BlockPos, Cell> portalCells = new HashMap<>();
        for (Cell cell : cells) {
            Axis axis = portalAxis(cell.targetState());
            if (axis == null) continue;
            int plane = planeCoordinate(cell.position(), axis);
            byPlane.computeIfAbsent(new Plane(axis, plane), ignored -> new LinkedHashSet<>())
                    .add(cell.position());
            portalCells.put(cell.position(), cell);
        }

        List<Shape> shapes = new ArrayList<>();
        for (Map.Entry<Plane, Set<BlockPos>> entry : byPlane.entrySet()) {
            Plane plane = entry.getKey();
            Set<BlockPos> remaining = new HashSet<>(entry.getValue());
            while (!remaining.isEmpty()) {
                BlockPos first = remaining.iterator().next();
                Set<BlockPos> component = new LinkedHashSet<>();
                ArrayDeque<BlockPos> queue = new ArrayDeque<>();
                queue.add(first);
                remaining.remove(first);
                while (!queue.isEmpty()) {
                    BlockPos next = queue.removeFirst();
                    component.add(next);
                    for (BlockPos neighbour : neighbours(next, plane)) {
                        if (remaining.remove(neighbour)) queue.addLast(neighbour);
                    }
                }
                Shape shape = shapeFromComponent(plane, component, portalCells);
                if (shape != null && validTargetFrame(world, targetStates, shape)
                        && legalInterior(world, shape)) {
                    shapes.add(shape);
                }
            }
        }
        shapes.sort(Comparator.comparingInt((Shape s) -> s.minY())
                .thenComparingInt(Shape::plane).thenComparingInt(Shape::minU));
        return List.copyOf(shapes);
    }

    static boolean liveFrameAndInteriorAreLegal(World world, Shape shape) {
        if (world == null || shape == null || !withinHeight(world, shape)) return false;
        for (int u = shape.minU(); u <= shape.maxU(); u++) {
            if (liveMaterial(world, position(shape.axis(), shape.plane(), u, shape.minY() - 1)) != Material.OBSIDIAN
                    || liveMaterial(world, position(shape.axis(), shape.plane(), u, shape.maxY() + 1)) != Material.OBSIDIAN) {
                return false;
            }
        }
        for (int y = shape.minY(); y <= shape.maxY(); y++) {
            if (liveMaterial(world, position(shape.axis(), shape.plane(), shape.minU() - 1, y)) != Material.OBSIDIAN
                    || liveMaterial(world, position(shape.axis(), shape.plane(), shape.maxU() + 1, y)) != Material.OBSIDIAN) {
                return false;
            }
        }
        return legalInterior(world, shape);
    }

    static boolean isPortalCell(BlockPos position, Shape shape) {
        return position != null && shape != null && shape.cells().stream()
                .anyMatch(cell -> cell.position().equals(position));
    }

    static BlockPos position(Shape shape, int u, int y) {
        return position(shape.axis(), shape.plane(), u, y);
    }

    private static Shape shapeFromComponent(Plane plane, Set<BlockPos> component,
                                           Map<BlockPos, Cell> portalCells) {
        if (component.isEmpty()) return null;
        int minU = Integer.MAX_VALUE;
        int maxU = Integer.MIN_VALUE;
        int minY = Integer.MAX_VALUE;
        int maxY = Integer.MIN_VALUE;
        for (BlockPos pos : component) {
            int u = widthCoordinate(pos, plane.axis());
            minU = Math.min(minU, u);
            maxU = Math.max(maxU, u);
            minY = Math.min(minY, pos.y());
            maxY = Math.max(maxY, pos.y());
        }
        int width = maxU - minU + 1;
        int height = maxY - minY + 1;
        if (width < MIN_WIDTH || width > MAX_WIDTH || height < MIN_HEIGHT || height > MAX_HEIGHT
                || component.size() != width * height) {
            return null;
        }

        List<Cell> cells = new ArrayList<>(component.size());
        for (int y = minY; y <= maxY; y++) {
            for (int u = minU; u <= maxU; u++) {
                BlockPos pos = position(plane.axis(), plane.coordinate(), u, y);
                Cell cell = portalCells.get(pos);
                if (cell == null || portalAxis(cell.targetState()) != plane.axis()) return null;
                cells.add(cell);
            }
        }
        return new Shape(plane.axis(), plane.coordinate(), minU, maxU, minY, maxY, cells);
    }

    private static boolean validTargetFrame(World world, Map<BlockPos, String> targetStates, Shape shape) {
        if (!withinHeight(world, shape)) return false;
        for (int u = shape.minU(); u <= shape.maxU(); u++) {
            if (!frameCellLegal(world, targetStates, position(shape, u, shape.minY() - 1))
                    || !frameCellLegal(world, targetStates, position(shape, u, shape.maxY() + 1))) {
                return false;
            }
        }
        for (int y = shape.minY(); y <= shape.maxY(); y++) {
            if (!frameCellLegal(world, targetStates, position(shape, shape.minU() - 1, y))
                    || !frameCellLegal(world, targetStates, position(shape, shape.maxU() + 1, y))) {
                return false;
            }
        }
        return true;
    }

    private static boolean frameCellLegal(World world, Map<BlockPos, String> targetStates, BlockPos pos) {
        String target = targetStates.get(pos);
        if (target != null) return blockMaterial(target) == Material.OBSIDIAN;
        return liveMaterial(world, pos) == Material.OBSIDIAN;
    }

    private static boolean legalInterior(World world, Shape shape) {
        if (!withinHeight(world, shape)) return false;
        for (Cell cell : shape.cells()) {
            Material current = liveMaterial(world, cell.position());
            if (current != Material.AIR && current != Material.FIRE && current != Material.NETHER_PORTAL) {
                return false;
            }
        }
        return true;
    }

    private static boolean withinHeight(World world, Shape shape) {
        return shape.minY() - 1 >= world.getMinHeight() && shape.maxY() + 1 < world.getMaxHeight();
    }

    private static List<BlockPos> neighbours(BlockPos pos, Plane plane) {
        int u = widthCoordinate(pos, plane.axis());
        int y = pos.y();
        return List.of(position(plane.axis(), plane.coordinate(), u - 1, y),
                position(plane.axis(), plane.coordinate(), u + 1, y),
                position(plane.axis(), plane.coordinate(), u, y - 1),
                position(plane.axis(), plane.coordinate(), u, y + 1));
    }

    private static int planeCoordinate(BlockPos pos, Axis axis) {
        return axis == Axis.X ? pos.z() : pos.x();
    }

    private static int widthCoordinate(BlockPos pos, Axis axis) {
        return axis == Axis.X ? pos.x() : pos.z();
    }

    private static BlockPos position(Axis axis, int plane, int u, int y) {
        return axis == Axis.X ? new BlockPos(u, y, plane) : new BlockPos(plane, y, u);
    }

    private static Axis portalAxis(String state) {
        try {
            BlockData data = org.bukkit.Bukkit.createBlockData(state);
            if (data.getMaterial() != Material.NETHER_PORTAL || !(data instanceof Orientable orientable)) return null;
            Axis axis = orientable.getAxis();
            return axis == Axis.X || axis == Axis.Z ? axis : null;
        } catch (IllegalArgumentException | LinkageError ex) {
            return null;
        }
    }

    private static Material blockMaterial(String state) {
        try {
            return org.bukkit.Bukkit.createBlockData(state).getMaterial();
        } catch (IllegalArgumentException | LinkageError ex) {
            return null;
        }
    }

    private static Material liveMaterial(World world, BlockPos pos) {
        if (pos.y() < world.getMinHeight() || pos.y() >= world.getMaxHeight()) return null;
        return world.getBlockAt(pos.x(), pos.y(), pos.z()).getType();
    }
}
