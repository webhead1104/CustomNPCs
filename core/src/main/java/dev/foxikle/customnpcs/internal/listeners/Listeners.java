/*
 * Copyright (c) 2024. Foxikle
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package dev.foxikle.customnpcs.internal.listeners;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.foxikle.customnpcs.actions.Action;
import dev.foxikle.customnpcs.actions.conditions.Condition;
import dev.foxikle.customnpcs.actions.defaultImpl.*;
import dev.foxikle.customnpcs.api.events.NpcInteractEvent;
import dev.foxikle.customnpcs.internal.CustomNPCs;
import dev.foxikle.customnpcs.internal.LookAtAnchor;
import dev.foxikle.customnpcs.internal.interfaces.InternalNpc;
import dev.foxikle.customnpcs.internal.menu.HologramMenu;
import dev.foxikle.customnpcs.internal.menu.MenuUtils;
import dev.foxikle.customnpcs.internal.menu.PoseEditorMenu;
import dev.foxikle.customnpcs.internal.utils.Msg;
import dev.foxikle.customnpcs.internal.utils.SkinUtils;
import dev.foxikle.customnpcs.internal.utils.WaitingType;
import io.github.mqzen.menus.base.MenuView;
import io.papermc.paper.event.world.WorldGameRuleChangeEvent;
import lombok.Getter;
import lombok.Setter;
import org.bukkit.*;
import org.bukkit.block.BlockFace;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.*;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.util.Vector;
import org.mineskin.data.CodeAndMessage;
import org.mineskin.data.Visibility;
import org.mineskin.exception.MineSkinRequestException;
import org.mineskin.request.GenerateRequest;
import org.mineskin.response.MineSkinResponse;

import java.io.InputStreamReader;
import java.net.URL;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.Level;
import java.util.regex.Pattern;

/**
 * The class that deals with misc listeners
 */
@SuppressWarnings("unused")
public class Listeners implements Listener {

    /**
     * Player Movement Data that keeps track of old movements to replace PlayerMoveEvent
     *
     * @since 1.6.0
     */
    private static final ConcurrentMap<UUID, MovementData> playerMovementData = new ConcurrentHashMap<>();
    // Helper Constants
    // since 1.6.0
    private static final int FIVE_BLOCKS = 25;
    private static final int FIFTY_BLOCKS = 2500; // 50 * 50
    private static final int SIXTY_BLOCKS = 3600; // 60 * 60
    private static final int FORTY_BLOCKS = 2304; // 48 * 48
    private static final double HALF_BLOCK = 0.25;
    // Writing Constants
    // since 1.6.0
    private static final BukkitScheduler SCHEDULER = Bukkit.getScheduler();
    private static final ConsoleCommandSender CONSOLE_SENDER = Bukkit.getConsoleSender();
    private static final Pattern PATTERN = Pattern.compile(" ");
    private final Map<UUID, Integer> worldSleepingPercentages = new ConcurrentHashMap<>();
    /**
     * The instance of the main Class
     */
    private final CustomNPCs plugin;

    // Executors for better handling of async scheduling than that bukkit scheduler
    private ScheduledExecutorService service;

    /**
     * Constructor for generic listeners class
     *
     * @param plugin The instance of the main class
     */
    public Listeners(CustomNPCs plugin) {
        this.plugin = plugin;
    }

    public void start() {
        Bukkit.getWorlds().forEach(world -> worldSleepingPercentages.put(world.getUID(), world.getGameRuleValue(GameRule.PLAYERS_SLEEPING_PERCENTAGE)));
        service = Executors.newSingleThreadScheduledExecutor();
        service.scheduleAtFixedRate(() -> Bukkit.getOnlinePlayers().forEach(this::actionPlayerMovement), 1000, plugin.getConfig().getInt("LookInterval") * 50L, TimeUnit.MILLISECONDS);
    }


