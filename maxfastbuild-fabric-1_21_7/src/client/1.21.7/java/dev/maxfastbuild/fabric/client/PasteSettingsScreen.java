package dev.maxfastbuild.fabric.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Paste options screen (Minecraft 1.21.7): filters + instant toggle shown before a paste starts.
 * Drawn with plain rects and text — no widgets — so both client versions stay identical; the
 * only version-specific part is the render/mouse entry points (classic render phase here).
 */
public final class PasteSettingsScreen extends Screen {
    private static final Component[] LABELS = {
            Component.translatable("maxfastbuild.paste.option.skip_fluids"),
            Component.translatable("maxfastbuild.paste.option.skip_entities"),
            Component.translatable("maxfastbuild.paste.option.skip_mobs"),
            Component.translatable("maxfastbuild.paste.option.skip_drops"),
            Component.translatable("maxfastbuild.paste.option.skip_contents"),
            Component.translatable("maxfastbuild.paste.option.skip_nbt"),
            Component.translatable("maxfastbuild.paste.option.instant"),
    };

    /** Order matches {@link PasteSettingsLayout.Option}. */
    private final boolean[] checked = new boolean[PasteSettingsLayout.Option.values().length];

    public PasteSettingsScreen() {
        super(Component.translatable("maxfastbuild.paste.settings.title"));
        PasteSettings current = PasteController.settings();
        checked[0] = current.skipFluids();
        checked[1] = current.skipEntities();
        checked[2] = current.skipMobs();
        checked[3] = current.skipDrops();
        checked[4] = current.skipContents();
        checked[5] = current.skipNbt();
        checked[6] = PasteController.instant();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        super.render(graphics, mouseX, mouseY, delta);
        graphics.drawCenteredString(font,
                Component.translatable("maxfastbuild.paste.settings.title"),
                width / 2, PasteSettingsLayout.TITLE_Y, 0xFFFFFFFF);
        for (int i = 0; i < checked.length; i++) {
            drawRow(graphics, i, mouseX, mouseY);
        }
        drawMetrics(graphics);
        // Pasting (instant or queued) can disrupt redstone machines; warn on every paste.
        graphics.drawCenteredString(font, Component.translatable("maxfastbuild.paste.warning.redstone"),
                width / 2, PasteSettingsLayout.WARNING_Y, 0xFFFF5A5A);
        drawButton(graphics, PasteSettingsLayout.confirm(width), "maxfastbuild.paste.settings.start", 0xFF8EE9FF, mouseX, mouseY);
        drawButton(graphics, PasteSettingsLayout.cancel(width), "maxfastbuild.paste.settings.cancel", 0xFFB8C2CE, mouseX, mouseY);
    }

    private void drawMetrics(GuiGraphics graphics) {
        PasteMetrics metrics = PasteController.pasteMetrics();
        ServerCapabilities.Limits limits = ServerCapabilities.current();
        if (limits == null) {
            graphics.drawCenteredString(font, Component.translatable("maxfastbuild.paste.limits.unavailable"),
                    width / 2, PasteSettingsLayout.METRICS_Y, 0xFFFFC44D);
            return;
        }
        graphics.drawCenteredString(font, Component.translatable("maxfastbuild.paste.limits",
                        limits.maxSizeX(), limits.maxSizeY(), limits.maxSizeZ(), limits.maxRegionBlocks(), limits.maxAffectedBlocks()),
                width / 2, PasteSettingsLayout.METRICS_Y, 0xFFB8C2CE);
        graphics.drawCenteredString(font, Component.translatable("maxfastbuild.paste.current",
                        metrics.sizeX(), metrics.sizeY(), metrics.sizeZ(), metrics.regionBlocks(), metrics.candidateBlocks()),
                width / 2, PasteSettingsLayout.METRICS_SECOND_Y, 0xFF8EE9FF);
    }

    private void drawRow(GuiGraphics graphics, int index, int mouseX, int mouseY) {
        PasteSettingsLayout.Rect box = PasteSettingsLayout.checkbox(index, width);
        boolean hovered = PasteSettingsLayout.row(index, width).contains(mouseX, mouseY);
        graphics.fill(box.x(), box.y(), box.x() + box.w(), box.y() + box.h(), 0xFF9AA5B1);
        graphics.fill(box.x() + 2, box.y() + 2, box.x() + box.w() - 2, box.y() + box.h() - 2,
                checked[index] ? 0xFF4CAF50 : 0xFF1A1D21);
        graphics.drawString(font, LABELS[index], PasteSettingsLayout.labelX(width),
                PasteSettingsLayout.labelCenterY(index), hovered ? 0xFFFFFFFF : 0xFFE7EEF7);
    }

    private void drawButton(GuiGraphics graphics, PasteSettingsLayout.Rect rect, String key, int textColor, int mouseX, int mouseY) {
        boolean hovered = rect.contains(mouseX, mouseY);
        graphics.fill(rect.x(), rect.y(), rect.x() + rect.w(), rect.y() + rect.h(), 0xFF9AA5B1);
        graphics.fill(rect.x() + 1, rect.y() + 1, rect.x() + rect.w() - 1, rect.y() + rect.h() - 1,
                hovered ? 0xFF3C4249 : 0xFF2A2E33);
        graphics.drawCenteredString(font, Component.translatable(key), rect.x() + rect.w() / 2, rect.y() + 3,
                hovered ? 0xFFFFFFFF : textColor);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button != 0) return super.mouseClicked(mouseX, mouseY, button);
        int row = PasteSettingsLayout.hitRow(width, mouseX, mouseY);
        if (row >= 0) {
            checked[row] = !checked[row];
            return true;
        }
        if (PasteSettingsLayout.confirm(width).contains(mouseX, mouseY)) {
            confirm();
            return true;
        }
        if (PasteSettingsLayout.cancel(width).contains(mouseX, mouseY)) {
            onClose();
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    private void confirm() {
        PasteController.confirmStart(new PasteSettings(checked[0], checked[1], checked[2], checked[3], checked[4], checked[5]), checked[6]);
        onClose();
    }
}
