package me.cortex.voxy.client;

import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import me.cortex.voxy.client.core.IVoxyRenderSystemHolder;
import me.cortex.voxy.common.DebugUtils;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.commonImpl.VoxyCommon;
import me.cortex.voxy.commonImpl.WorldIdentifier;
import me.cortex.voxy.commonImpl.importers.DHImporter;
import me.cortex.voxy.commonImpl.importers.ImportOptions;
import me.cortex.voxy.commonImpl.importers.ImportProgress;
import me.cortex.voxy.commonImpl.importers.WorldImporter;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.storage.LevelResource;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;


public class VoxyCommands {

    public static LiteralArgumentBuilder<FabricClientCommandSource> register() {
        //The optional radius (in blocks around the player) limits the import to the chunks within it
        var imports = ClientCommands.literal("import")
                .then(ClientCommands.literal("world")
                        .then(ClientCommands.argument("world_name", StringArgumentType.string())
                                .suggests(VoxyCommands::importWorldSuggester)
                                .executes(ctx->importWorld(ctx, 0))
                                .then(ClientCommands.argument("radius", IntegerArgumentType.integer(1))
                                        .executes(ctx->importWorld(ctx, IntegerArgumentType.getInteger(ctx, "radius"))))))
                .then(ClientCommands.literal("bobby")
                        .then(ClientCommands.argument("world_name", StringArgumentType.string())
                                .suggests(VoxyCommands::importBobbySuggester)
                                .executes(ctx->importBobby(ctx, 0))
                                .then(ClientCommands.argument("radius", IntegerArgumentType.integer(1))
                                        .executes(ctx->importBobby(ctx, IntegerArgumentType.getInteger(ctx, "radius"))))))
                .then(ClientCommands.literal("raw")
                        .then(ClientCommands.argument("path", StringArgumentType.string())
                                .executes(ctx->importRaw(ctx, 0))
                                .then(ClientCommands.argument("radius", IntegerArgumentType.integer(1))
                                        .executes(ctx->importRaw(ctx, IntegerArgumentType.getInteger(ctx, "radius"))))))
                .then(ClientCommands.literal("zip")
                        .then(ClientCommands.argument("zipPath", StringArgumentType.string())
                                .executes(VoxyCommands::importZip)
                                .then(ClientCommands.argument("innerPath", StringArgumentType.string())
                                        .executes(VoxyCommands::importZip))))
                .then(ClientCommands.literal("current")
                        .executes(ctx->importCurrentWorldIn(ctx, 0))
                        .then(ClientCommands.argument("radius", IntegerArgumentType.integer(1))
                                .executes(ctx->importCurrentWorldIn(ctx, IntegerArgumentType.getInteger(ctx, "radius")))))
                .then(ClientCommands.literal("reset_progress")
                        .executes(VoxyCommands::resetImportProgress))
                .then(ClientCommands.literal("cancel")
                        .executes(VoxyCommands::cancelImport));

        if (DHImporter.HasRequiredLibraries) {
            imports = imports
                    .then(ClientCommands.literal("distant_horizons")
                    .then(ClientCommands.argument("sqlDbPath", StringArgumentType.string())
                            .executes(VoxyCommands::importDistantHorizons)));
        }

        var debug = ClientCommands.literal("debug")
                .then(ClientCommands.literal("verifyTLNChildMask")
                        .executes(ctx->verifyTLNs(ctx, false))
                        .then(ClientCommands.argument("attemptRepair", BoolArgumentType.bool())
                                .executes(ctx->verifyTLNs(ctx, BoolArgumentType.getBool(ctx, "attemptRepair"))))
                );

        return ClientCommands.literal("voxy")//.requires((ctx)-> VoxyCommon.getInstance() != null)
                .then(ClientCommands.literal("reload")
                        .executes(VoxyCommands::reloadInstance))
                .then(imports)
                .then(debug);
    }