    public void stop() {
        service.shutdown();
        Bukkit.getWorlds().forEach(world -> world.setGameRule(GameRule.PLAYERS_SLEEPING_PERCENTAGE, worldSleepingPercentages.get(world.getUID())));
        CompletableFuture.runAsync(() -> {
            try {
                if (!service.awaitTermination(2, TimeUnit.SECONDS)) {
                    service.shutdownNow();
                }
            } catch (InterruptedException e) {
                service.shutdownNow();
                Thread.currentThread().interrupt();
            }
            plugin.getLogger().info("ScheduledExecutorService successfully shut down!");
        });
    }

    private void actionPlayerMovement(Player player) {
        if (player.getGameMode() == GameMode.SPECTATOR) return; // we don't care about spectators
        final Location location = player.getLocation();
        final World world = player.getWorld();

        final UUID uuid = player.getUniqueId();
        for (InternalNpc npc : plugin.npcs.values()) {
            if (npc.getTarget() != null) continue;

            World npcWorld = npc.getWorld();
            if (world != npcWorld) continue;
            //if (npc.getSettings().isTunnelvision()) continue;
            processPlayerMovement(player, npc, world, npcWorld, location, uuid);
        }
    }

    private void processPlayerMovement(final Player player, final InternalNpc npc, final World world, final World npcWorld, final Location location, final UUID uuid) {
        if (player.getGameMode() == GameMode.SPECTATOR) return; // we don't care about spectators
        final Location npcLocation = npc.getCurrentLocation();
        MovementData oldMovementData; // difference in order of initialization in if/else statement
        MovementData movementData = playerMovementData.get(uuid);
        final double distanceSquared = location.distanceSquared(npcLocation);
        if (movementData == null) {
            playerMovementData.put(uuid, new MovementData(uuid, location, distanceSquared));
            movementData = playerMovementData.get(uuid);
            oldMovementData = movementData;
        } else {
            oldMovementData = movementData;
            movementData.setLastLocation(location);
            movementData.setDistanceSquared(distanceSquared);
        }
        trackFromTo(player, npc, movementData, oldMovementData);
        if (npc.getSettings().isTunnelvision()) return;
        if (distanceSquared > FIVE_BLOCKS) {
            SCHEDULER.runTask(plugin, () -> {
                Collection<Entity> entities = npcWorld.getNearbyEntities(npc.getCurrentLocation(), 2.5, 2.5, 2.5);
                entities.removeIf(entity -> entity.getScoreboardTags().contains("NPC"));
                for (Entity en : entities) {
                    if (!(en instanceof Player p)) continue;
                    if (p.getGameMode() == GameMode.SPECTATOR) continue;
                    if (!npc.getSettings().isTunnelvision()) {
                        npc.lookAt(LookAtAnchor.HEAD, p);
                        return;
                    }
                }
                npc.setYRotation(npc.getSpawnLoc().getYaw());
                npc.setXRotation(npc.getSpawnLoc().getPitch());
            });
        }
    }

    private void trackFromTo(Player player, InternalNpc npc, MovementData data, MovementData oldData) {
        if (data.distanceSquared <= FIVE_BLOCKS && !npc.getSettings().isTunnelvision() && player.getGameMode() != GameMode.SPECTATOR) {
            npc.lookAt(LookAtAnchor.HEAD, player);
        }
    }

    /**
     * <p>The npc interaction handler
     * </p>
     *
     * @param e The event callback
     * @since 1.0
     */
    @EventHandler
    public void onPlayerInteract(PlayerInteractEntityEvent e) {
        Player player = e.getPlayer();

        if (e.getHand() != EquipmentSlot.HAND) return;
        if (e.getRightClicked().getType() != EntityType.PLAYER) return;
        Player rightClicked = (Player) e.getRightClicked();

        if (plugin.getNPCByID(rightClicked.getUniqueId()) == null) return;

        InternalNpc npc;
        UUID uuid = rightClicked.getUniqueId();

        try {
            npc = plugin.getNPCByID(uuid);
            assert npc != null;
        } catch (IllegalArgumentException ignored) {
            return;
        }

        if (player.hasPermission("customnpcs.edit") && player.isSneaking()) {
            player.performCommand("npc edit " + uuid);
        } else {
            if (npc.getSettings().isInteractable()) {
                NpcInteractEvent event = new NpcInteractEvent(player, npc);
                Bukkit.getServer().getPluginManager().callEvent(event);
                if (event.isCancelled()) return;
                npc.getActions().forEach(action -> SCHEDULER.runTaskLater(plugin, () ->
                        action.perform(npc, null, player), action.getDelay()));
            }
        }
    }

