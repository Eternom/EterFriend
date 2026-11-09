# EterFriend

Les amis du réseau Akia : demandes, liste avec le serveur de chacun, rejoindre un ami, annonces d'arrivée et de départ.
Paper 26.2, nécessite **EterLib 1.23.0** (base, Redis, bus réseau, téléportations).

## Joueurs

| Commande | Effet |
|---|---|
| `/ami` | Le menu : amis en ligne d'abord (avec leur serveur), demandes reçues, réglages |
| `/ami <joueur>` ou `/ami ajouter <joueur>` | Demande d'ami (si l'autre t'avait déjà demandé : vous êtes amis) |
| `/ami accepter` / `refuser <joueur>` | Répondre à une demande (aussi cliquable dans le message reçu) |
| `/ami retirer <joueur>` | Retirer un ami (des deux côtés) |
| `/ami rejoindre <joueur>` | Aller à côté de lui, sur son serveur |
| `/ami liste` | Les amis en ligne, en texte |
| `/ami demandes` | Les demandes reçues |
| `/ami notifications` | Couper / rouvrir les annonces d'arrivée |
| `/ami demandes-on-off` | Refuser / accepter les nouvelles demandes |

Dans le menu : clic sur un ami en ligne = le rejoindre, Maj + clic droit = le retirer.

- Une arrivée sur le réseau (pas un changement de serveur) et un départ sont annoncés aux amis, où qu'ils soient.
- Les joueurs en vanish n'apparaissent pas en ligne et ne sont pas annoncés.
- Les demandes de plus de 30 jours disparaissent.

## Configuration

`config.yml` : `default-limit` (50 amis), `menus.back-command`.
Limite plus haute par grade : permission `eterfriend.limit.<n>` (la plus grande l'emporte).

## Pour les autres plugins

```java
// plugin.yml : softdepend: [EterFriend]
FriendApi.get().ifPresent(api -> api.openMenu(player));
Set<UUID> friends = api.cachedFriends(uuid);      // joueur connecté ici, sans base
Map<UUID, String> online = api.onlineFriends(uuid); // bloquant : ami -> serveur
```

Actions EterLib (PNJ d'EterNpc, menus) : `eterfriend:menu`, `eterfriend:requests`.

## Tables

`eterfriend_friends` (une ligne par sens), `eterfriend_requests`, `eterfriend_settings`.