    private static int reloadInstance(CommandContext<FabricClientCommandSource> ctx) {
        var instance = (VoxyClientInstance)VoxyCommon.getInstance();
        if (instance == null) {
            ctx.getSource().sendError(Component.translatable("Voxy must be enabled in settings to use this"));
            return 1;
        }

        var vrsh = IVoxyRenderSystemHolder.getNullableHolder();
        if (vrsh!=null) {
            vrsh.voxy$shutdownRenderer();
        }

        VoxyCommon.shutdownInstance();
        System.gc();
        VoxyCommon.createInstance();

        var r = Minecraft.getInstance().levelExtractor;
        if (r != null) r.allChanged();
        return 0;
    }

    private static int verifyTLNs(CommandContext<FabricClientCommandSource> ctx, boolean attemptRepair) {
        var instance = VoxyCommon.getInstance();
        if (instance == null) {
            ctx.getSource().sendError(Component.translatable("Voxy must be enabled in settings to use this"));
            return 1;
        }
        if (Minecraft.getInstance().level == null) {
            throw new IllegalStateException("How you even do this");
        }
        var engine = WorldIdentifier.ofEngine(Minecraft.getInstance().level);
        if (engine!=null) {
            DebugUtils.verifyAllTopLevelNodes(engine, attemptRepair);
            return 0;
        }
        return 1;
    }


    private static int importDistantHorizons(CommandContext<FabricClientCommandSource> ctx) {
        var instance = (VoxyClientInstance)VoxyCommon.getInstance();
        if (instance == null) {
            ctx.getSource().sendError(Component.translatable("Voxy must be enabled in settings to use this"));
            return 1;
        }
        var dbFile = new File(ctx.getArgument("sqlDbPath", String.class));
        if (!dbFile.exists()) {
            return 1;
        }
        if (dbFile.isDirectory()) {
            dbFile = dbFile.toPath().resolve("DistantHorizons.sqlite").toFile();
            if (!dbFile.exists()) {
                return 1;
            }
        }

        File dbFile_ = dbFile;
        var engine = WorldIdentifier.ofEngine(Minecraft.getInstance().level);
        if (engine==null)return 1;
        return instance.getImportManager().makeAndRunIfNone(engine, ()->
                new DHImporter(dbFile_, engine, Minecraft.getInstance().level, instance.getServiceManager(), instance.savingServiceRateLimiter))?0:1;
    }

    private static boolean fileBasedImporter(File directory, int radius) {
        var instance = (VoxyClientInstance)VoxyCommon.getInstance();
        if (instance == null) {
            return false;
        }

        var engine = WorldIdentifier.ofEngine(Minecraft.getInstance().level);
        if (engine==null) return false;
        return instance.getImportManager().makeAndRunIfNone(engine, ()->{
            var importer = new WorldImporter(engine, Minecraft.getInstance().level, instance.getServiceManager(), instance.savingServiceRateLimiter, instance::getPendingSaveCount);
            importer.importRegionDirectoryAsync(directory, createImportOptions(radius, openImportProgress(instance, directory)));
            return importer;
        });
    }

    //Imports around the player (closest regions first), optionally limited to a radius in blocks
    private static ImportOptions createImportOptions(int radius, ImportProgress progress) {
        var player = Minecraft.getInstance().player;
        if (player == null) {
            return new ImportOptions(false, 0, 0, 0, progress);
        }
        return new ImportOptions(true, player.getX(), player.getZ(), radius, progress);
    }

    private static Path getImportProgressDirectory(VoxyClientInstance instance) {
        var identifier = WorldIdentifier.of(Minecraft.getInstance().level);
        if (identifier == null) {
            return null;
        }
        return instance.getStorageBasePath().resolve(identifier.getWorldId()).resolve("import_progress");
    }

    //Opens the progress of importing the region directory into the current world, so the import can be resumed
    private static ImportProgress openImportProgress(VoxyClientInstance instance, File regionDirectory) {
        try {
            var directory = getImportProgressDirectory(instance);
            if (directory == null) {
                return null;
            }
            String source = regionDirectory.getCanonicalPath();
            //The storage identity changes if the voxy storage of the world is deleted, the progress is then discarded
            var identityFile = directory.getParent().resolve("storage").resolve("IDENTITY");
            String identity = Files.isRegularFile(identityFile) ? Files.readString(identityFile).trim() : "unknown";
            var progress = ImportProgress.open(directory.resolve(ImportProgress.fileNameFor(source)), source, identity);
            if (progress.getCompletedCount() != 0) {
                Logger.info("Resuming voxy import of " + source + ", " + progress.getCompletedCount() + " regions were already imported");
            }
            return progress;
        } catch (Exception e) {
            Logger.error("Could not open the voxy import progress, the import will not be resumable", e);
            return null;
        }
    }