    /**
     * The handler for text input
     *
     * @param e The event callback
     * @since 1.0
     */
    @EventHandler(priority = EventPriority.LOWEST)
    @SuppressWarnings("deprecation")
    public void onChat(AsyncPlayerChatEvent e) {
        Player player = e.getPlayer();
        String message = e.getMessage();
        boolean cancel = message.equalsIgnoreCase("quit") || message.equalsIgnoreCase("exit") || message.equalsIgnoreCase("stop") || message.equalsIgnoreCase("cancel");
        if (plugin.isWaiting(player, WaitingType.COMMAND)) {
            Action actionImpl = plugin.editingActions.get(player.getUniqueId());
            if (!(actionImpl instanceof RunCommand runCommand)) {
                plugin.getLogger().warning("Expected action to be an instance of 'RunCommand', got " + actionImpl.getClass().getSimpleName());
                return;
            }
            if (cancel) {
                plugin.waiting.remove(player.getUniqueId());
                SCHEDULER.runTask(plugin, () -> plugin.getLotus().openMenu(player, actionImpl.getMenu()));
                e.setCancelled(true);
                return;
            }
            plugin.waiting.remove(player.getUniqueId());
            runCommand.setCommand(message);

            player.sendMessage(Msg.translate(player.locale(), "customnpcs.actionImpls.set.command", message));

            SCHEDULER.runTask(plugin, () -> plugin.getLotus().openMenu(player, actionImpl.getMenu()));
        } else if (plugin.isWaiting(player, WaitingType.NAME)) {
            InternalNpc npc = plugin.getEditingNPCs().getIfPresent(player.getUniqueId());
            if (npc == null) {
                player.sendMessage(Msg.translate(player.locale(), "customnpcs.error.npc-menu-expired"));
                return;
            }

            if (cancel) {
                plugin.waiting.remove(player.getUniqueId());
                SCHEDULER.runTask(plugin, () -> plugin.getLotus().openMenu(player, MenuUtils.NPC_HOLOGRAMS));
                e.setCancelled(true);
                return;
            }

            plugin.waiting.remove(player.getUniqueId());
            int index = HologramMenu.editingIndicies.get(player.getUniqueId());
            if (npc.getSettings().getRawHolograms().length <= index) { // an addition
                npc.getSettings().setRawHolograms(Arrays.copyOf(npc.getSettings().getRawHolograms(), index + 1)); // extend it by 1
            }
            npc.getSettings().getRawHolograms()[index] = message;
            player.sendMessage(Msg.translate(player.locale(), "customnpcs.set.name", index + 1, Msg.format(message)));
            SCHEDULER.runTask(plugin, () -> plugin.getLotus().openMenu(player, MenuUtils.NPC_HOLOGRAMS));
        } else if (plugin.isWaiting(player, WaitingType.TARGET)) {
            Condition conditional = plugin.editingConditionals.get(player.getUniqueId());
            if (cancel) {
                plugin.waiting.remove(player.getUniqueId());
                SCHEDULER.runTask(plugin, () -> plugin.getLotus().openMenu(player, MenuUtils.NPC_CONDITION_CUSTOMIZER));
                e.setCancelled(true);
                return;
            }
            if (conditional.getType() == Condition.Type.NUMERIC) {
                try {
                    Double.parseDouble(message);
                } catch (NumberFormatException ignored) {
                    player.sendMessage(Msg.translate(player.locale(), "customnpcs.error.parse_number", message));
                    return;
                }
            }
            plugin.waiting.remove(player.getUniqueId());
            conditional.setTargetValue(message);
            plugin.editingConditionals.put(player.getUniqueId(), conditional);
            player.sendMessage(Msg.translate(player.locale(), "customnpcs.actionImpls.conditions.set.target", message));
            SCHEDULER.runTask(plugin, () -> plugin.getLotus().openMenu(player, MenuUtils.NPC_CONDITION_CUSTOMIZER));
        } else if (plugin.isWaiting(player, WaitingType.TITLE)) {
            Action actionImpl = plugin.editingActions.get(player.getUniqueId());
            if (!(actionImpl instanceof DisplayTitle setTitle)) {
                return;
            }

            if (cancel) {
                plugin.waiting.remove(player.getUniqueId());
                SCHEDULER.runTask(plugin, () -> plugin.getLotus().openMenu(player, actionImpl.getMenu()));
                e.setCancelled(true);
                return;
            }
            plugin.waiting.remove(player.getUniqueId());

            setTitle.setTitle(message);

            player.sendMessage(Msg.translate(player.locale(), "customnpcs.actionImpls.set.title", Msg.format(message)));
            SCHEDULER.runTask(plugin, () -> plugin.getLotus().openMenu(player, actionImpl.getMenu()));
        } else if (plugin.isWaiting(player, WaitingType.SUBTITLE)) {
            Action actionImpl = plugin.editingActions.get(player.getUniqueId());
            if (!(actionImpl instanceof DisplayTitle setTitle)) {
                return;
            }

            if (cancel) {
                plugin.waiting.remove(player.getUniqueId());
                SCHEDULER.runTask(plugin, () -> plugin.getLotus().openMenu(player, actionImpl.getMenu()));
                e.setCancelled(true);
                return;
            }
            plugin.waiting.remove(player.getUniqueId());

            setTitle.setSubTitle(message);

            player.sendMessage(Msg.translate(player.locale(), "customnpcs.actionImpls.set.subtitle", Msg.format(message)));
            SCHEDULER.runTask(plugin, () -> plugin.getLotus().openMenu(player, actionImpl.getMenu()));
        } else if (plugin.isWaiting(player, WaitingType.MESSAGE)) {
            Action actionImpl = plugin.editingActions.get(player.getUniqueId());
            if (!(actionImpl instanceof SendMessage sendMessage)) {
                return;
            }
            if (cancel) {
                plugin.waiting.remove(player.getUniqueId());
                SCHEDULER.runTask(plugin, () -> plugin.getLotus().openMenu(player, actionImpl.getMenu()));
                e.setCancelled(true);
                return;
            }
            plugin.waiting.remove(player.getUniqueId());
            sendMessage.setRawMessage(message);

            player.sendMessage(Msg.translate(player.locale(), "customnpcs.actionImpls.set.message", Msg.format(message)));
            SCHEDULER.runTask(plugin, () -> plugin.getLotus().openMenu(player, actionImpl.getMenu()));
        } else if (plugin.isWaiting(player, WaitingType.SERVER)) {
            Action actionImpl = plugin.editingActions.get(player.getUniqueId());
            if (!(actionImpl instanceof SendServer runServer)) {
                return;
            }
            if (cancel) {
                plugin.waiting.remove(player.getUniqueId());
                SCHEDULER.runTask(plugin, () -> plugin.getLotus().openMenu(player, actionImpl.getMenu()));
                e.setCancelled(true);
                return;
            }
            plugin.waiting.remove(player.getUniqueId());

            runServer.setServer(message);

            player.sendMessage(Msg.translate(player.locale(), "customnpcs.actionImpls.set.server", Msg.format(message)));
            SCHEDULER.runTask(plugin, () -> plugin.getLotus().openMenu(player, actionImpl.getMenu()));
        } else if (plugin.isWaiting(player, WaitingType.ACTIONBAR)) {
            Action actionImpl = plugin.editingActions.get(player.getUniqueId());
            if (!(actionImpl instanceof ActionBar actionBar)) {
                return;
            }
            if (cancel) {
                plugin.waiting.remove(player.getUniqueId());
                SCHEDULER.runTask(plugin, () -> plugin.getLotus().openMenu(player, actionImpl.getMenu()));
                e.setCancelled(true);
                return;
            }
            plugin.waiting.remove(player.getUniqueId());
            actionBar.setRawMessage(message);
            player.sendMessage(Msg.translate(player.locale(), "customnpcs.actionImpls.set.actionbar", Msg.format(message)));
            SCHEDULER.runTask(plugin, () -> plugin.getLotus().openMenu(player, actionImpl.getMenu()));
        } else if (plugin.isWaiting(player, WaitingType.PLAYER)) {
            if (cancel) {
                plugin.waiting.remove(player.getUniqueId());
                SCHEDULER.runTask(plugin, () -> plugin.getLotus().openMenu(player, MenuUtils.NPC_SKIN));
                e.setCancelled(true);
                return;
            }

            InternalNpc npc = plugin.getEditingNPCs().getIfPresent(player.getUniqueId());
            if (npc == null) {
                player.sendMessage(Msg.translate(player.locale(), "customnpcs.error.npc-menu-expired"));
                return;
            }

            // this runs on an async thread, so there isn't any need to do this async :)
            player.sendMessage(Msg.translate(player.locale(), "customnpcs.skins.fetching.player", message));
            String name = e.getMessage();
            try {
                URL url = new URL("https://api.mojang.com/users/profiles/minecraft/" + name);
                InputStreamReader reader = new InputStreamReader(url.openStream());
                String uuid = new JsonParser().parse(reader).getAsJsonObject().get("id").getAsString();
                reader.close();

                URL url2 = new URL("https://sessionserver.mojang.com/session/minecraft/profile/" + uuid + "?unsigned=false");
                reader = new InputStreamReader(url2.openStream());

                JsonObject property = new JsonParser().parse(reader).getAsJsonObject().get("properties").getAsJsonArray().get(0).getAsJsonObject();
                String value = property.get("value").getAsString();
                String signature = property.get("signature").getAsString();
                npc.getSettings().setSkinData(signature, value, Msg.translatedString(player.locale(), "customnpcs.skins.imported_by.player_name", Msg.format(name)));
            } catch (Exception ignored) {
                player.sendMessage(Msg.translate(player.locale(), "customnpcs.skins.errors.player_does_not_exist", name));
                e.setCancelled(true);
                return;
            }
            plugin.waiting.remove(player.getUniqueId());
            player.sendMessage(Msg.translate(player.locale(), "customnpcs.skins.success.player_name", name));
            SCHEDULER.runTask(plugin, () -> plugin.getLotus().openMenu(player, MenuUtils.NPC_SKIN));
        } else if (plugin.isWaiting(player, WaitingType.URL)) {
            if (cancel) {
                plugin.waiting.remove(player.getUniqueId());
                SCHEDULER.runTask(plugin, () -> plugin.getLotus().openMenu(player, MenuUtils.NPC_SKIN));
                e.setCancelled(true);
                return;
            }
            InternalNpc npc = plugin.getEditingNPCs().getIfPresent(player.getUniqueId());
            if (npc == null) {
                player.sendMessage(Msg.translate(player.locale(), "customnpcs.error.npc-menu-expired"));
                return;
            }
            e.setCancelled(true);
            player.sendMessage(Msg.translate(player.locale(), "customnpcs.skins.fetching.url"));
            try {
                URL url = new URL(message);

                GenerateRequest request = GenerateRequest.url(url)
                        .name("URL Generated Skin")
                        .visibility(Visibility.UNLISTED);
                SkinUtils.fetch(request).thenAccept(skin -> {
                            npc.getSettings().setSkinData(skin.texture().data().signature(), skin.texture().data().value(), Msg.translatedString(player.locale(), "customnpcs.skins.imported_by.url"));
                            plugin.waiting.remove(player.getUniqueId());
                            player.sendMessage(Msg.translate(player.locale(), "customnpcs.skins.success.url", message));
                            SCHEDULER.runTask(plugin, () -> plugin.getLotus().openMenu(player, MenuUtils.NPC_SKIN));
                        })
                        .exceptionally(throwable -> {

                            if (throwable instanceof CompletionException completionException) {
                                throwable = completionException.getCause();
                            }

                            if (throwable instanceof MineSkinRequestException requestException) {
                                // get error details
                                MineSkinResponse<?> response = requestException.getResponse();
                                Optional<CodeAndMessage> detailsOptional = response.getErrorOrMessage();
                                Throwable finalThrowable = throwable;
                                detailsOptional.ifPresent(details -> {
                                    plugin.getLogger().log(Level.SEVERE, details.code() + " : " + details, finalThrowable);
                                    System.out.println(details.code() + ": " + details.message());
                                });
                            }

                            if (throwable.getMessage().equalsIgnoreCase("java.lang.RuntimeException: org.mineskin.data.MineskinException: Failed to find image from url")) {
                                player.sendMessage(Msg.translate(player.locale(), "customnpcs.skins.errors.no_image_data"));
                                return null;
                            }
                            player.sendMessage(Msg.translate(player.locale(), "customnpcs.skins.errors.unknown_url_error"));
                            plugin.getLogger().log(Level.SEVERE, "An error occurred whilst parsing this skin from a url.", throwable);
                            return null;
                        });
            } catch (Exception ex) {
                player.sendMessage(Msg.translate(player.locale(), "customnpcs.skins.errors.invalid_url"));
            }
        } else if (plugin.isWaiting(player, WaitingType.HOLOGRAM)) {
            if (cancel) {
                plugin.waiting.remove(player.getUniqueId());
                SCHEDULER.runTask(plugin, () -> plugin.getLotus().openMenu(player, MenuUtils.NPC_EXTRA_SETTINGS));
                e.setCancelled(true);
                return;
            }
            InternalNpc npc = plugin.getEditingNPCs().getIfPresent(player.getUniqueId());
            if (npc == null) {
                player.sendMessage(Msg.translate(player.locale(), "customnpcs.error.npc-menu-expired"));
                return;
            }
            plugin.waiting.remove(player.getUniqueId());
            e.setCancelled(true);
            player.sendMessage(Msg.translate(player.locale(), "customnpcs.set.clickable_hologram", Msg.format(message)));
            npc.getSettings().setCustomInteractableHologram(message);
            SCHEDULER.runTask(plugin, () -> plugin.getLotus().openMenu(player, MenuUtils.NPC_EXTRA_SETTINGS));
        } else if (plugin.isWaiting(player, WaitingType.FACING)) {
            e.setCancelled(true);
            plugin.waiting.remove(player.getUniqueId());
            if (cancel) return;
            InternalNpc npc = plugin.getEditingNPCs().getIfPresent(player.getUniqueId());
            if (npc == null) {
                player.sendMessage(Msg.translate(player.locale(), "customnpcs.error.npc-menu-expired"));
                return;
            }
            if (message.equalsIgnoreCase("confirm")) {
                npc.getSettings().setDirection(player.getLocation().getYaw());
                npc.getSpawnLoc().setPitch(player.getLocation().getPitch());
                npc.getSpawnLoc().setYaw(player.getLocation().getYaw());
                player.sendMessage(Msg.translate(player.locale(), "customnpcs.set.facing_direction"));
                player.playSound(player, Sound.BLOCK_AMETHYST_BLOCK_BREAK, 1, 1);
                SCHEDULER.runTask(plugin, () -> plugin.getLotus().openMenu(player, MenuUtils.NPC_MAIN));
            }
        } else return;
        e.setCancelled(true);
    }

