package fr.eternom.eterFriend.api;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Ce qu'EterFriend offre aux autres plugins : les amis d'un joueur et ceux qui sont en ligne (avec leur serveur). Personne
 * d'autre ne lit les tables eterfriend_* : on demande ici.
 * <pre>
 *     // compileOnly("com.github.Eternom:EterFriend:&lt;tag&gt;") ; plugin.yml : softdepend: [EterFriend]
 * </pre>
 */
public interface FriendApi {

    static Optional<FriendApi> get() {
        return Optional.ofNullable(Bukkit.getServicesManager().load(FriendApi.class));
    }

    /** Les amis d'un joueur connecté ICI, sans base (gardés en mémoire à son arrivée). Vide s'il n'est pas ici. */
    Set<UUID> cachedFriends(UUID player);

    /** Les amis d'un joueur. Bloquant (base). */
    Set<UUID> friends(UUID player);

    /** Les deux joueurs sont amis. Bloquant (base) ; immédiat si l'un des deux est connecté ici. */
    boolean areFriends(UUID a, UUID b);

    /** Les amis en ligne (et visibles) d'un joueur : ami -> serveur. Bloquant (Redis). */
    Map<UUID, String> onlineFriends(UUID player);

    /** Le menu des amis. Thread principal. */
    void openMenu(Player player);
}
