package com.raildispatch.client;

import com.raildispatch.api.SignalAspect;
import com.raildispatch.autopilot.AutopilotState;
import com.raildispatch.block.entity.AutopilotConsoleBlockEntity;
import com.raildispatch.menu.AutopilotConsoleMenu;
import com.raildispatch.network.AutopilotCommandPayload;
import com.raildispatch.network.AutopilotTuningPayload;
import java.util.Locale;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import com.simibubi.create.foundation.gui.AllGuiTextures;
import com.simibubi.create.foundation.gui.menu.AbstractSimiContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

/**
 * The autopilot console's screen: a destination field, three buttons and the live status.
 *
 * <p>No texture: the readout is the point, and a flat panel keeps the mod free of binary assets that would
 * have to be redrawn for every resource pack.
 *
 * <p>The status is read from the menu's {@code ContainerData}, which vanilla mirrors while the menu is
 * open. The destination field is prefilled from the block entity's synced tag, so closing and reopening
 * the screen does not lose the name that is currently programmed.
 */
public class AutopilotConsoleScreen extends AbstractSimiContainerScreen<AutopilotConsoleMenu> {

    private static final int PANEL = 0xFF14181C;
    private static final int BORDER = 0xFF6E6E6E;
    private static final int LABEL = 0xFFD0D0D0;
    private static final int VALUE = 0xFFB8E0FF;

    private EditBox destinationBox;
    private EditBox maxSpeedBox;
    private EditBox accelerationBox;
    private EditBox brakeDecelBox;
    private EditBox approachBox;
    private EditBox smoothingBox;

    public AutopilotConsoleScreen(AutopilotConsoleMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        this.imageWidth = 360;
        this.imageHeight = 226;
    }

    @Override
    protected void init() {
        super.init();
        String current = menu.console() == null ? "" : menu.console().destination();

        destinationBox = new EditBox(font, leftPos + 30, topPos + 18, 138, 16,
                Component.translatable("raildispatch.autopilot.destination"));
        destinationBox.setMaxLength(96);
        destinationBox.setValue(current);
        addRenderableWidget(destinationBox);

        addRenderableWidget(Button.builder(
                        Component.translatable("raildispatch.autopilot.start"),
                        button -> send(AutopilotConsoleBlockEntity.ACTION_START))
                .bounds(leftPos + 8, topPos + 40, 50, 18).build());
        addRenderableWidget(Button.builder(
                        Component.translatable("raildispatch.autopilot.stop"),
                        button -> send(AutopilotConsoleBlockEntity.ACTION_STOP))
                .bounds(leftPos + 62, topPos + 40, 50, 18).build());
        addRenderableWidget(Button.builder(
                        Component.translatable("raildispatch.autopilot.clear"),
                        button -> send(AutopilotConsoleBlockEntity.ACTION_CLEAR))
                .bounds(leftPos + 116, topPos + 40, 52, 18).build());

        AutopilotConsoleBlockEntity console = menu.console();
        maxSpeedBox = tuningBox(264, 24, console == null ? 20.0D : console.tuningMaxSpeed());
        accelerationBox = tuningBox(264, 40, console == null ? 0.22D : console.tuningAcceleration());
        brakeDecelBox = tuningBox(264, 56, console == null ? 0.35D : console.tuningBrakeDecel());
        approachBox = tuningBox(264, 72, console == null ? 1.0D : console.tuningApproach());
        smoothingBox = tuningBox(264, 88, console == null ? 0.35D : console.tuningSmoothing());

        addRenderableWidget(Button.builder(
                        Component.translatable("raildispatch.autopilot.apply"),
                        button -> sendTuning())
                .bounds(leftPos + 264, topPos + 104, 88, 16).build());
    }

    private EditBox tuningBox(int x, int y, double value) {
        EditBox box = new EditBox(font, leftPos + x, topPos + y, 88, 14,
                Component.translatable("raildispatch.autopilot.tuning"));
        box.setMaxLength(12);
        box.setValue(String.format(Locale.ROOT, "%.2f", value));
        addRenderableWidget(box);
        return box;
    }

