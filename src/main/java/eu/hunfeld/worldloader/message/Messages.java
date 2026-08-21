package eu.hunfeld.worldloader.message;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.TagPattern;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.jetbrains.annotations.NotNull;

import java.util.List;

public final class Messages {

    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();

    private final FileConfiguration language;
    private final String primaryGradient;
    private final String secondaryGradient;
    private final String successGradient;
    private final String dangerGradient;
    private final Component prefix;

    public Messages(@NotNull final FileConfiguration config, @NotNull final FileConfiguration language) {
        this.language = language;
        this.primaryGradient = config.getString("format.primary-gradient", "<gradient:#23D5FF:#8B5CF6>");
        this.secondaryGradient = config.getString("format.secondary-gradient", "<gradient:#8B5CF6:#EC4899>");
        this.successGradient = config.getString("format.success-gradient", "<gradient:#34D399:#22C55E>");
        this.dangerGradient = config.getString("format.danger-gradient", "<gradient:#FB7185:#EF4444>");
        final String prefixTemplate = config.getString("format.prefix",
                "<aqua><bold>WorldLoader</bold></aqua> <dark_gray>»</dark_gray>");
        this.prefix = MINI_MESSAGE.deserialize(expandGradients(prefixTemplate));
    }

    public void send(@NotNull final CommandSender sender, @NotNull final String key,
                     @NotNull final TagResolver... resolvers) {
        sender.sendMessage(prefix.append(Component.space()).append(component(key, resolvers)));
    }

    public void sendRaw(@NotNull final CommandSender sender, @NotNull final String key,
                        @NotNull final TagResolver... resolvers) {
        sender.sendMessage(component(key, resolvers));
    }

    public void sendHelp(@NotNull final CommandSender sender) {
        final List<String> lines = language.getStringList("messages.help");
        for (final String line : lines) {
            sender.sendMessage(prefix.append(Component.space()).append(parse(line)));
        }
    }

    @NotNull
    public Component component(@NotNull final String key, @NotNull final TagResolver... resolvers) {
        final String value = language.getString("messages." + key, "<red>Missing message: " + key + "</red>");
        return parse(value, resolvers);
    }

    @NotNull
    public Component parse(@NotNull final String value, @NotNull final TagResolver... resolvers) {
        return MINI_MESSAGE.deserialize(expandGradients(value), TagResolver.resolver(resolvers));
    }

    @NotNull
    public TagResolver text(@TagPattern @NotNull final String key, @NotNull final Object value) {
        return Placeholder.unparsed(key, value.toString());
    }

    private String expandGradients(final String value) {
        return value
                .replace("<primary>", primaryGradient).replace("</primary>", "</gradient>")
                .replace("<secondary>", secondaryGradient).replace("</secondary>", "</gradient>")
                .replace("<success>", successGradient).replace("</success>", "</gradient>")
                .replace("<danger>", dangerGradient).replace("</danger>", "</gradient>");
    }
}
