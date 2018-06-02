package org.dimdev.vanillafix.crashes.mixins.client;

import com.google.common.util.concurrent.ListenableFutureTask;
import net.minecraft.client.Minecraft;
import net.minecraft.client.audio.SoundHandler;
import net.minecraft.client.gui.*;
import net.minecraft.client.gui.toasts.GuiToast;
import net.minecraft.client.gui.toasts.IToast;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.client.renderer.EntityRenderer;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.RenderGlobal;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.client.resources.*;
import net.minecraft.client.resources.data.MetadataSerializer;
import net.minecraft.client.settings.GameSettings;
import net.minecraft.client.shader.Framebuffer;
import net.minecraft.crash.CrashReport;
import net.minecraft.profiler.ISnooperInfo;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.util.IThreadListener;
import net.minecraft.util.MinecraftError;
import net.minecraft.util.ReportedException;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.text.TextComponentString;
import net.minecraftforge.fml.client.SplashProgress;
import org.apache.logging.log4j.Logger;
import org.dimdev.vanillafix.ModConfig;
import org.dimdev.vanillafix.VanillaFix;
import org.dimdev.vanillafix.VanillaFixLoadingPlugin;
import org.dimdev.vanillafix.crashes.*;
import org.lwjgl.LWJGLException;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.Display;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import javax.annotation.Nullable;
import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.FutureTask;

@Mixin(Minecraft.class)
public abstract class MixinMinecraft implements IThreadListener, ISnooperInfo, IPatchedMinecraft {
    // @formatter:off
    @Shadow @Final private static Logger LOGGER;
    @Shadow volatile boolean running;
    @Shadow private boolean hasCrashed;
    @Shadow private CrashReport crashReporter;
    @Shadow private long debugCrashKeyPressTime;
    @Shadow public static byte[] memoryReserve;
    @Shadow public RenderGlobal renderGlobal;
    @Shadow public GameSettings gameSettings;
    @Shadow public GuiIngame ingameGUI;
    @Shadow public EntityRenderer entityRenderer;
    @Shadow @Final private Queue<FutureTask<?>> scheduledTasks;
    @Shadow private boolean actionKeyF3;
    @Shadow @Nullable private IntegratedServer integratedServer;
    @Shadow private boolean integratedServerIsRunning;
    @Shadow @Nullable public GuiScreen currentScreen;
    @Shadow public int displayWidth;
    @Shadow public int displayHeight;
    @Shadow public TextureManager renderEngine;
    @Shadow public FontRenderer fontRenderer;
    @Shadow private int leftClickCounter;
    @Shadow private Framebuffer framebufferMc;
    @Shadow private IReloadableResourceManager mcResourceManager;
    @Shadow private SoundHandler mcSoundHandler;
    @Shadow @Final private List<IResourcePack> defaultResourcePacks;
    @Shadow private LanguageManager mcLanguageManager;
    @Shadow @Final private MetadataSerializer metadataSerializer_;

    @Shadow @SuppressWarnings("RedundantThrows") private void init() throws LWJGLException, IOException {}
    @Shadow @SuppressWarnings("RedundantThrows") private void runGameLoop() throws IOException {}
    @Shadow public void displayGuiScreen(@Nullable GuiScreen guiScreenIn) {}
    @Shadow public CrashReport addGraphicsAndWorldToCrashReport(CrashReport theCrash) { return null; }
    @Shadow public void shutdownMinecraftApplet() {}
    @Shadow @Nullable public abstract NetHandlerPlayClient getConnection();
    @Shadow public static long getSystemTime() { return 0; }
    @Shadow public abstract void loadWorld(@Nullable WorldClient worldClientIn);
    @Shadow public abstract GuiToast getToastGui();
    @Shadow protected abstract void createDisplay() throws LWJGLException;
    @Shadow public abstract void refreshResources();
    @Shadow public abstract TextureManager getTextureManager();
    @Shadow public abstract void updateDisplay();
    @Shadow protected abstract void checkGLError(String message);
    // @formatter:on

    private boolean crashIntegratedServerNextTick;
    private int clientCrashCount = 0;
    private int serverCrashCount = 0;