    /**
     * <p>The npc injection handler on join
     * </p>
     *
     * @param e The event callback
     * @since 1.3-pre5
     */
    @EventHandler
    public void onPlayerLogin(PlayerJoinEvent e) {
        Player player = e.getPlayer();

        if (plugin.update && plugin.getConfig().getBoolean("AlertOnUpdate") && player.hasPermission("customnpcs.alert")) {
            player.sendMessage(Msg.translate(player.locale(), "customnpcs.should_update"));
        }
        recalcSleepingPercentages();
        if (player.getGameMode() == GameMode.SPECTATOR) return;

        Location location = player.getLocation();
        World world = player.getWorld();

        for (InternalNpc npc : plugin.npcs.values()) {
            Location spawnLocation = npc.getSpawnLoc();
            if (world != npc.getWorld()) continue;

            double distanceSquared = location.distanceSquared(spawnLocation);
            if (distanceSquared <= FIVE_BLOCKS && !npc.getSettings().isTunnelvision()) {
                npc.lookAt(LookAtAnchor.HEAD, player);
            }
        }
    }

    /**
     * <p>The npc injection handler on velocity
     * </p>
     *
     * @param e The event callback
     * @since 1.3-pre4
     */
    @EventHandler
    public void onVelocity(PlayerVelocityEvent e) {
        actionPlayerMovement(e.getPlayer());
    }


