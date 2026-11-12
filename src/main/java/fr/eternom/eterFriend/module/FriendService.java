package fr.eternom.eterFriend.module;

import fr.eternom.eterFriend.api.FriendApi;
import fr.eternom.eterLib.EterLib;
import fr.eternom.eterLib.helper.cache.NetworkBus;
import fr.eternom.eterLib.helper.gui.Sounds;
import fr.eternom.eterLib.helper.message.Messages;
import fr.eternom.eterLib.helper.task.Tasks;
import fr.eternom.eterLib.module.player.PlayerDirectory.NetworkPlayer;
import fr.eternom.eterLib.module.teleport.Destination;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.permissions.PermissionAttachmentInfo;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.Duration;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Les amis du réseau : demandes (une demande croisée devient une amitié), liste, retrait, réglages ; arrivée et départ
 * d'un ami annoncés où qu'il soit (bus d'EterLib), sans compter les changements de serveur ni les joueurs en vanish.
 * Rejoindre un ami : son serveur, à côté de lui. Les amis des joueurs connectés ici sont gardés en mémoire (cachedFriends)
 * pour les autres plugins (visibilité des lobbys...). Thread principal, base en tâche de fond.
 */
public class FriendService implements FriendApi {

    public static final String LIMIT_PERMISSION = "eterfriend.limit.";
    private static final String ONLINE_KEY = "friend-online:";
    private static final Duration ONLINE_TTL = Duration.ofSeconds(90);
    private static final long REFRESH_TICKS = 30 * 20;
    /** Délai avant d'annoncer un départ : un changement de serveur n'est pas un départ. */
    private static final long LEAVE_CHECK_TICKS = 10 * 20;

    private final JavaPlugin plugin;
    private final FriendRepository repository;
    private final Messages messages;
    private final NetworkBus bus;
    private final int defaultLimit;
    private final Map<UUID, Set<UUID>> cache = new ConcurrentHashMap<>();
    private FriendMenus menus;

    public FriendService(JavaPlugin plugin, FriendRepository repository, Messages messages, NetworkBus bus, int defaultLimit) {
        this.plugin = plugin;
        this.repository = repository;
        this.messages = messages;
        this.bus = bus;
        this.defaultLimit = defaultLimit;
        bus.on("changed", data -> {
            // Une amitié a changé ailleurs : les deux joueurs relisent leurs amis s'ils sont ici
            reload(UUID.fromString(data.get("a").getAsString()));
            reload(UUID.fromString(data.get("b").getAsString()));
        });
    }

    public void setMenus(FriendMenus menus) {
        this.menus = menus;
    }

    public void start() {
        Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, () -> {
            for (Player player : Bukkit.getOnlinePlayers()) {
                EterLib.get().getRedis().set(ONLINE_KEY + player.getUniqueId(), "1", ONLINE_TTL);
            }
        }, REFRESH_TICKS, REFRESH_TICKS);
        Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, () -> repository.purgeRequests(30), 20 * 60, 20 * 60 * 60);
        // Rechargement à chaud : les joueurs déjà là gardent leurs amis en mémoire (sans annonce)
        Bukkit.getOnlinePlayers().forEach(player -> reload(player.getUniqueId()));
    }

    // ---------- API ----------

    @Override
    public Set<UUID> cachedFriends(UUID player) {
        return cache.getOrDefault(player, Set.of());
    }

    @Override
    public Set<UUID> friends(UUID player) {
        return repository.friends(player).keySet();
    }

    @Override
    public boolean areFriends(UUID a, UUID b) {
        Set<UUID> known = cache.get(a);
        if (known != null) {
            return known.contains(b);
        }
        known = cache.get(b);
        return known != null ? known.contains(a) : repository.areFriends(a, b);
    }

    @Override
    public Map<UUID, String> onlineFriends(UUID player) {
        Map<UUID, String> online = new LinkedHashMap<>();
        for (UUID friend : friends(player)) {
            if (EterLib.get().getVanish().isVanished(friend)) {
                continue;
            }
            EterLib.get().getPlayers().getServer(friend).ifPresent(server -> online.put(friend, server));
        }
        return online;
    }

    @Override
    public void openMenu(Player player) {
        menus.openFriends(player, 0);
    }

    // ---------- Arrivée et départ ----------

    /** Arrivée (thread principal) : amis en mémoire, et annonce aux amis si c'est une arrivée sur le réseau. */
    void join(Player player) {
        UUID uuid = player.getUniqueId();
        String name = player.getName();
        Tasks.async(plugin, () -> {
            Set<UUID> friends = Set.copyOf(repository.friends(uuid).keySet());
            cache.put(uuid, friends);
            boolean networkJoin = !EterLib.get().getRedis().exists(ONLINE_KEY + uuid);
            EterLib.get().getRedis().set(ONLINE_KEY + uuid, "1", ONLINE_TTL);
            int pending = repository.incoming(uuid).size();
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (networkJoin && !EterLib.get().getVanish().isVanished(uuid)) {
                    friends.forEach(friend -> notifyIfWanted(friend, "friend.joined", "player", name));
                }
                if (pending > 0 && player.isOnline()) {
                    messages.send(player, "friend.pending", "count", String.valueOf(pending));
                }
            });
        }, "Amis non lus pour " + name);
    }

    /** Départ : annoncé aux amis seulement s'il n'est plus nulle part 10 s plus tard (pas un changement de serveur). */
    void quit(Player player) {
        UUID uuid = player.getUniqueId();
        String name = player.getName();
        Set<UUID> friends = cache.remove(uuid);
        Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, () -> {
            if (EterLib.get().getPlayers().getServer(uuid).isPresent() || Bukkit.getPlayer(uuid) != null) {
                return; // passé sur un autre serveur
            }
            EterLib.get().getRedis().delete(ONLINE_KEY + uuid);
            if (friends != null && !EterLib.get().getVanish().isVanished(uuid)) {
                Bukkit.getScheduler().runTask(plugin, () -> friends.forEach(friend -> notifyIfWanted(friend, "friend.left", "player", name)));
            }
        }, LEAVE_CHECK_TICKS);
    }

    /** Prévient un ami où qu'il soit, s'il veut ces annonces (lu en base). Thread principal. */
    private void notifyIfWanted(UUID friend, String key, String... placeholders) {
        Tasks.async(plugin, () -> {
            if (repository.settings(friend).announce()) {
                Bukkit.getScheduler().runTask(plugin, () -> bus.notify(friend, key, false, placeholders));
            }
        }, "Annonce d'ami non envoyée");
    }

    private void reload(UUID player) {
        if (Bukkit.getPlayer(player) == null) {
            return;
        }
        Tasks.async(plugin, () -> cache.put(player, Set.copyOf(repository.friends(player).keySet())), "Amis non relus");
    }

    private void changed(UUID a, UUID b) {
        reload(a);
        reload(b);
        com.google.gson.JsonObject data = new com.google.gson.JsonObject();
        data.addProperty("a", a.toString());
        data.addProperty("b", b.toString());
        Bukkit.getScheduler().runTask(plugin, () -> bus.publish("changed", data));
    }

    // ---------- Demandes ----------

    /** Demande d'ami (ou acceptation, si l'autre avait déjà demandé). Thread principal. */
    public void request(Player player, String targetName) {
        UUID uuid = player.getUniqueId();
        int limit = limit(player);
        String name = player.getName();
        Tasks.async(plugin, player, () -> {
            Optional<NetworkPlayer> target = EterLib.get().getPlayers().findFor(player, targetName);
            if (target.isEmpty()) {
                return new String[]{"player.unknown", targetName};
            }
            UUID other = target.get().uuid();
            String otherName = target.get().name();
            if (other.equals(uuid)) {
                return new String[]{"friend.self", otherName};
            }
            if (repository.areFriends(uuid, other)) {
                return new String[]{"friend.already", otherName};
            }
            if (repository.count(uuid) >= limit) {
                return new String[]{"friend.limit", String.valueOf(limit)};
            }
            if (repository.hasRequest(other, uuid)) {
                repository.befriend(uuid, other);
                changed(uuid, other);
                Bukkit.getScheduler().runTask(plugin, () -> bus.notify(other, "friend.accepted-by", true, "player", name));
                return new String[]{"friend.now-friends", otherName};
            }
            if (!repository.settings(other).requests() || ChatIgnores.ignores(other, uuid)) {
                return new String[]{"friend.refuses-requests", otherName};
            }
            if (!repository.request(uuid, other)) {
                return new String[]{"friend.already-sent", otherName};
            }
            Bukkit.getScheduler().runTask(plugin, () -> bus.notify(other, "friend.request-received", true, "player", name));
            return new String[]{"friend.request-sent", otherName};
        }, result -> {
            messages.send(player, result[0], "player", result[1], "limit", result[1]);
            if (result[0].equals("friend.request-sent") || result[0].equals("friend.now-friends")) {
                Sounds.click(player);
            } else {
                Sounds.refuse(player);
            }
        }, () -> messages.send(player, "error.generic"));
    }

    /** Accepter ou refuser la demande d'un joueur. Thread principal. */
    public void answer(Player player, UUID sender, String senderName, boolean accept, Runnable after) {
        UUID uuid = player.getUniqueId();
        int limit = limit(player);
        String name = player.getName();
        Tasks.async(plugin, player, () -> {
            if (!repository.hasRequest(sender, uuid)) {
                return "friend.no-request";
            }
            if (!accept) {
                repository.removeRequest(sender, uuid);
                return "friend.denied";
            }
            if (repository.count(uuid) >= limit) {
                return "friend.limit";
            }
            repository.befriend(uuid, sender);
            changed(uuid, sender);
            Bukkit.getScheduler().runTask(plugin, () -> bus.notify(sender, "friend.accepted-by", true, "player", name));
            return "friend.now-friends";
        }, result -> {
            messages.send(player, result, "player", senderName, "limit", String.valueOf(limit));
            after.run();
        }, () -> messages.send(player, "error.generic"));
    }

    /** Accepter ou refuser par le nom (commande). */
    public void answer(Player player, String senderName, boolean accept) {
        Tasks.async(plugin, player, () -> EterLib.get().getPlayers().find(senderName), found -> {
            if (found.isEmpty()) {
                messages.send(player, "player.unknown", "player", senderName);
            } else {
                answer(player, found.get().uuid(), found.get().name(), accept, () -> { });
            }
        }, () -> messages.send(player, "error.generic"));
    }

    /** Accepter ou refuser la dernière demande reçue (boutons du chat, /friend accept sans pseudo). */
    public void answerLatest(Player player, boolean accept) {
        UUID uuid = player.getUniqueId();
        Tasks.async(plugin, player, () -> repository.incoming(uuid).keySet().stream().reduce((first, second) -> second)
                .map(sender -> new Object[]{sender, EterLib.get().getPlayers().get(sender).map(NetworkPlayer::name).orElse("?")}),
                latest -> latest.ifPresentOrElse(found -> answer(player, (UUID) found[0], (String) found[1], accept, () -> { }),
                        () -> messages.send(player, "friend.no-pending")),
                () -> messages.send(player, "error.generic"));
    }

    /** Retirer un ami (des deux côtés). Thread principal. */
    public void remove(Player player, UUID friend, String friendName, Runnable after) {
        UUID uuid = player.getUniqueId();
        Tasks.async(plugin, player, () -> {
            boolean removed = repository.unfriend(uuid, friend);
            if (removed) {
                changed(uuid, friend);
            }
            return removed;
        }, removed -> {
            messages.send(player, removed ? "friend.removed" : "friend.not-friends", "player", friendName);
            after.run();
        }, () -> messages.send(player, "error.generic"));
    }

    public void remove(Player player, String friendName) {
        Tasks.async(plugin, player, () -> EterLib.get().getPlayers().find(friendName), found -> {
            if (found.isEmpty()) {
                messages.send(player, "player.unknown", "player", friendName);
            } else {
                remove(player, found.get().uuid(), found.get().name(), () -> { });
            }
        }, () -> messages.send(player, "error.generic"));
    }

    /** Rejoindre un ami : à côté de lui, sur son serveur (règles de téléportation d'EterLib). Thread principal. */
    public void join(Player player, UUID friend, String friendName) {
        UUID uuid = player.getUniqueId();
        Tasks.async(plugin, player, () -> {
            if (!areFriends(uuid, friend)) {
                return Optional.<String>empty();
            }
            if (EterLib.get().getVanish().isVanished(friend)) {
                return Optional.of("");
            }
            return EterLib.get().getPlayers().getServer(friend).or(() -> Optional.of(""));
        }, server -> {
            if (server.isEmpty()) {
                messages.send(player, "friend.not-friends", "player", friendName);
            } else if (server.get().isEmpty()) {
                messages.send(player, "friend.offline", "player", friendName);
            } else {
                player.closeInventory();
                EterLib.get().getTeleports().teleport(player, Destination.toPlayer(friend, server.get(), friendName));
            }
        }, () -> messages.send(player, "error.generic"));
    }

    /** Réglages : accepter les demandes, recevoir les annonces d'arrivée. */
    public void toggle(Player player, boolean requests, Runnable after) {
        UUID uuid = player.getUniqueId();
        Tasks.async(plugin, player, () -> {
            FriendRepository.Settings current = repository.settings(uuid);
            FriendRepository.Settings next = requests ? new FriendRepository.Settings(!current.requests(), current.announce())
                    : new FriendRepository.Settings(current.requests(), !current.announce());
            repository.saveSettings(uuid, next);
            return next;
        }, next -> {
            messages.send(player, requests ? (next.requests() ? "friend.requests-on" : "friend.requests-off")
                    : (next.announce() ? "friend.notify-on" : "friend.notify-off"));
            after.run();
        }, () -> messages.send(player, "error.generic"));
    }

    /** Nombre d'amis permis : eterfriend.limit.<n> le plus grand, sinon default-limit. */
    public int limit(Player player) {
        int best = defaultLimit;
        for (PermissionAttachmentInfo info : player.getEffectivePermissions()) {
            if (info.getValue() && info.getPermission().startsWith(LIMIT_PERMISSION)) {
                best = Math.max(best, Limits.parse(info.getPermission().substring(LIMIT_PERMISSION.length()), 0));
            }
        }
        return best;
    }

    FriendRepository repository() {
        return repository;
    }

    Messages messages() {
        return messages;
    }

    JavaPlugin plugin() {
        return plugin;
    }

    /** Petit utilitaire (testé). */
    static final class Limits {
        private Limits() {
        }

        static int parse(String text, int fallback) {
            try {
                return Math.max(0, Integer.parseInt(text.trim()));
            } catch (NumberFormatException e) {
                return fallback;
            }
        }
    }

    /** Pour les menus : le joueur visible ou non (vanish). */
    static boolean visible(UUID player) {
        return !EterLib.get().getVanish().isVanished(player);
    }

    /** Ensemble modifiable (menus). */
    static Set<UUID> copy(Set<UUID> set) {
        return new HashSet<>(set);
    }
}
