package fr.eternom.eterFriend.module;

import fr.eternom.eterLib.EterLib;
import fr.eternom.eterLib.helper.message.Messages;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Locale;

/**
 * /friend [add | accept | deny | remove | join <joueur>] [list | requests | notify | toggle] : sans argument, le menu.
 * /friend <joueur> : raccourci de /friend add <joueur>. accept / deny sans pseudo : la dernière demande reçue (boutons
 * du chat). Les commandes et leurs arguments sont en anglais ; les textes sont traduits (lang/).
 */
public class FriendCommand implements TabExecutor {

    private static final List<String> SUBS = List.of("add", "accept", "deny", "remove", "join", "list", "requests", "notify", "toggle");
    private static final List<String> WITHOUT_PLAYER = List.of("list", "requests", "notify", "toggle");

    private final FriendService service;
    private final FriendMenus menus;
    private final Messages messages;

    public FriendCommand(FriendService service, FriendMenus menus) {
        this.service = service;
        this.menus = menus;
        this.messages = service.messages();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            messages.send(sender, "command.players-only");
            return true;
        }
        if (args.length == 0) {
            menus.openFriends(player, 0);
            return true;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        String target = args.length > 1 ? args[1] : null;
        switch (sub) {
            case "list" -> menus.listInChat(player);
            case "requests" -> menus.openRequests(player);
            case "notify" -> service.toggle(player, false, () -> { });
            case "toggle" -> service.toggle(player, true, () -> { });
            case "add", "accept", "deny", "remove", "join" -> {
                if (target == null) {
                    // Sans pseudo : accepter / refuser la dernière demande reçue (boutons du chat)
                    switch (sub) {
                        case "accept" -> service.answerLatest(player, true);
                        case "deny" -> service.answerLatest(player, false);
                        default -> messages.send(player, "command.usage", "label", label);
                    }
                    return true;
                }
                switch (sub) {
                    case "add" -> service.request(player, target);
                    case "accept" -> service.answer(player, target, true);
                    case "deny" -> service.answer(player, target, false);
                    case "remove" -> service.remove(player, target);
                    default -> joinByName(player, target);
                }
            }
            default -> service.request(player, args[0]);
        }
        return true;
    }

    private void joinByName(Player player, String name) {
        EterLib.get().getPlayers().findOnlineFor(player, name).ifPresentOrElse(
                found -> service.join(player, found.uuid(), found.name()),
                () -> messages.send(player, "friend.offline", "player", name));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 1) {
            String start = args[0].toLowerCase(Locale.ROOT);
            return SUBS.stream().filter(sub -> sub.startsWith(start)).toList();
        }
        if (args.length == 2 && !WITHOUT_PLAYER.contains(args[0].toLowerCase(Locale.ROOT))) {
            return EterLib.get().getOnlineNames().complete(args[1]);
        }
        return List.of();
    }
}