    /**
     * <p>The npc injection handler
     * </p>
     *
     * @param e The event callback
     * @since 1.0
     */
    @EventHandler
    public void onTeleport(PlayerTeleportEvent e) {
        recalcSleepingPercentages();
        Player player = e.getPlayer();

        if (player.getGameMode() == GameMode.SPECTATOR) return;

        Location location = player.getLocation();
        World world = player.getWorld();
        for (InternalNpc npc : plugin.npcs.values()) {
            Location spawnLocation = npc.getSpawnLoc();
            if (world != npc.getWorld()) continue;

            double distanceSquared = location.distanceSquared(spawnLocation);
            if (distanceSquared <= FIVE_BLOCKS && !npc.getSettings().isTunnelvision()) {
                npc.lookAt(LookAtAnchor.HEAD, player);
            }
        }
    }

    /**
     * <p>The npc injection handler
     * </p>
     *
     * @param e The event callback
     * @since 1.0
     */
    @EventHandler
    public void onRespawn(PlayerRespawnEvent e) {
        Player player = e.getPlayer();
        if (player.getGameMode() == GameMode.SPECTATOR) return;
        Location location = player.getLocation();
        World world = player.getWorld();
        for (InternalNpc npc : plugin.npcs.values()) {
            Location spawnLocation = npc.getSpawnLoc();
            if (world != npc.getWorld()) return;

            double distanceSquared = location.distanceSquared(spawnLocation);
            if (distanceSquared <= FIFTY_BLOCKS) {
                SCHEDULER.runTaskLater(plugin, () -> npc.injectPlayer(player), 5);
            }

            if (distanceSquared <= FIVE_BLOCKS && !npc.getSettings().isTunnelvision()) {
                npc.lookAt(LookAtAnchor.HEAD, player);
            }
        }
    }