    /**
     * @reason Allows the player to choose to return to the title screen after a crash, or get
     * a pasteable link to the crash report on paste.dimdev.org.
     */
    @Overwrite
    public void run() {
        running = true;

        try {
            init();
        } catch (Throwable throwable) {
            CrashReport report = CrashReport.makeCrashReport(throwable, "Initializing game");
            displayInitErrorScreen(addGraphicsAndWorldToCrashReport(report));
            return;
        }

        try {
            while (running) {
                if (!hasCrashed || crashReporter == null) {
                    try {
                        runGameLoop();
                    } catch (ReportedException e) {
                        clientCrashCount++;
                        addGraphicsAndWorldToCrashReport(e.getCrashReport());
                        addInfoToCrash(e.getCrashReport());
                        freeMemory();
                        LOGGER.fatal("Reported exception thrown!", e);
                        displayCrashScreen(e.getCrashReport());
                    } catch (Throwable e) {
                        clientCrashCount++;
                        CrashReport report = new CrashReport("Unexpected error", e);
                        addGraphicsAndWorldToCrashReport(report);
                        addInfoToCrash(report);
                        freeMemory();
                        LOGGER.fatal("Unreported exception thrown!", e);
                        displayCrashScreen(report);
                    }
                } else {
                    serverCrashCount++;
                    addInfoToCrash(crashReporter);
                    freeMemory();
                    displayCrashScreen(crashReporter);
                    hasCrashed = false;
                    crashReporter = null;
                }
            }
        } catch (MinecraftError ignored) {
        } finally {
            shutdownMinecraftApplet();
        }
    }

    public void addInfoToCrash(CrashReport report) {
        report.getCategory().addDetail("Client Crashes Since Restart", () -> String.valueOf(clientCrashCount));
        report.getCategory().addDetail("Integrated Server Crashes Since Restart", () -> String.valueOf(serverCrashCount));
    }


    public void displayInitErrorScreen(CrashReport report) {
        CrashUtils.outputReport(report);
        try {
            VanillaFixLoadingPlugin.initialize();

            try {
                URL url = VanillaFix.class.getProtectionDomain().getCodeSource().getLocation();
                if (url.getProtocol().equals("jar")) url = new URL(url.getFile().substring(0, url.getFile().indexOf('!')));
                File modFile = new File(url.toURI());
                defaultResourcePacks.add(modFile.isDirectory() ? new FolderResourcePack(modFile) : new FileResourcePack(modFile));
            } catch (Throwable t) {
                LOGGER.error("Failed to load VanillaFix resource pack", t);
            }

            mcResourceManager = new SimpleReloadableResourceManager(metadataSerializer_);
            renderEngine = new TextureManager(mcResourceManager);
            mcResourceManager.registerReloadListener(renderEngine);

            mcLanguageManager = new LanguageManager(metadataSerializer_, gameSettings.language);
            mcResourceManager.registerReloadListener(mcLanguageManager);

            refreshResources(); // TODO: Why is this necessary?
            fontRenderer = new FontRenderer(gameSettings, new ResourceLocation("textures/font/ascii.png"), renderEngine, false);
            mcResourceManager.registerReloadListener(fontRenderer);

            mcSoundHandler = new SoundHandler(mcResourceManager, gameSettings);
            mcResourceManager.registerReloadListener(mcSoundHandler);

            running = true;
            //noinspection deprecation
            SplashProgress.pause();// Disable the forge splash progress screen
            runGUILoop(new GuiInitErrorScreen(report));
        } catch (Throwable t) {
            LOGGER.error("An uncaught exception occured while displaying the init error screen, making normal report instead", t);
            displayCrashReport(report);
            System.exit(report.getFile() != null ? -1 : -2);
        }
    }