    private static int resetImportProgress(CommandContext<FabricClientCommandSource> ctx) {
        var instance = (VoxyClientInstance)VoxyCommon.getInstance();
        if (instance == null) {
            ctx.getSource().sendError(Component.translatable("Voxy must be enabled in settings to use this"));
            return 1;
        }
        var engine = WorldIdentifier.ofEngineNullable(Minecraft.getInstance().level);
        if (engine != null && instance.getImportManager().isImportRunning(engine)) {
            ctx.getSource().sendError(Component.literal("An import is running in this world, cancel it first with /voxy import cancel"));
            return 1;
        }
        var directory = getImportProgressDirectory(instance);
        if (directory == null) {
            return 1;
        }
        try {
            int removed = ImportProgress.reset(directory);
            ctx.getSource().sendFeedback(Component.literal("Cleared the voxy import progress of this world (" + removed + " file(s)), the next import will process every region again"));
            return 0;
        } catch (IOException e) {
            Logger.error("Failed to reset the voxy import progress", e);
            ctx.getSource().sendError(Component.literal("Failed to reset the import progress: " + e.getMessage()));
            return 1;
        }
    }

    private static int importRaw(CommandContext<FabricClientCommandSource> ctx, int radius) {
        if (VoxyCommon.getInstance() == null) {
            ctx.getSource().sendError(Component.translatable("Voxy must be enabled in settings to use this"));
            return 1;
        }

        return fileBasedImporter(new File(ctx.getArgument("path", String.class)), radius)?0:1;
    }

    private static int importBobby(CommandContext<FabricClientCommandSource> ctx, int radius) {
        if (VoxyCommon.getInstance() == null) {
            ctx.getSource().sendError(Component.translatable("Voxy must be enabled in settings to use this"));
            return 1;
        }

        var file = new File(".bobby").toPath().resolve(ctx.getArgument("world_name", String.class)).toFile();
        return fileBasedImporter(file, radius)?0:1;
    }

    private static CompletableFuture<Suggestions> importWorldSuggester(CommandContext<FabricClientCommandSource> ctx, SuggestionsBuilder sb) {
        return fileDirectorySuggester(Minecraft.getInstance().gameDirectory.toPath().resolve("saves"), sb);
    }
    private static CompletableFuture<Suggestions> importBobbySuggester(CommandContext<FabricClientCommandSource> ctx, SuggestionsBuilder sb) {
        return fileDirectorySuggester(Minecraft.getInstance().gameDirectory.toPath().resolve(".bobby"), sb);
    }

    private static CompletableFuture<Suggestions> fileDirectorySuggester(Path dir, SuggestionsBuilder sb) {
        var str = sb.getRemaining().replace("\\\\", "\\").replace("\\", "/");
        if (str.startsWith("\"")) {
            str = str.substring(1);
        }
        if (str.endsWith("\"")) {
            str = str.substring(0,str.length()-1);
        }
        var remaining = str;
        if (str.contains("/")) {
            int idx = str.lastIndexOf('/');
            remaining = str.substring(idx+1);
            try {
                dir = dir.resolve(str.substring(0, idx));
            } catch (Exception e) {
                return Suggestions.empty();
            }
            str = str.substring(0, idx+1);
        } else {
            str = "";
        }

        try {
            var worlds = Files.list(dir).toList();
            for (var world : worlds) {
                if (!world.toFile().isDirectory()) {
                    continue;
                }
                var wn = world.getFileName().toString();
                if (wn.equals(remaining)) {
                    continue;
                }
                if (SharedSuggestionProvider.matchesSubStr(remaining, wn) || SharedSuggestionProvider.matchesSubStr(remaining, '"'+wn)) {
                    wn = str+wn + "/";
                    sb.suggest(StringArgumentType.escapeIfRequired(wn));
                }
            }
        } catch (IOException e) {}

        return sb.buildFuture();
    }


