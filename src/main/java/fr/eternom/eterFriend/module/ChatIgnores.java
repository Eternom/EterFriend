package fr.eternom.eterFriend.module;

import fr.eternom.eterChat.api.ChatApi;
import org.bukkit.Bukkit;

import java.util.UUID;

/**
 * Le /ignore d'EterChat (facultatif) : un joueur ignoré ne peut pas non plus envoyer de demande d'ami. Classe à part :
 * l'API d'EterChat n'est chargée que si le plugin tourne. Bloquant (base) : hors du thread principal.
 */
final class ChatIgnores {

    private ChatIgnores() {
    }

    static boolean ignores(UUID player, UUID other) {
        if (!Bukkit.getPluginManager().isPluginEnabled("EterChat")) {
            return false;
        }
        try {
            return ChatApi.get().map(api -> api.isIgnoring(player, other)).orElse(false);
        } catch (RuntimeException e) {
            return false;
        }
    }
}
