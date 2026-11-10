package com.raildispatch.client;

import com.raildispatch.menu.YardTerminalMenu;
import com.raildispatch.network.YardTerminalPayload;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Terminal table. Left: every vehicle, shown by its nameplate name, with an editable type and
 * destination. Right: every physically coupled consist with an auto-generated, editable service
 * number. Clicking a consist filters the vehicle list to the cars inside it.
 */
public class YardTerminalScreen extends AbstractContainerScreen<YardTerminalMenu> {

    private static final int ROW_HEIGHT = 18;
    private static final int VEHICLE_VIEW = 7;
    private static final int CONSIST_VIEW = 7;
    private static final int VEHICLE_X = 8;
    private static final int TYPE_X = 60;
    private static final int TYPE_W = 48;
    private static final int DEST_X = 112;
    private static final int DEST_W = 116;
    private static final int CONSIST_X = 240;
    private static final int CONSIST_FIELD_X = 296;
    private static final int CONSIST_FIELD_W = 56;
    private static final int ROW_Y = 30;

    private final List<EditBox> typeFields = new ArrayList<>();
    private final List<EditBox> destinationFields = new ArrayList<>();
    private final List<EditBox> serviceFields = new ArrayList<>();
    private int vehicleOffset;
    private int consistOffset;
    private String selectedGroup;

    public YardTerminalScreen(YardTerminalMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        this.imageWidth = 360;
        this.imageHeight = 250;
        this.inventoryLabelY = 154;
    }

    @Override
    protected void init() {
        super.init();
        typeFields.clear();
        destinationFields.clear();
        serviceFields.clear();
        for (YardTerminalMenu.VehicleRow row : menu.vehicles()) {
            EditBox type = new EditBox(font, leftPos + TYPE_X, topPos + ROW_Y, TYPE_W, 16,
                    Component.translatable("raildispatch.terminal.type"));
            type.setMaxLength(24);
            type.setValue(row.type());
            typeFields.add(addRenderableWidget(type));

            EditBox destination = new EditBox(font, leftPos + DEST_X, topPos + ROW_Y, DEST_W, 16,
                    Component.translatable("raildispatch.terminal.destination"));
            destination.setMaxLength(48);
            destination.setValue(row.destination());
            destinationFields.add(addRenderableWidget(destination));
        }
        for (YardTerminalMenu.ConsistRow row : menu.consists()) {
            EditBox service = new EditBox(font, leftPos + CONSIST_FIELD_X, topPos + ROW_Y,
                    CONSIST_FIELD_W, 16, Component.translatable("raildispatch.terminal.service"));
            service.setMaxLength(32);
            service.setValue(row.service());
            serviceFields.add(addRenderableWidget(service));
        }
        addRenderableWidget(Button.builder(Component.translatable("raildispatch.terminal.save"), button -> {
            save();
            onClose();
        }).bounds(leftPos + CONSIST_X, topPos + 156, CONSIST_FIELD_W + 56, 16).build());
        updateLayout();
    }

    private void save() {
        Map<String, String> services = new HashMap<>();
        for (int i = 0; i < menu.consists().size() && i < serviceFields.size(); i++) {
            services.put(menu.consists().get(i).group(), serviceFields.get(i).getValue());
        }
        List<YardTerminalPayload.Entry> entries = new ArrayList<>();
        for (int i = 0; i < menu.vehicles().size(); i++) {
            YardTerminalMenu.VehicleRow row = menu.vehicles().get(i);
            entries.add(new YardTerminalPayload.Entry(row.vehicle(), destinationFields.get(i).getValue(),
                    row.group(), services.getOrDefault(row.group(), ""), typeFields.get(i).getValue()));
        }
        PacketDistributor.sendToServer(new YardTerminalPayload(entries));
    }

    private List<Integer> visibleVehicles() {
        List<Integer> indices = new ArrayList<>();
        for (int i = 0; i < menu.vehicles().size(); i++) {
            YardTerminalMenu.VehicleRow row = menu.vehicles().get(i);
            if (selectedGroup == null || selectedGroup.equals(row.group())) {
                indices.add(i);
            }
        }
        return indices;
    }