    private void runGUILoop(GuiScreen screen) throws IOException {
        displayGuiScreen(screen);
        while (running && currentScreen != null && !(currentScreen instanceof GuiMainMenu)) {
            if (Display.isCreated() && Display.isCloseRequested()) running = false;
            leftClickCounter = 10000;
            currentScreen.handleInput();
            currentScreen.updateScreen();

            GlStateManager.pushMatrix();
            GlStateManager.clear(16640);
            framebufferMc.bindFramebuffer(true);
            GlStateManager.enableTexture2D();

            GlStateManager.viewport(0, 0, displayWidth, displayHeight);

            // EntityRenderer.setupOverlayRendering
            ScaledResolution scaledResolution = new ScaledResolution((Minecraft) (Object) this);
            GlStateManager.clear(256);
            GlStateManager.matrixMode(5889);
            GlStateManager.loadIdentity();
            GlStateManager.ortho(0.0D, scaledResolution.getScaledWidth_double(), scaledResolution.getScaledHeight_double(), 0, 1000, 3000);
            GlStateManager.matrixMode(5888);
            GlStateManager.loadIdentity();
            GlStateManager.translate(0, 0, -2000);
            GlStateManager.clear(256);

            int width = scaledResolution.getScaledWidth();
            int height = scaledResolution.getScaledHeight();
            int mouseX = Mouse.getX() * width / displayWidth;
            int mouseY = height - Mouse.getY() * height / displayHeight - 1;
            currentScreen.drawScreen(mouseX, mouseY, 0);

            framebufferMc.unbindFramebuffer();
            GlStateManager.popMatrix();

            GlStateManager.pushMatrix();
            framebufferMc.framebufferRender(displayWidth, displayHeight);
            GlStateManager.popMatrix();

            updateDisplay();
            Thread.yield();
            Display.sync(60);
            checkGLError("VanillaFix GUI Loop");
        }
    }

    public void displayCrashScreen(CrashReport report) { // TODO: Shouldn't the GL state be reset here and on displayInitErrorScreen?
        try {
            CrashUtils.outputReport(report);

            // Reset hasCrashed, debugCrashKeyPressTime, and crashIntegratedServerNextTick
            hasCrashed = false;
            debugCrashKeyPressTime = -1;
            crashIntegratedServerNextTick = false;

            // Vanilla does this when switching to main menu but not our custom crash screen
            // nor the out of memory screen (see https://bugs.mojang.com/browse/MC-128953)
            gameSettings.showDebugInfo = false;
            ingameGUI.getChatGUI().clearChatMessages(true);

            // Display the crash screen
            runGUILoop(new GuiCrashScreen(report));
        } catch (Throwable t) {
            // The crash screen has crashed. Report it normally instead.
            LOGGER.error("An uncaught exception occured while displaying the crash screen, making normal report instead", t);
            displayCrashReport(report);
            System.exit(report.getFile() != null ? -1 : -2);
        }
    }

    @Overwrite
    public void displayCrashReport(CrashReport report) {
        CrashUtils.outputReport(report);
    }

    /**
     * @reason Disconnect from the current world and free memory, using a memory reserve
     * to make sure that an OutOfMemory doesn't happen while doing this.
     * <p>
     * Bugs Fixed:
     * - https://bugs.mojang.com/browse/MC-128953
     * - Memory reserve not recreated after out-of memory
     */
    @Overwrite
    @SuppressWarnings("CallToSystemGC")
    public void freeMemory() {
        int originalMemoryReserveSize = -1;
        try {
            // Separate try in case another mod actually deletes the memoryReserve field
            if (memoryReserve != null) {
                originalMemoryReserveSize = memoryReserve.length;
                memoryReserve = new byte[0];
            }
        } catch (Throwable ignored) {}

        try {
            renderGlobal.deleteAllDisplayLists();
        } catch (Throwable ignored) {}

        try {
            System.gc();

            // Fix: Close the connection to avoid receiving packets from old server
            // when playing in another world (MC-128953)
            if (getConnection() != null) {
                getConnection().getNetworkManager().closeChannel(new TextComponentString("[VanillaFix] Client crashed"));
            }

            loadWorld(null);

            // TODO: Figure out why this isn't necessary for vanilla disconnect:
            scheduledTasks.clear();

            // Fix: Probably not necessary, but do this now rathe than next tick to
            // make it identical to a regular disconnect:
            if (entityRenderer.isShaderActive()) {
                entityRenderer.stopUseShader();
            }
        } catch (Throwable ignored) {}

        System.gc();

        // Fix: Re-create memory reserve so that future crashes work well too
        if (originalMemoryReserveSize != -1) {
            try {
                memoryReserve = new byte[originalMemoryReserveSize];
            } catch (Throwable ignored) {}
        }
    }

