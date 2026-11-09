package fr.eternom.eterFriend.module;

import fr.eternom.eterLib.EterLib;
import fr.eternom.eterLib.helper.gui.BackButton;
import fr.eternom.eterLib.helper.gui.Dialogs;
import fr.eternom.eterLib.helper.gui.Frame;
import fr.eternom.eterLib.helper.gui.Items;
import fr.eternom.eterLib.helper.gui.Menu;
import fr.eternom.eterLib.helper.gui.Sounds;
import fr.eternom.eterLib.helper.message.Messages;
import fr.eternom.eterLib.helper.task.Tasks;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Les menus des amis :
 * - la liste (28 par page, en ligne d'abord, avec leur serveur ; clic = rejoindre, Maj + clic droit = retirer),
 * - les demandes reçues (clic = accepter, clic droit = refuser),
 * - ajouter un ami (Dialog), accepter les demandes ou non, annonces d'arrivée ou non.
 */
public class FriendMenus {

    private static final int[] SLOTS = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25, 28, 29, 30, 31, 32, 33, 34,
            37, 38, 39, 40, 41, 42, 43};
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    /** Un ami tel que montré : en ligne ou non, son serveur. */
    record Friend(UUID uuid, String name, String server, long since, long lastSeen) {
        boolean online() {
            return server != null;
        }
    }

    private final FriendService service;
    private final Messages messages;
    private final BackButton back;

    public FriendMenus(FriendService service, BackButton back) {
        this.service = service;
        this.messages = service.messages();
        this.back = back;
    }

    public void openFriends(Player player, int page) {
        UUID uuid = player.getUniqueId();
        Tasks.async(service.plugin(), player, () -> {
            List<Friend> friends = new ArrayList<>();
            service.repository().friends(uuid).forEach((friend, since) -> {
                var known = EterLib.get().getPlayers().get(friend);
                String server = FriendService.visible(friend) ? EterLib.get().getPlayers().getServer(friend).orElse(null) : null;
                friends.add(new Friend(friend, known.map(found -> found.name()).orElse("?"), server, since,
                        known.map(found -> found.lastSeen()).orElse(0L)));
            });
            friends.sort((a, b) -> a.online() != b.online() ? (a.online() ? -1 : 1) : a.name().compareToIgnoreCase(b.name()));
            return new Object[]{friends, service.repository().incoming(uuid).size(), service.repository().settings(uuid)};
        }, data -> {
            @SuppressWarnings("unchecked") List<Friend> friends = (List<Friend>) data[0];
            player.openInventory(new FriendsMenu(player, friends, (int) data[1], (FriendRepository.Settings) data[2], page).getInventory());
        }, () -> messages.send(player, "error.generic"));
    }

    public void openRequests(Player player) {
        UUID uuid = player.getUniqueId();
        Tasks.async(service.plugin(), player, () -> {
            List<Friend> requests = new ArrayList<>();
            service.repository().incoming(uuid).forEach((sender, at) -> requests.add(new Friend(sender,
                    EterLib.get().getPlayers().get(sender).map(found -> found.name()).orElse("?"), null, at, 0)));
            return requests;
        }, requests -> player.openInventory(new RequestsMenu(player, requests).getInventory()),
                () -> messages.send(player, "error.generic"));
    }

    /** Ajouter un ami : son pseudo dans un Dialog. */
    void askAdd(Player player) {
        player.closeInventory();
        DialogBase base = DialogBase.builder(messages.get(player, "menu.add.title"))
                .body(List.of(DialogBody.plainMessage(messages.get(player, "menu.add.body"))))
                .inputs(List.of(DialogInput.text("name", messages.get(player, "menu.add.label")).maxLength(16).build()))
                .build();
        Dialogs.show(service.plugin(), player, base, messages.get(player, "menu.add.confirm"), messages.get(player, "dialog.cancel"),
                response -> {
                    String name = response.getText("name");
                    if (name != null && !name.isBlank()) {
                        service.request(player, name.strip());
                    }
                    Bukkit.getScheduler().runTaskLater(service.plugin(), () -> openFriends(player, 0), 10);
                }, () -> openFriends(player, 0));
    }

    private String date(long millis) {
        return millis <= 0 ? "?" : DATE.format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()));
    }

    /** La liste des amis. */
    private final class FriendsMenu implements Menu {

        private static final int ADD = 47;
        private static final int REQUESTS = 48;
        private static final int BACK = 49;
        private static final int TOGGLE_REQUESTS = 50;
        private static final int TOGGLE_NOTIFY = 51;
        private static final int PREVIOUS = 45;
        private static final int NEXT = 53;

        private final Player viewer;
        private final List<Friend> friends;
        private final int page;
        private final int pages;
        private final Inventory inventory;

        FriendsMenu(Player viewer, List<Friend> friends, int requests, FriendRepository.Settings settings, int page) {
            this.viewer = viewer;
            this.friends = friends;
            this.pages = Math.max(1, (friends.size() + SLOTS.length - 1) / SLOTS.length);
            this.page = Math.clamp(page, 0, pages - 1);
            long online = friends.stream().filter(Friend::online).count();
            this.inventory = Bukkit.createInventory(this, 54, messages.get(viewer, "menu.title", "online", String.valueOf(online),
                    "count", String.valueOf(friends.size()), "limit", String.valueOf(service.limit(viewer))));
            Frame.draw(inventory, Material.LIGHT_BLUE_STAINED_GLASS_PANE);
            for (int i = 0; i < SLOTS.length; i++) {
                int index = this.page * SLOTS.length + i;
                if (index >= friends.size()) {
                    break;
                }
                Friend friend = friends.get(index);
                List<Component> lore = new ArrayList<>();
                lore.add(friend.online()
                        ? messages.get(viewer, "menu.friend.online", "server", EterLib.get().getServerDisplayName(friend.server()))
                        : messages.get(viewer, "menu.friend.offline", "date", date(friend.lastSeen())));
                lore.add(messages.get(viewer, "menu.friend.since", "date", date(friend.since())));
                lore.add(Component.empty());
                if (friend.online()) {
                    lore.add(messages.get(viewer, "menu.friend.join"));
                }
                lore.add(messages.get(viewer, "menu.friend.remove"));
                inventory.setItem(SLOTS[i], Items.head(Bukkit.createProfile(friend.uuid(), friend.name()),
                        messages.get(viewer, friend.online() ? "menu.friend.name-online" : "menu.friend.name", "player", friend.name()), lore));
            }
            if (friends.isEmpty()) {
                inventory.setItem(22, Items.item(Material.PAPER, messages.get(viewer, "menu.empty"), List.of(messages.get(viewer, "menu.empty-lore"))));
            }
            inventory.setItem(ADD, Items.item(Material.EMERALD, messages.get(viewer, "menu.add.name"), List.of(messages.get(viewer, "menu.add.lore"))));
            inventory.setItem(REQUESTS, Items.item(requests > 0 ? Material.WRITABLE_BOOK : Material.BOOK, messages.get(viewer, "menu.requests.name"),
                    List.of(messages.get(viewer, "menu.requests.lore", "count", String.valueOf(requests))), requests > 0));
            inventory.setItem(BACK, back.item(viewer));
            inventory.setItem(TOGGLE_REQUESTS, Items.item(settings.requests() ? Material.LIME_DYE : Material.GRAY_DYE,
                    messages.get(viewer, "menu.toggle-requests.name"),
                    List.of(messages.get(viewer, settings.requests() ? "menu.toggle-requests.on" : "menu.toggle-requests.off"))));
            inventory.setItem(TOGGLE_NOTIFY, Items.item(settings.announce() ? Material.BELL : Material.GRAY_DYE,
                    messages.get(viewer, "menu.toggle-notify.name"),
                    List.of(messages.get(viewer, settings.announce() ? "menu.toggle-notify.on" : "menu.toggle-notify.off"))));
            if (this.page > 0) {
                inventory.setItem(PREVIOUS, Items.item(Material.ARROW, messages.get(viewer, "menu.previous"), List.of()));
            }
            if (this.page < pages - 1) {
                inventory.setItem(NEXT, Items.item(Material.ARROW, messages.get(viewer, "menu.next"), List.of()));
            }
        }

        @Override
        public void onClick(Player player, int slot, ClickType click) {
            switch (slot) {
                case ADD -> {
                    Sounds.click(player);
                    askAdd(player);
                    return;
                }
                case REQUESTS -> {
                    Sounds.page(player);
                    openRequests(player);
                    return;
                }
                case BACK -> {
                    back.click(player);
                    return;
                }
                case TOGGLE_REQUESTS, TOGGLE_NOTIFY -> {
                    Sounds.click(player);
                    service.toggle(player, slot == TOGGLE_REQUESTS, () -> openFriends(player, page));
                    return;
                }
                case PREVIOUS, NEXT -> {
                    if (slot == PREVIOUS ? page > 0 : page < pages - 1) {
                        Sounds.page(player);
                        openFriends(player, page + (slot == NEXT ? 1 : -1));
                    }
                    return;
                }
                default -> {
                }
            }
            friendAt(slot).ifPresent(friend -> {
                if (click == ClickType.SHIFT_RIGHT) {
                    Sounds.click(player);
                    service.remove(player, friend.uuid(), friend.name(), () -> openFriends(player, page));
                } else if (friend.online()) {
                    Sounds.click(player);
                    service.join(player, friend.uuid(), friend.name());
                } else {
                    Sounds.refuse(player);
                }
            });
        }

        private Optional<Friend> friendAt(int slot) {
            for (int i = 0; i < SLOTS.length; i++) {
                int index = page * SLOTS.length + i;
                if (SLOTS[i] == slot && index < friends.size()) {
                    return Optional.of(friends.get(index));
                }
            }
            return Optional.empty();
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    /** Les demandes reçues. */
    private final class RequestsMenu implements Menu {

        private static final int BACK = 49;

        private final List<Friend> requests;
        private final Inventory inventory;

        RequestsMenu(Player viewer, List<Friend> requests) {
            this.requests = requests;
            this.inventory = Bukkit.createInventory(this, 54, messages.get(viewer, "menu.requests.title", "count", String.valueOf(requests.size())));
            Frame.draw(inventory, Material.LIGHT_BLUE_STAINED_GLASS_PANE);
            for (int i = 0; i < requests.size() && i < SLOTS.length; i++) {
                Friend request = requests.get(i);
                inventory.setItem(SLOTS[i], Items.head(Bukkit.createProfile(request.uuid(), request.name()),
                        messages.get(viewer, "menu.friend.name", "player", request.name()),
                        List.of(messages.get(viewer, "menu.requests.sent", "date", date(request.since())), Component.empty(),
                                messages.get(viewer, "menu.requests.accept"), messages.get(viewer, "menu.requests.deny"))));
            }
            if (requests.isEmpty()) {
                inventory.setItem(22, Items.item(Material.PAPER, messages.get(viewer, "menu.requests.empty"), List.of()));
            }
            inventory.setItem(BACK, Items.item(Material.OAK_DOOR, messages.get(viewer, "menu.back-friends"), List.of()));
        }

        @Override
        public void onClick(Player player, int slot, ClickType click) {
            if (slot == BACK) {
                Sounds.page(player);
                openFriends(player, 0);
                return;
            }
            for (int i = 0; i < requests.size() && i < SLOTS.length; i++) {
                if (SLOTS[i] == slot) {
                    Friend request = requests.get(i);
                    Sounds.click(player);
                    service.answer(player, request.uuid(), request.name(), !click.isRightClick(), () -> openRequests(player));
                    return;
                }
            }
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    /** Pour la commande : les amis en ligne, en texte. */
    void listInChat(Player player) {
        UUID uuid = player.getUniqueId();
        Tasks.async(service.plugin(), player, () -> service.onlineFriends(uuid), online -> {
            if (online.isEmpty()) {
                messages.send(player, "friend.none-online");
                return;
            }
            messages.send(player, "friend.online-title", "count", String.valueOf(online.size()));
            for (Map.Entry<UUID, String> entry : online.entrySet()) {
                String name = EterLib.get().getPlayers().get(entry.getKey()).map(found -> found.name()).orElse("?");
                player.sendMessage(messages.get(player, "friend.online-line", "player", name,
                        "server", EterLib.get().getServerDisplayName(entry.getValue())));
            }
        }, () -> messages.send(player, "error.generic"));
    }
}