    /**
     * Logic for injecting NPCs on world changes
     *
     * @param e Event callback
     */
    @EventHandler
    public void onDimensionChange(PlayerChangedWorldEvent e) {
        Player player = e.getPlayer();

        if (player.getGameMode() == GameMode.SPECTATOR) return;

        Location location = player.getLocation();
        World world = player.getWorld();

        for (InternalNpc npc : plugin.npcs.values()) {
            Location spawnLocation = npc.getSpawnLoc();
            if (world != npc.getWorld()) continue;

            double distanceSquared = location.distanceSquared(spawnLocation);
            if (distanceSquared <= FIVE_BLOCKS && !npc.getSettings().isTunnelvision()) {
                npc.lookAt(LookAtAnchor.HEAD, player);
            }
        }
    }

    /**
     * <p>The npc leave message handler. Cancels the leave message.
     * </p>
     *
     * @param e The event callback
     * @since 1.0
     */
    @EventHandler
    public void onLeave(PlayerQuitEvent e) {
        plugin.waiting.remove(e.getPlayer().getUniqueId());
        Bukkit.getScheduler().runTaskLater(plugin, this::recalcSleepingPercentages, 1);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onClick(InventoryClickEvent e) {
        if (e.isCancelled()) {
            return;
        }

        Player clicker = (Player) e.getWhoClicked();
        MenuView<?> menu = plugin.getLotus().getMenuView(clicker.getUniqueId()).orElseGet(() -> {
            if (e.getClickedInventory() == null) return null;
            if (e.getClickedInventory().getHolder() instanceof MenuView<?> playerMenu) {
                return playerMenu;
            }
            return null;
        });

        if (menu != null) {
            if (e.getClick() == ClickType.DOUBLE_CLICK) e.setCancelled(true);
        }
    }

    private void recalcSleepingPercentages() {
        Bukkit.getWorlds().forEach(world -> {
            if (world == null) return;
            int target = worldSleepingPercentages.get(world.getUID());
            int npcCount = plugin.getNPCs().stream().filter(npc -> npc.getWorld() == world).toList().size();
            int playercount = world.getPlayers().size();
            if (npcCount == 0) {
                world.setGameRule(GameRule.PLAYERS_SLEEPING_PERCENTAGE, target);
                return;
            }
            world.setGameRule(GameRule.PLAYERS_SLEEPING_PERCENTAGE, (int) (((playercount - npcCount) / (double) playercount) * target));
        });
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onGameruleChange(WorldGameRuleChangeEvent event) {
        if (event.getCommandSender() == null) return; // prevent stack overflows
        if (!event.getGameRule().equals(GameRule.PLAYERS_SLEEPING_PERCENTAGE)) return;
        worldSleepingPercentages.put(event.getWorld().getUID(), Integer.parseInt(event.getValue()));
        recalcSleepingPercentages();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onScroll(PlayerItemHeldEvent event) {
        Player player = event.getPlayer();
        if (!plugin.isWaiting(player, WaitingType.NUDGE)) return;

        if (plugin.getEditingNPCs().getIfPresent(player.getUniqueId()) == null) {
            player.sendMessage(Msg.translate(player.locale(), "customnpcs.error.npc-menu-expired"));
            plugin.waiting.remove(player.getUniqueId());
            return;
        }
        InternalNpc npc = PoseEditorMenu.previewNPCs.get(player.getUniqueId());
        if (npc == null) {
            // bad
            return;
        }
        int delta = (event.getNewSlot() - event.getPreviousSlot() + 9) % 9;
        double multiplier = delta <= 4 ? -.05 : .05; // handle toward or away
        multiplier = player.isSneaking() ? multiplier * 5 : multiplier; // sneaking makes it use larger jumps

        BlockFace face = player.getFacing();
        if (player.getLocation().getPitch() < -45) face = BlockFace.UP;
        if (player.getLocation().getPitch() > 45) face = BlockFace.DOWN;

        Vector vec = face.getDirection().multiply(multiplier);
        player.playSound(player, Sound.ITEM_FLINTANDSTEEL_USE, 1, 1);
        npc.getWorld().spawnParticle(Particle.REVERSE_PORTAL, npc.getCurrentLocation().add(vec), 10, 0, 0, 0, 0);
        npc.moveTo(vec);
    }

    @EventHandler
    public void onSwapToOffhand(PlayerSwapHandItemsEvent event) {
        Player player = event.getPlayer();
        if (plugin.isWaiting(player, WaitingType.NUDGE)) {
            event.setCancelled(true);
            InternalNpc npc = plugin.getEditingNPCs().getIfPresent(player.getUniqueId());
            if (npc == null) {
                player.sendMessage(Msg.translate(player.locale(), "customnpcs.error.npc-menu-expired"));
                return;
            }

            InternalNpc previewNpc = PoseEditorMenu.previewNPCs.get(player.getUniqueId());
            plugin.waiting.remove(player.getUniqueId());

            Location finalLoc = previewNpc.getCurrentLocation();
            finalLoc.setPitch(npc.getSpawnLoc().getPitch());
            finalLoc.setYaw(npc.getSpawnLoc().getYaw());
            npc.setSpawnLoc(finalLoc);
            previewNpc.remove();

            plugin.getNPCByID(npc.getUniqueID()).createNPC();
            plugin.getLotus().openMenu(player, MenuUtils.NPC_MAIN);
        }
    }

    @Getter
    private static class MovementData {
        private final UUID uniqueId;
        @Setter
        private Location lastLocation;
        @Setter
        private double distanceSquared;

        MovementData(UUID uniqueId, Location lastLocation, double distanceSquared) {
            this.uniqueId = uniqueId;
            this.lastLocation = lastLocation;
            this.distanceSquared = distanceSquared;
        }

        public MovementData copy() {
            return new MovementData(uniqueId, lastLocation, distanceSquared);
        }
    }
}