    /**
     * @reason Replaces the vanilla F3 + C logic to immediately crash rather than requiring
     * that the buttons are pressed for 6 seconds and add more crash types:
     * F3 + C - Client crash
     * Alt + F3 + C - Integrated server crash
     * Shift + F3 + C - Scheduled client task exception
     * Alt + Shift + F3 + C - Scheduled server task exception
     * <p>
     * Note: Left Shift + F3 + C doesn't work on most keyboards, see http://keyboardchecker.com/
     * Use the right shift instead.
     * <p>
     * TODO: Make this work outside the game too (for example on the main menu).
     */
    @Redirect(method = "runTickKeyboard", at = @At(value = "FIELD", target = "Lnet/minecraft/client/Minecraft;debugCrashKeyPressTime:J", ordinal = 0))
    private long checkForF3C(Minecraft mc) {
        // Fix: Check if keys are down before checking time pressed
        if (Keyboard.isKeyDown(Keyboard.KEY_F3) && Keyboard.isKeyDown(Keyboard.KEY_C)) {
            debugCrashKeyPressTime = getSystemTime();
            actionKeyF3 = true;
        } else {
            debugCrashKeyPressTime = -1L;
        }

        if (debugCrashKeyPressTime > 0L) {
            if (getSystemTime() - debugCrashKeyPressTime >= 0) {
                if (GuiScreen.isShiftKeyDown()) {
                    if (GuiScreen.isAltKeyDown()) {
                        if (integratedServerIsRunning) integratedServer.addScheduledTask(() -> {
                            throw new ReportedException(new CrashReport("Manually triggered server-side scheduled task exception", new Throwable()));
                        });
                    } else {
                        scheduledTasks.add(ListenableFutureTask.create(() -> {
                            throw new ReportedException(new CrashReport("Manually triggered client-side scheduled task exception", new Throwable()));
                        }));
                    }
                } else {
                    if (GuiScreen.isAltKeyDown()) {
                        if (integratedServerIsRunning) crashIntegratedServerNextTick = true;
                    } else {
                        throw new ReportedException(new CrashReport("Manually triggered client-side debug crash", new Throwable()));
                    }
                }
            }
        }
        return -1;
    }

    /** @reason Disables the vanilla F3 + C logic. */
    @Redirect(method = "runTickKeyboard", at = @At(value = "INVOKE", target = "Lorg/lwjgl/input/Keyboard;isKeyDown(I)Z", ordinal = 0))
    private boolean isKeyDownF3(int key) {
        return false;
    }

    @Override
    public boolean shouldCrashIntegratedServerNextTick() {
        return crashIntegratedServerNextTick;
    }

    @Override
    public void makeErrorNotification(CrashReport report) {
        if (ModConfig.crashes.replaceErrorNotifications) {
            ProblemToast lastToast = getToastGui().getToast(ProblemToast.class, IToast.NO_TOKEN);
            if (lastToast != null) lastToast.hide = true;
        }

        getToastGui().add(new ProblemToast(report));
    }

    /**
     * @reason Checks if Ctrl + I is pressed and opens a warning screen if there is a visible or
     * queued error notification. TODO: Main menu too
     */
    @Inject(method = "runTickKeyboard", at = @At("HEAD"))
    private void checkForCtrlI(CallbackInfo ci) {
        if (GuiScreen.isCtrlKeyDown() && !GuiScreen.isShiftKeyDown() && !GuiScreen.isAltKeyDown() && Keyboard.isKeyDown(Keyboard.KEY_I)) {
            ProblemToast lastToast = getToastGui().getToast(ProblemToast.class, IToast.NO_TOKEN);
            if (lastToast != null) {
                lastToast.hide = true;
                displayGuiScreen(new GuiWarningScreen(lastToast.report, Minecraft.getMinecraft().currentScreen));
            }
        }
    }
}
