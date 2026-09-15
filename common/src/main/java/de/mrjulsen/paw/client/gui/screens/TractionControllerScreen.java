package de.mrjulsen.paw.client.gui.screens;

import de.mrjulsen.mcdragonlib.client.gui.DLScreen;
import de.mrjulsen.mcdragonlib.client.util.Graphics;
import de.mrjulsen.mcdragonlib.client.util.GuiUtils;
import de.mrjulsen.mcdragonlib.core.EAlignment;
import de.mrjulsen.mcdragonlib.util.TextUtils;
import de.mrjulsen.paw.PantographsAndWires;
import de.mrjulsen.paw.client.gui.ModGuiUtils;
import de.mrjulsen.paw.network.TrainSettingsPacket;
import de.mrjulsen.paw.traction.TrainSettings;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;

/**
 * A Traction Controller's settings: one button per setting, each click moving to the next choice.
 * Changes are sent when the screen closes, and only if something changed.
 */
public class TractionControllerScreen extends DLScreen {
    private static final ResourceLocation TEXTURE = new ResourceLocation(PantographsAndWires.MOD_ID, "textures/gui/cantilever_settings.png");
    private static final int TEXTURE_WIDTH = 256;
    private static final int TEXTURE_HEIGHT = 256;
    private static final int GUI_WIDTH = 224;
    private static final int GUI_HEIGHT = 160;
    private static final int ROW_HEIGHT = 24;
    private static final int BUTTON_HEIGHT = 20;

    private final TrainSettingsPacket.Target target;
    private final TrainSettings original;
    private final MutableComponent hint = TextUtils.translate("gui." + PantographsAndWires.MOD_ID + ".traction_controller.hint");
    private TrainSettings settings;
    private int guiLeft;
    private int guiTop;

    public TractionControllerScreen(TrainSettingsPacket.Target target, TrainSettings settings) {
        super(TextUtils.translate("gui." + PantographsAndWires.MOD_ID + ".traction_controller.title"));
        this.target = target;
        this.original = settings;
        this.settings = settings;
    }

    @Override
    protected void init() {
        super.init();
        guiLeft = width() / 2 - GUI_WIDTH / 2;
        guiTop = height() / 2 - GUI_HEIGHT / 2;
        String mod = PantographsAndWires.MOD_ID;
        int x = guiLeft + 16;
        int buttonWidth = GUI_WIDTH - 32;
        int y = guiTop + 36;

        addCycleButton(mod, TrainSettings.SoundPack.class, x, y, buttonWidth, BUTTON_HEIGHT,
            TextUtils.translate(settings.soundPack().getEnumTranslationKey(mod)), settings.soundPack(),
            (button, value) -> settings = settings.withSoundPack(value), null);
        y += ROW_HEIGHT;
        addCycleButton(mod, TrainSettings.SpeedUnit.class, x, y, buttonWidth, BUTTON_HEIGHT,
            TextUtils.translate(settings.speedUnit().getEnumTranslationKey(mod)), settings.speedUnit(),
            (button, value) -> settings = settings.withSpeedUnit(value), null);
        y += ROW_HEIGHT;
        addCycleButton(mod, TrainSettings.SteamSound.class, x, y, buttonWidth, BUTTON_HEIGHT,
            TextUtils.translate(settings.steamSound().getEnumTranslationKey(mod)), settings.steamSound(),
            (button, value) -> settings = settings.withSteamSound(value), null);

        addButton(guiLeft + GUI_WIDTH / 2 - 40, guiTop + GUI_HEIGHT - 28, 80, BUTTON_HEIGHT, CommonComponents.GUI_DONE, button -> onClose(), null);
    }

    @Override
    public void onClose() {
        if (!settings.equals(original)) {
            PantographsAndWires.net().CHANNEL.sendToServer(new TrainSettingsPacket(target, settings));
        }
        super.onClose();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void renderMainLayer(Graphics graphics, int mouseX, int mouseY, float partialTicks) {
        renderScreenBackground(graphics);
        GuiUtils.drawTexture(TEXTURE, graphics, guiLeft, guiTop, GUI_WIDTH, GUI_HEIGHT, 0, 0, TEXTURE_WIDTH, TEXTURE_HEIGHT);

        graphics.poseStack().pushPose();
        graphics.poseStack().translate(0, 0, 500);
        super.renderMainLayer(graphics, mouseX, mouseY, partialTicks);

        int halfWidth = font.width(title) / 2;
        ModGuiUtils.renderRoundedBox(graphics, width() / 2 - halfWidth - 3, guiTop + 6, halfWidth * 2 + 6, font.lineHeight + 3, 0x55000000);
        GuiUtils.drawString(graphics, font, width() / 2, guiTop + 8, title, 0xFFFFFFFF, EAlignment.CENTER, false);
        GuiUtils.drawString(graphics, font, width() / 2, guiTop + 22, hint, 0xFFE0E8FF, EAlignment.CENTER, false);
        graphics.poseStack().popPose();
    }
}
