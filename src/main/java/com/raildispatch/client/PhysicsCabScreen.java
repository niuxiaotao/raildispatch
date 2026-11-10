package com.raildispatch.client;

import com.raildispatch.api.SignalAspect;
import com.raildispatch.block.entity.PhysicsCabBlockEntity;
import com.raildispatch.cab.CabState;
import com.raildispatch.menu.PhysicsCabMenu;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

/**
 * The cab screen. It deliberately uses no texture: the status readout is the point, and a flat panel
 * keeps the mod free of binary assets that would have to be redrawn for every resource pack.
 */
public class PhysicsCabScreen extends AbstractContainerScreen<PhysicsCabMenu> {

    private static final int PANEL = 0xFF1B1B1B;
    private static final int BORDER = 0xFF6E6E6E;
    private static final int LABEL = 0xFFD0D0D0;

    public PhysicsCabScreen(PhysicsCabMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        this.imageWidth = 176;
        this.imageHeight = 166;
        this.inventoryLabelY = this.imageHeight - 94;
    }

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        graphics.fill(leftPos, topPos, leftPos + imageWidth, topPos + imageHeight, PANEL);
        graphics.fill(leftPos, topPos, leftPos + imageWidth, topPos + 1, BORDER);
        graphics.fill(leftPos, topPos + imageHeight - 1, leftPos + imageWidth, topPos + imageHeight, BORDER);
        graphics.fill(leftPos, topPos, leftPos + 1, topPos + imageHeight, BORDER);
        graphics.fill(leftPos + imageWidth - 1, topPos, leftPos + imageWidth, topPos + imageHeight, BORDER);
        // Sunken slot for the schedule.
        graphics.fill(leftPos + 79, topPos + 35, leftPos + 99, topPos + 55, 0xFF0A0A0A);
    }

    @Override
    protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        graphics.drawString(font, title, titleLabelX, titleLabelY, LABEL, false);

        CabState state = state();
        SignalAspect signal = signal();
        double speed = menu.status(PhysicsCabBlockEntity.DATA_SPEED) / 100.0D;
        boolean routed = menu.status(PhysicsCabBlockEntity.DATA_ROUTE) != 0;
        int bogeys = menu.status(PhysicsCabBlockEntity.DATA_BOGEYS);
        int entry = menu.status(PhysicsCabBlockEntity.DATA_ENTRY);
        int entries = menu.status(PhysicsCabBlockEntity.DATA_ENTRIES);
        boolean hasSchedule = menu.status(PhysicsCabBlockEntity.DATA_SCHEDULE) != 0;

        int y = 20;
        y = line(graphics, "raildispatch.gui.physics_cab.schedule",
                hasSchedule ? entry + 1 + "/" + entries : "--", y, ChatFormatting.WHITE);
        y = line(graphics, "raildispatch.gui.physics_cab.status",
                state.translation().getString(), y, ChatFormatting.AQUA);
        line(graphics, "raildispatch.gui.physics_cab.signal",
                signal.translation().getString(), y, signalColor(signal));
        line(graphics, "raildispatch.gui.physics_cab.speed",
                String.format(java.util.Locale.ROOT, "%.1f m/s", speed), y + 10, 0xFFB0B0B0);
        line(graphics, "raildispatch.gui.physics_cab.route",
                routed ? "locked" : "free", y + 20, routed ? 0xFF55FF55 : 0xFF606060);
        graphics.drawString(font, Component.literal("bogeys: " + bogeys), 8, y + 30, 0xFF909090, false);
    }

    private int line(GuiGraphics graphics, String key, String value, int y, ChatFormatting colour) {
        Integer rgb = colour.getColor();
        return line(graphics, key, value, y, rgb == null ? 0xFFFFFF : rgb);
    }

    private int line(GuiGraphics graphics, String key, String value, int y, int colour) {
        graphics.drawString(font, Component.translatable(key), 8, y, LABEL, false);
        graphics.drawString(font, Component.literal(value), 92, y, colour, false);
        return y + 10;
    }

    private static int signalColor(SignalAspect aspect) {
        return switch (aspect) {
            case CLEAR -> 0x55FF55;
            case CAUTION -> 0xFFFF55;
            case DANGER -> 0xFF5555;
            case NONE -> 0x808080;
        };
    }

    private CabState state() {
        int ordinal = menu.status(PhysicsCabBlockEntity.DATA_STATE);
        CabState[] values = CabState.values();
        return values[Math.max(0, Math.min(values.length - 1, ordinal))];
    }

    private SignalAspect signal() {
        int ordinal = menu.status(PhysicsCabBlockEntity.DATA_SIGNAL);
        SignalAspect[] values = SignalAspect.values();
        return values[Math.max(0, Math.min(values.length - 1, ordinal))];
    }
}