    private void sendTuning() {
        PacketDistributor.sendToServer(new AutopilotTuningPayload(menu.pos(),
                parse(maxSpeedBox, 20.0D), parse(accelerationBox, 0.22D),
                parse(brakeDecelBox, 0.35D), parse(approachBox, 1.0D),
                parse(smoothingBox, 0.35D)));
    }

    private static double parse(EditBox box, double fallback) {
        if (box == null) {
            return fallback;
        }
        try {
            return Double.parseDouble(box.getValue().trim().replace(',', '.'));
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }

    private void send(int action) {
        String destination = destinationBox == null ? "" : destinationBox.getValue();
        PacketDistributor.sendToServer(
                new AutopilotCommandPayload(menu.pos(), destination, action, -1));
    }

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        // Create's own train-schedule background for the upper panel.
        AllGuiTextures.SCHEDULE.render(graphics, leftPos, topPos);
        // Same offsets as Create's ScheduleScreen: player inventory at (38, 122), slots at (46, 140).
        renderPlayerInventory(graphics, leftPos + 38, topPos + 122);
        // Separate Create-style parameter column on the right.
        graphics.fill(leftPos + 256, topPos, leftPos + imageWidth, topPos + imageHeight, PANEL);
        // Sunken square for the Create schedule slot, aligned with the menu slot at (8, 18).
        graphics.fill(leftPos + 7, topPos + 17, leftPos + 25, topPos + 35, 0xFF080808);
        graphics.fill(leftPos + 7, topPos + 17, leftPos + 25, topPos + 18, BORDER);
        graphics.fill(leftPos + 7, topPos + 34, leftPos + 25, topPos + 35, BORDER);
        graphics.fill(leftPos + 7, topPos + 17, leftPos + 8, topPos + 35, BORDER);
        graphics.fill(leftPos + 24, topPos + 17, leftPos + 25, topPos + 35, BORDER);
    }

    @Override
    protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        graphics.drawString(font, title, titleLabelX, titleLabelY, LABEL, false);

        int flags = menu.status(AutopilotConsoleBlockEntity.DATA_FLAGS);
        double speed = menu.status(AutopilotConsoleBlockEntity.DATA_SPEED) / 100.0D;
        double target = menu.status(AutopilotConsoleBlockEntity.DATA_TARGET) / 100.0D;
        int remaining = menu.status(AutopilotConsoleBlockEntity.DATA_REMAINING);
        int node = menu.status(AutopilotConsoleBlockEntity.DATA_WAYPOINT);
        int nodes = menu.status(AutopilotConsoleBlockEntity.DATA_WAYPOINTS);
        boolean routed = menu.status(AutopilotConsoleBlockEntity.DATA_ROUTE) != 0;
        int drive = menu.status(AutopilotConsoleBlockEntity.DATA_DRIVE);
        int brake = menu.status(AutopilotConsoleBlockEntity.DATA_BRAKE);

        int y = 56;
        y = line(graphics, "raildispatch.autopilot.status",
                state().translation().getString(), y, ChatFormatting.AQUA);
        y = line(graphics, "raildispatch.autopilot.speed",
                String.format(Locale.ROOT, "%.1f / %.1f", speed, target), y, VALUE);
        y = line(graphics, "raildispatch.autopilot.signal",
                signal().translation().getString(), y, signalColor(signal()));
        String route = routed
                ? (remaining < 0 ? "locked" : remaining + "b " + node + "/" + nodes)
                : "free";
        y = line(graphics, "raildispatch.autopilot.route", route, y, routed ? 0xFF55FF55 : 0xFF707070);
        y = line(graphics, "raildispatch.autopilot.output", drive + "% / " + brake + "%", y, VALUE);

