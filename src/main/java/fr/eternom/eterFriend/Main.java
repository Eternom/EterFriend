package fr.eternom.eterFriend;

import fr.eternom.eterFriend.api.FriendApi;
import fr.eternom.eterFriend.module.FriendCommand;
import fr.eternom.eterFriend.module.FriendListener;
import fr.eternom.eterFriend.module.FriendMenus;
import fr.eternom.eterFriend.module.FriendRepository;
import fr.eternom.eterFriend.module.FriendService;
import fr.eternom.eterLib.EterLib;
import fr.eternom.eterLib.helper.cache.NetworkBus;
import fr.eternom.eterLib.helper.message.Messages;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.Objects;

/**
 * Les amis du réseau : demandes, liste avec le serveur de chacun, rejoindre un ami, annonces d'arrivée et de départ.
 * Les autres plugins (EterHub...) passent par {@link FriendApi} ; les PNJ par l'action eterfriend:menu.
 */
public final class Main extends JavaPlugin {

    private static final String REQUIRED_ETERLIB = "1.23.0";
    private static final String TABLE_PREFIX = "eterfriend_";

    @Override
    public void onEnable() {
        saveDefaultConfig();
        try {
            if (!EterLib.requireVersion(this, REQUIRED_ETERLIB)) {
                return;
            }
        } catch (LinkageError tooOld) {
            getLogger().severe("EterLib " + REQUIRED_ETERLIB + " ou plus récent est nécessaire.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        EterLib lib = EterLib.get();
        Messages messages = lib.messages(this, "en_us", "fr_fr");
        NetworkBus bus = lib.network(this, "eterfriend", messages);
        FriendService service = new FriendService(this, new FriendRepository(lib.database(TABLE_PREFIX)), messages, bus,
                Math.max(1, getConfig().getInt("default-limit", 50)));
        FriendMenus menus = new FriendMenus(service, lib.backButton(getConfig().getString("menus.back-command", "")));
        service.setMenus(menus);

        FriendCommand command = new FriendCommand(service, menus);
        PluginCommand friend = Objects.requireNonNull(getCommand("friend"), "Commande absente du plugin.yml : friend");
        friend.setExecutor(command);
        friend.setTabCompleter(command);

        lib.getActions().register(this, "eterfriend:menu", "Le menu des amis", List.of(), (player, args) -> menus.openFriends(player, 0));
        lib.getActions().register(this, "eterfriend:requests", "Les demandes d'amis reçues", List.of(), (player, args) -> menus.openRequests(player));

        getServer().getServicesManager().register(FriendApi.class, service, this, ServicePriority.Normal);
        getServer().getPluginManager().registerEvents(new FriendListener(service), this);
        service.start();
    }
}
