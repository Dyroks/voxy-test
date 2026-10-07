package me.cortex.voxy.client;

import me.cortex.voxy.common.Logger;
import me.cortex.voxy.commonImpl.ImportManager;
import me.cortex.voxy.commonImpl.importers.IDataImporter;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.LerpingBossEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.BossEvent;

import java.util.Locale;
import java.util.UUID;

public class ClientImportManager extends ImportManager {
    protected class ClientImportTask extends ImportTask {
        private final UUID bossbarUUID;
        private final LerpingBossEvent bossBar;
        protected ClientImportTask(IDataImporter importer) {
            super(importer);

            this.bossbarUUID = UUID.randomUUID();
            this.bossBar = new LerpingBossEvent(this.bossbarUUID, Component.nullToEmpty("Voxy world importer"), 0.0f, BossEvent.BossBarColor.GREEN, BossEvent.BossBarOverlay.PROGRESS, false, false, false);
            Minecraft.getInstance().execute(()->{
                Minecraft.getInstance().gui.hud.getBossOverlay().events.put(bossBar.getId(), bossBar);
            });
        }

        @Override
        protected boolean onUpdate(int completed, int outOf) {
            if (!super.onUpdate(completed, outOf)) {
                return false;
            }
            String text = "Voxy import: " + completed + "/" + outOf + " chunks";
            long elapsed = System.currentTimeMillis() - this.startTime;
            if (completed > 0 && elapsed > 0) {
                double rate = completed * 1000.0 / elapsed;
                text += " (" + (long) rate + "/s";
                if (outOf > completed) {
                    text += ", ETA " + formatDuration((long) ((outOf - completed) / rate));
                }
                text += ")";
            }
            String name = text;
            Minecraft.getInstance().execute(()->{
                this.bossBar.setProgress((float) (((double)completed) / ((double) Math.max(1, outOf))));
                this.bossBar.setName(Component.nullToEmpty(name));
            });
            return true;
        }

        @Override
        protected void onCompleted(int total) {
            super.onCompleted(total);
            Minecraft.getInstance().execute(()->{
                Minecraft.getInstance().gui.hud.getBossOverlay().events.remove(this.bossbarUUID);
                long delta = Math.max(System.currentTimeMillis() - this.startTime, 1);

                String msg = "Voxy world import finished in " + (delta/1000) + " seconds, averaging " + (int)(total/(delta/1000f)) + " chunks per second";
                String details = this.importer.getCompletionDetails();
                if (details != null) {
                    msg += ", " + details;
                }
                Minecraft.getInstance().gui.hud.getChat().addClientSystemMessage(Component.literal(msg));
                Logger.info(msg);
            });
        }
    }

    @Override
    protected synchronized ImportTask createImportTask(IDataImporter importer) {
        return new ClientImportTask(importer);
    }

    private static String formatDuration(long seconds) {
        if (seconds >= 3600) {
            return (seconds/3600) + "h" + String.format(Locale.ROOT, "%02d", (seconds%3600)/60) + "m";
        }
        if (seconds >= 60) {
            return (seconds/60) + "m" + String.format(Locale.ROOT, "%02d", seconds%60) + "s";
        }
        return seconds + "s";
    }
}
