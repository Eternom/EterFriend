package fr.eternom.eterFriend.module;

import fr.eternom.eterLib.helper.sql.Column;
import fr.eternom.eterLib.helper.sql.Database;
import fr.eternom.eterLib.helper.sql.Row;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Les amitiés, communes au réseau. Appels bloquants : hors du thread principal.
 * - eterfriend_friends : une ligne par sens (a -> b et b -> a), avec la date ;
 * - eterfriend_requests : demandes en attente (de, à, date) ;
 * - eterfriend_settings : accepte les demandes, reçoit les notifications d'arrivée.
 */
public class FriendRepository {

    private static final String FRIENDS = "friends";
    private static final String REQUESTS = "requests";
    private static final String SETTINGS = "settings";

    /** Réglages d'un joueur. */
    public record Settings(boolean requests, boolean announce) {
        public static final Settings DEFAULT = new Settings(true, true);
    }

    private final Database database;

    public FriendRepository(Database database) {
        this.database = database;
        database.createTable(FRIENDS,
                Column.of("uuid", Column.Type.UUID).primaryKey(),
                Column.of("friend", Column.Type.UUID).primaryKey(),
                Column.of("since", Column.Type.LONG).notNull());
        database.createTable(REQUESTS,
                Column.of("sender", Column.Type.UUID).primaryKey(),
                Column.of("target", Column.Type.UUID).primaryKey(),
                Column.of("sent_at", Column.Type.LONG).notNull());
        database.createTable(SETTINGS,
                Column.of("uuid", Column.Type.UUID).primaryKey(),
                Column.of("requests", Column.Type.BOOLEAN).notNull(),
                Column.of("notify", Column.Type.BOOLEAN).notNull());
    }

    /** Amis -> date de l'amitié. */
    public Map<UUID, Long> friends(UUID player) {
        Map<UUID, Long> friends = new LinkedHashMap<>();
        database.query("SELECT friend, since FROM " + database.table(FRIENDS) + " WHERE uuid = ? ORDER BY since", player)
                .forEach(row -> friends.put(row.getUUID("friend"), row.getLong("since")));
        return friends;
    }

    public boolean areFriends(UUID a, UUID b) {
        return database.getFirst(FRIENDS, Map.of("uuid", a, "friend", b)).isPresent();
    }

    public int count(UUID player) {
        return database.query("SELECT COUNT(*) AS total FROM " + database.table(FRIENDS) + " WHERE uuid = ?", player)
                .getFirst().getInt("total");
    }

    /** Amitié dans les deux sens ; la demande (dans un sens ou l'autre) disparaît. */
    public void befriend(UUID a, UUID b) {
        long now = System.currentTimeMillis();
        String insert = "INSERT IGNORE INTO " + database.table(FRIENDS) + " (uuid, friend, since) VALUES (?, ?, ?)";
        database.execute(insert, a, b, now);
        database.execute(insert, b, a, now);
        database.delete(REQUESTS, Map.of("sender", a, "target", b));
        database.delete(REQUESTS, Map.of("sender", b, "target", a));
    }

    /** true si une amitié existait. */
    public boolean unfriend(UUID a, UUID b) {
        int removed = database.delete(FRIENDS, Map.of("uuid", a, "friend", b));
        database.delete(FRIENDS, Map.of("uuid", b, "friend", a));
        return removed > 0;
    }

    /** Demande enregistrée ; false si elle existait déjà. */
    public boolean request(UUID sender, UUID target) {
        return database.execute("INSERT IGNORE INTO " + database.table(REQUESTS) + " (sender, target, sent_at) VALUES (?, ?, ?)",
                sender, target, System.currentTimeMillis()) > 0;
    }

    public boolean hasRequest(UUID sender, UUID target) {
        return database.getFirst(REQUESTS, Map.of("sender", sender, "target", target)).isPresent();
    }

    /** true si la demande existait (et a été retirée). */
    public boolean removeRequest(UUID sender, UUID target) {
        return database.delete(REQUESTS, Map.of("sender", sender, "target", target)) > 0;
    }

    /** Demandes reçues : envoyeur -> date. */
    public Map<UUID, Long> incoming(UUID target) {
        Map<UUID, Long> requests = new LinkedHashMap<>();
        database.query("SELECT sender, sent_at FROM " + database.table(REQUESTS) + " WHERE target = ? ORDER BY sent_at", target)
                .forEach(row -> requests.put(row.getUUID("sender"), row.getLong("sent_at")));
        return requests;
    }

    public Set<UUID> outgoing(UUID sender) {
        Set<UUID> targets = new LinkedHashSet<>();
        database.get(REQUESTS, Map.of("sender", sender)).forEach(row -> targets.add(row.getUUID("target")));
        return targets;
    }

    /** Les demandes de plus de days jours disparaissent. */
    public void purgeRequests(int days) {
        database.execute("DELETE FROM " + database.table(REQUESTS) + " WHERE sent_at < ?", System.currentTimeMillis() - days * 86_400_000L);
    }

    public Settings settings(UUID player) {
        return database.getFirst(SETTINGS, Map.of("uuid", player))
                .map(row -> new Settings(row.getBoolean("requests"), row.getBoolean("notify"))).orElse(Settings.DEFAULT);
    }

    public void saveSettings(UUID player, Settings settings) {
        database.set(SETTINGS, Map.of("uuid", player, "requests", settings.requests(), "notify", settings.announce()), "uuid");
    }

    static UUID uuid(Row row, String column) {
        return row.getUUID(column);
    }
}