        if (menu.status(AutopilotConsoleBlockEntity.DATA_SCHEDULE) != 0) {
            int entry = menu.status(AutopilotConsoleBlockEntity.DATA_SCHEDULE_ENTRY);
            int entries = menu.status(AutopilotConsoleBlockEntity.DATA_SCHEDULE_ENTRIES);
            y = line(graphics, "raildispatch.autopilot.schedule", entry + "/" + entries, y, 0xFFE0C060);
        }

        String notes = "";
        if ((flags & AutopilotConsoleBlockEntity.FLAG_WRONG_DIRECTION) != 0) {
            notes += " WRONG WAY";
        }
        if ((flags & AutopilotConsoleBlockEntity.FLAG_STAND_DOWN) != 0) {
            notes += " CAB";
        }
        if ((flags & AutopilotConsoleBlockEntity.FLAG_REDSTONE) != 0) {
            notes += " REDSTONE";
        }
        if ((flags & AutopilotConsoleBlockEntity.FLAG_DERAILED) != 0) {
            notes += " DERAILED";
        }
        if (!notes.isEmpty()) {
            graphics.drawString(font, Component.literal(notes.trim()), 8, y, 0xFFFFAA55, false);
            y += 10;
        }
        String detail = menu.console() == null ? "" : menu.console().detail();
        if (detail != null && !detail.isBlank()) {
            graphics.drawString(font, Component.literal(font.plainSubstrByWidth(detail, 160)),
                    8, y, 0xFF909090, false);
        }

        graphics.drawString(font, Component.translatable("raildispatch.autopilot.tuning"),
                184, 8, 0xFFE0C060, false);
        tuningLabel(graphics, "raildispatch.autopilot.max_speed", 16);
        tuningLabel(graphics, "raildispatch.autopilot.acceleration", 32);
        tuningLabel(graphics, "raildispatch.autopilot.brake_decel", 48);
        tuningLabel(graphics, "raildispatch.autopilot.approach", 64);
        tuningLabel(graphics, "raildispatch.autopilot.smoothing", 80);
    }

    private void tuningLabel(GuiGraphics graphics, String key, int y) {
        graphics.drawString(font, Component.translatable(key), 264, y, LABEL, false);
    }

    private int line(GuiGraphics graphics, String key, String value, int y, ChatFormatting colour) {
        Integer rgb = colour.getColor();
        return line(graphics, key, value, y, rgb == null ? 0xFFFFFF : rgb);
    }

    private int line(GuiGraphics graphics, String key, String value, int y, int colour) {
        graphics.drawString(font, Component.translatable(key), 8, y, LABEL, false);
        graphics.drawString(font, Component.literal(value), 76, y, colour, false);
        return y + 8;
    }

    private AutopilotState state() {
        AutopilotState[] values = AutopilotState.values();
        int ordinal = menu.status(AutopilotConsoleBlockEntity.DATA_STATE);
        return values[Math.max(0, Math.min(values.length - 1, ordinal))];
    }

    private SignalAspect signal() {
        SignalAspect[] values = SignalAspect.values();
        int ordinal = menu.status(AutopilotConsoleBlockEntity.DATA_SIGNAL);
        return values[Math.max(0, Math.min(values.length - 1, ordinal))];
    }

    private static int signalColor(SignalAspect aspect) {
        return switch (aspect) {
            case CLEAR -> 0x55FF55;
            case CAUTION -> 0xFFFF55;
            case DANGER -> 0xFF5555;
            case NONE -> 0x808080;
        };
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (destinationBox != null && destinationBox.isFocused()) {
            if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
                send(AutopilotConsoleBlockEntity.ACTION_START);
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
                destinationBox.setFocused(false);
                return true;
            }
            if (destinationBox.keyPressed(keyCode, scanCode, modifiers)) {
                return true;
            }
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean charTyped(char character, int modifiers) {
        if (destinationBox != null && destinationBox.isFocused()
                && destinationBox.charTyped(character, modifiers)) {
            return true;
        }
        return super.charTyped(character, modifiers);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (destinationBox != null && destinationBox.isFocused()
                && !destinationBox.isMouseOver(mouseX, mouseY)) {
            destinationBox.setFocused(false);
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }
}