    private void updateLayout() {
        List<Integer> vehicles = visibleVehicles();
        int maxOffset = Math.max(0, vehicles.size() - VEHICLE_VIEW);
        vehicleOffset = Math.max(0, Math.min(maxOffset, vehicleOffset));
        for (int i = 0; i < menu.vehicles().size(); i++) {
            boolean visible = false;
            int y = topPos + ROW_Y;
            for (int slot = 0; slot < VEHICLE_VIEW; slot++) {
                int listIndex = vehicleOffset + slot;
                if (listIndex < vehicles.size() && vehicles.get(listIndex) == i) {
                    y = topPos + ROW_Y + slot * ROW_HEIGHT;
                    visible = true;
                    break;
                }
            }
            typeFields.get(i).setY(y);
            typeFields.get(i).setVisible(visible);
            destinationFields.get(i).setY(y);
            destinationFields.get(i).setVisible(visible);
        }
        for (int i = 0; i < serviceFields.size(); i++) {
            int y = topPos + ROW_Y + (i - consistOffset) * ROW_HEIGHT;
            boolean visible = i >= consistOffset && i < consistOffset + CONSIST_VIEW;
            serviceFields.get(i).setY(y);
            serviceFields.get(i).setVisible(visible);
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (mouseX >= leftPos + CONSIST_X && mouseX < leftPos + CONSIST_FIELD_X
                && mouseY >= topPos + ROW_Y) {
            int row = (int) ((mouseY - (topPos + ROW_Y)) / ROW_HEIGHT);
            int index = consistOffset + row;
            if (row >= 0 && row < CONSIST_VIEW && index < menu.consists().size()) {
                String group = menu.consists().get(index).group();
                selectedGroup = selectedGroup != null && selectedGroup.equals(group) ? null : group;
                vehicleOffset = 0;
                updateLayout();
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        int maxVehicles = Math.max(0, visibleVehicles().size() - VEHICLE_VIEW);
        int maxConsists = Math.max(0, menu.consists().size() - CONSIST_VIEW);
        if (mouseX < leftPos + CONSIST_X) {
            vehicleOffset = Math.max(0, Math.min(maxVehicles, vehicleOffset - (int) Math.signum(scrollY)));
        } else {
            consistOffset = Math.max(0, Math.min(maxConsists, consistOffset - (int) Math.signum(scrollY)));
        }
        updateLayout();
        return true;
    }

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        graphics.fill(leftPos, topPos, leftPos + imageWidth, topPos + imageHeight, 0xFF151518);
        graphics.fill(leftPos + 3, topPos + 3, leftPos + imageWidth - 3, topPos + imageHeight - 3,
                0xFF2B2D31);
        graphics.fill(leftPos + 4, topPos + 28, leftPos + 236, topPos + 156, 0xFF1E2024);
        graphics.fill(leftPos + 238, topPos + 28, leftPos + imageWidth - 4, topPos + 156, 0xFF1E2024);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics, mouseX, mouseY, partialTick);
        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.drawString(font, Component.translatable("raildispatch.terminal.title"),
                leftPos + 8, topPos + 7, 0xFFC9A85C, false);
        graphics.drawString(font, Component.translatable("raildispatch.terminal.vehicles"),
                leftPos + VEHICLE_X, topPos + 18, 0xFFC9A85C, false);
        graphics.drawString(font, Component.translatable("raildispatch.terminal.type"),
                leftPos + TYPE_X, topPos + 18, 0xFFC9A85C, false);
        graphics.drawString(font, Component.translatable("raildispatch.terminal.destination"),
                leftPos + DEST_X, topPos + 18, 0xFFC9A85C, false);
        graphics.drawString(font, Component.translatable("raildispatch.terminal.consists"),
                leftPos + CONSIST_X, topPos + 18, 0xFFC9A85C, false);
        graphics.drawString(font, Component.translatable("raildispatch.terminal.service"),
                leftPos + CONSIST_FIELD_X, topPos + 18, 0xFFC9A85C, false);

        List<Integer> vehicles = visibleVehicles();
        for (int slot = 0; slot < VEHICLE_VIEW; slot++) {
            int listIndex = vehicleOffset + slot;
            if (listIndex >= vehicles.size()) {
                break;
            }
            int y = topPos + ROW_Y + slot * ROW_HEIGHT;
            graphics.drawString(font, font.plainSubstrByWidth(
                            menu.vehicles().get(vehicles.get(listIndex)).label(), 50),
                    leftPos + VEHICLE_X, y + 4, 0xFFB0B4BA, false);
        }
        for (int slot = 0; slot < CONSIST_VIEW; slot++) {
            int index = consistOffset + slot;
            if (index >= menu.consists().size()) {
                break;
            }
            int y = topPos + ROW_Y + slot * ROW_HEIGHT;
            YardTerminalMenu.ConsistRow row = menu.consists().get(index);
            boolean selected = row.group().equals(selectedGroup);
            if (selected) {
                graphics.fill(leftPos + CONSIST_X - 2, y - 1,
                        leftPos + CONSIST_FIELD_X - 2, y + 16, 0x553C6E3C);
            }
            graphics.drawString(font, row.label(), leftPos + CONSIST_X, y + 4,
                    selected ? 0xFF9ADF9A : 0xFFB0B4BA, false);
        }
        graphics.drawString(font, Component.translatable("raildispatch.terminal.all_vehicles"),
                leftPos + CONSIST_X, topPos + 146, 0xFF8C9096, false);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
