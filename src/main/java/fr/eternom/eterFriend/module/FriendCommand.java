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
 * /ami [ajouter | accepter | refuser | retirer <joueur>] [liste | demandes | notifications | rejoindre <joueur>] :
 * sans argument, le menu des amis.
 */
public class FriendCommand implements TabExecutor {

    private static final List<String> SUBS = List.of("ajouter", "accepter", "refuser", "retirer", "rejoindre", "liste",
            "demandes", "notifications", "demandes-on-off");

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
            case "liste", "list" -> menus.listInChat(player);
            case "demandes", "requests" -> menus.openRequests(player);
            case "notifications", "notify" -> service.toggle(player, false, () -> { });
            case "demandes-on-off", "toggle" -> service.toggle(player, true, () -> { });
            case "ajouter", "add", "accepter", "accept", "refuser", "deny", "retirer", "remove", "rejoindre", "join" -> {
                if (target == null) {
                    messages.send(player, "command.usage", "label", label);
                    return true;
                }
                switch (sub) {
                    case "ajouter", "add" -> service.request(player, target);
                    case "accepter", "accept" -> service.answer(player, target, true);
                    case "refuser", "deny" -> service.answer(player, target, false);
                    case "retirer", "remove" -> service.remove(player, target);
                    default -> joinByName(player, target);
                }
            }
            // /ami <joueur> : raccourci de /ami ajouter <joueur>
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
        if (args.length == 2 && !List.of("liste", "demandes", "notifications", "demandes-on-off").contains(args[0].toLowerCase(Locale.ROOT))) {
            return EterLib.get().getOnlineNames().complete(args[1]);
        }
        return List.of();
    }
}
