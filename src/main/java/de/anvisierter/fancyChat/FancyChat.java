package de.anvisierter.fancyChat;

import io.papermc.paper.event.player.AsyncChatEvent;
import me.clip.placeholderapi.PlaceholderAPI;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.model.user.User;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

public final class FancyChat extends JavaPlugin implements Listener {

    private static final PlainTextComponentSerializer PLAIN_TEXT = PlainTextComponentSerializer.plainText();
    private static final LegacyComponentSerializer LEGACY_AMPERSAND = LegacyComponentSerializer.legacyAmpersand();

    private final MiniMessage miniMessage = MiniMessage.miniMessage();
    private LuckPerms luckPerms;
    private boolean placeholderApiAvailable;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        setupLuckPerms();
        updateHookState();

        Bukkit.getPluginManager().registerEvents(this, this);
        if (getCommand("fancychat") != null) {
            getCommand("fancychat").setExecutor(this);
        }

        getLogger().info("FancyChat has been enabled.");
    }

    @Override
    public void onDisable() {
        getLogger().info("FancyChat has been disabled.");
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, String @NotNull [] args) {
        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            reloadConfig();
            updateHookState();
            sender.sendMessage(miniMessage.deserialize("<green>FancyChat config has been reloaded.</green>"));
            return true;
        }

        sender.sendMessage(miniMessage.deserialize("<red>Use /" + label + " reload</red>"));
        return true;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onAsyncChat(AsyncChatEvent event) {
        Player player = event.getPlayer();
        String rawMessage = PLAIN_TEXT.serialize(event.message());
        Set<Audience> viewers = new HashSet<>(event.viewers());

        event.setCancelled(true);
        Bukkit.getScheduler().runTask(this, () -> {
            for (Audience viewer : viewers) {
                viewer.sendMessage(renderChatMessage(player, rawMessage));
            }
        });
    }

    private void setupLuckPerms() {
        this.luckPerms = Bukkit.getServicesManager().load(LuckPerms.class);
        if (this.luckPerms == null) {
            getLogger().severe("LuckPerms API could not be found. Is LuckPerms installed?");
        }
    }

    private void updateHookState() {
        this.placeholderApiAvailable = Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI");
    }

    private Component renderChatMessage(Player player, String rawMessage) {
        FileConfiguration config = getConfig();
        String format = config.getString("chat.format", "<prefix><player><gray>: </gray><message>");
        format = normalizeConfigTags(applyPlaceholderApi(player, format, config.getBoolean("placeholderapi.apply-to-format", true)));

        Component prefix = luckPermsMeta(player, true);
        Component suffix = luckPermsMeta(player, false);
        Component message = renderPlayerMessage(player, rawMessage);
        Component item = renderItem(player);

        TagResolver resolver = TagResolver.resolver(
                Placeholder.component("prefix", prefix),
                Placeholder.component("suffix", suffix),
                Placeholder.component("player", Component.text(player.getName())),
                Placeholder.component("display_name", player.displayName()),
                Placeholder.component("displayname", player.displayName()),
                Placeholder.component("message", message),
                Placeholder.component("item", item)
        );

        return miniMessage.deserialize(format, resolver);
    }

    private Component renderPlayerMessage(Player player, String rawMessage) {
        FileConfiguration config = getConfig();
        String message = applyPlaceholderApi(player, rawMessage, config.getBoolean("placeholderapi.apply-to-message", false));
        String itemToken = config.getString("item.token", "{item}");
        boolean itemEnabled = config.getBoolean("item.enabled", true);

        boolean parseMiniMessage = config.getBoolean("chat.allow-player-minimessage", true);
        if (config.getBoolean("chat.require-minimessage-permission", false)) {
            parseMiniMessage = player.hasPermission("fancychat.minimessage");
        }

        if (!itemEnabled || itemToken == null || itemToken.isEmpty() || !message.contains(itemToken)) {
            return deserializeMessage(message, parseMiniMessage);
        }

        Component result = Component.empty();
        int currentIndex = 0;
        int tokenIndex;
        while ((tokenIndex = message.indexOf(itemToken, currentIndex)) >= 0) {
            String beforeToken = message.substring(currentIndex, tokenIndex);
            result = result.append(deserializeMessage(beforeToken, parseMiniMessage));
            result = result.append(renderItem(player));
            currentIndex = tokenIndex + itemToken.length();
        }

        if (currentIndex < message.length()) {
            result = result.append(deserializeMessage(message.substring(currentIndex), parseMiniMessage));
        }

        return result;
    }

    private Component deserializeMessage(String text, boolean parseMiniMessage) {
        if (!parseMiniMessage) {
            return Component.text(text);
        }

        return miniMessage.deserialize(text);
    }

    private Component luckPermsMeta(Player player, boolean prefix) {
        if (luckPerms == null) {
            return Component.empty();
        }

        User user = luckPerms.getUserManager().getUser(player.getUniqueId());
        if (user == null) {
            return Component.empty();
        }

        String value = prefix
                ? user.getCachedData().getMetaData().getPrefix()
                : user.getCachedData().getMetaData().getSuffix();
        if (value == null || value.isBlank()) {
            return Component.empty();
        }

        FileConfiguration config = getConfig();
        String path = prefix ? "placeholderapi.apply-to-prefix" : "placeholderapi.apply-to-suffix";
        value = applyPlaceholderApi(player, value, config.getBoolean(path, true));

        String parsePath = prefix ? "luckperms.parse-prefix-minimessage" : "luckperms.parse-suffix-minimessage";
        if (config.getBoolean(parsePath, true) && !looksLikeLegacyColorText(value)) {
            return miniMessage.deserialize(value);
        }

        return LEGACY_AMPERSAND.deserialize(value);
    }

    private Component renderItem(Player player) {
        ItemStack item = player.getInventory().getItemInMainHand();
        FileConfiguration config = getConfig();

        if (item.getType() == Material.AIR || item.getAmount() <= 0) {
            return miniMessage.deserialize(config.getString("item.empty-hand", "<gray>[Empty Hand]</gray>"));
        }

        String itemFormat = normalizeConfigTags(config.getString("item.format", "<gray>[</gray><item_name><gray> x<amount>]</gray>"));
        Component itemName = itemName(item);
        TagResolver resolver = TagResolver.resolver(
                Placeholder.component("item_name", itemName),
                Placeholder.unparsed("amount", Integer.toString(item.getAmount())),
                Placeholder.unparsed("material", item.getType().getKey().asString())
        );

        return miniMessage.deserialize(itemFormat, resolver).hoverEvent(itemHoverEvent(item));
    }

    private Component itemName(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        if (meta != null && meta.hasDisplayName() && meta.displayName() != null) {
            return meta.displayName();
        }

        return Component.text(prettyMaterialName(item.getType()));
    }

    private HoverEvent<?> itemHoverEvent(ItemStack item) {
        try {
            Method method = item.getClass().getMethod("asHoverEvent");
            Object hoverEvent = method.invoke(item);
            if (hoverEvent instanceof HoverEvent<?> typedHoverEvent) {
                return typedHoverEvent;
            }
        } catch (ReflectiveOperationException ignored) {
        }

        Key key = Key.key(item.getType().getKey().getNamespace(), item.getType().getKey().getKey());
        return HoverEvent.showItem(key, item.getAmount());
    }

    private String applyPlaceholderApi(Player player, String text, boolean enabledForField) {
        if (text == null || text.isEmpty()) {
            return "";
        }

        if (!getConfig().getBoolean("placeholderapi.enabled", true) || !enabledForField || !placeholderApiAvailable) {
            return text;
        }

        return PlaceholderAPI.setPlaceholders(player, text);
    }

    private String normalizeConfigTags(String text) {
        return text
                .replace("{prefix}", "<prefix>")
                .replace("{suffix}", "<suffix>")
                .replace("{player}", "<player>")
                .replace("{display_name}", "<display_name>")
                .replace("{displayname}", "<displayname>")
                .replace("{message}", "<message>")
                .replace("{item}", "<item>");
    }

    private boolean looksLikeLegacyColorText(String text) {
        return text.matches(".*[&\\u00A7][0-9a-fk-orA-FK-OR].*");
    }

    private String prettyMaterialName(Material material) {
        String[] words = material.name().toLowerCase(Locale.ROOT).split("_");
        StringBuilder builder = new StringBuilder();
        for (String word : words) {
            if (!builder.isEmpty()) {
                builder.append(' ');
            }
            builder.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return builder.toString();
    }
}