    private static int importCurrentWorldIn(CommandContext<FabricClientCommandSource> ctx, int radius) {
        if (VoxyCommon.getInstance() == null) {
            ctx.getSource().sendError(Component.translatable("Voxy must be enabled in settings to use this"));
            return 1;
        }

        var localServer = Minecraft.getInstance().getSingleplayerServer();
        if (localServer == null) {
            ctx.getSource().sendError(Component.translatable("You must be in single player to use this command"));
            return 1;
        }
        var regionPath = DimensionType.getStorageFolder(Minecraft.getInstance().level.dimension(), localServer.getWorldPath(LevelResource.ROOT)).resolve("region");
        if ((!regionPath.toFile().exists())||!regionPath.toFile().isDirectory()) {
            ctx.getSource().sendError(Component.translatable("Cannot find region folder for current dimension"));
            return 1;
        }
        return fileBasedImporter(regionPath.toFile(), radius)?0:1;
    }

    private static int importWorld(CommandContext<FabricClientCommandSource> ctx, int radius) {
        if (VoxyCommon.getInstance() == null) {
            ctx.getSource().sendError(Component.translatable("Voxy must be enabled in settings to use this"));
            return 1;
        }

        var name = ctx.getArgument("world_name", String.class);
        var file = new File("saves").toPath().resolve(name);
        name = name.toLowerCase(Locale.ROOT);
        if (name.endsWith("/")) {
            name = name.substring(0, name.length()-1);
        }
        if (file.resolve("level.dat").toFile().exists()) {
            var dimFile = DimensionType.getStorageFolder(Minecraft.getInstance().level.dimension(), file)
                    .resolve("region")
                    .toFile();
            if (!dimFile.isDirectory()) return 1;
            return fileBasedImporter(dimFile, radius)?0:1;
            //We are in a world directory, so import the current dimension we are in
            /*
            for (var dim : new String[]{"overworld", "the_nether", "the_end"}) {//This is so annoying that you cant loop through all the dimensions
                var id = ResourceKey.create(Registries.DIMENSION, Identifier.withDefaultNamespace(dim));
                var dimPath = DimensionType.getStorageFolder(id, file);
                dimPath = dimPath.resolve("region");
                var dimFile = dimPath.toFile();
                if (dimFile.isDirectory()) {//exists and is a directory
                    if (!fileBasedImporter(dimFile)) {
                        Logger.error("Failed to import dimension: " + id);
                    }
                }
            }*/
        } else {
            if (!(name.endsWith("region"))) {
                file = file.resolve("region");
            }
            return fileBasedImporter(file.toFile(), radius) ? 0 : 1;
        }
    }

    private static int importZip(CommandContext<FabricClientCommandSource> ctx) {
        var zip =  new File(ctx.getArgument("zipPath", String.class));
        var innerDir = "region/";
        try {
            innerDir = ctx.getArgument("innerPath", String.class);
        } catch (Exception e) {}

        var instance = (VoxyClientInstance)VoxyCommon.getInstance();
        if (instance == null) {
            ctx.getSource().sendError(Component.translatable("Voxy must be enabled in settings to use this"));
            return 1;
        }
        String finalInnerDir = innerDir;

        var engine = WorldIdentifier.ofEngine(Minecraft.getInstance().level);
        if (engine != null) {
            return instance.getImportManager().makeAndRunIfNone(engine, () -> {
                var importer = new WorldImporter(engine, Minecraft.getInstance().level, instance.getServiceManager(), instance.savingServiceRateLimiter, instance::getPendingSaveCount);
                importer.importZippedRegionDirectoryAsync(zip, finalInnerDir, createImportOptions(0, null));
                return importer;
            }) ? 0 : 1;
        }
        return 1;
    }

    private static int cancelImport(CommandContext<FabricClientCommandSource> ctx) {
        var instance = (VoxyClientInstance)VoxyCommon.getInstance();
        if (instance == null) {
            ctx.getSource().sendError(Component.translatable("Voxy must be enabled in settings to use this"));
            return 1;
        }
        var world = WorldIdentifier.ofEngineNullable(Minecraft.getInstance().level);
        if (world != null) {
            return instance.getImportManager().cancelImport(world)?0:1;
        }
        return 1;
    }
}