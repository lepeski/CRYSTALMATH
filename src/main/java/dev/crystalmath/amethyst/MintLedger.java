package dev.crystalmath.amethyst;

import org.bukkit.Location;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.sql.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public class MintLedger {
    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_HELD = "HELD";
    public static final String STATUS_LOST = "LOST";
    public static final String STATUS_REDEEMED = "REDEEMED";
    public static final String EVENT_REDEEMED = "REDEEMED";
    public static final String EVENT_CRAFT_BEACON = "CRAFT_BEACON";
    public static final String EVENT_VOID_LOSS = "VOID_LOSS";

    private final JavaPlugin plugin;
    private Connection connection;

    public MintLedger(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public synchronized void initialize() {
        if (connection != null) {
            return;
        }

        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException exception) {
            throw new LedgerException("SQLite JDBC driver not found", exception);
        }

        File dataFolder = plugin.getDataFolder();
        if (!dataFolder.exists() && !dataFolder.mkdirs()) {
            throw new LedgerException("Unable to create plugin data folder at " + dataFolder.getAbsolutePath());
        }

        File dbFile = new File(dataFolder, "ledger.db");

        try {
            connection = DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath());
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("""
                        CREATE TABLE IF NOT EXISTS crystals (
                            uuid TEXT PRIMARY KEY,
                            area_id TEXT,
                            world TEXT,
                            x INTEGER,
                            y INTEGER,
                            z INTEGER,
                            status TEXT NOT NULL,
                            created_at INTEGER NOT NULL,
                            updated_at INTEGER NOT NULL
                        )
                        """);
                statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_crystals_location ON crystals(world, x, y, z)");
                statement.executeUpdate("UPDATE crystals SET status = 'ACTIVE' WHERE status = 'active'");
                statement.executeUpdate("UPDATE crystals SET status = 'REDEEMED' WHERE status = 'closed'");
                statement.executeUpdate("""
                        CREATE TABLE IF NOT EXISTS crystal_events (
                            id INTEGER PRIMARY KEY AUTOINCREMENT,
                            crystal_uuid TEXT NOT NULL,
                            event_type TEXT NOT NULL,
                            details TEXT,
                            occurred_at INTEGER NOT NULL,
                            FOREIGN KEY (crystal_uuid) REFERENCES crystals(uuid) ON DELETE CASCADE
                        )
                        """);
                statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_crystal_events_crystal ON crystal_events(crystal_uuid)");
                statement.executeUpdate("""
                        CREATE TABLE IF NOT EXISTS offline_crystals (
                            crystal_uuid TEXT PRIMARY KEY,
                            player_uuid TEXT NOT NULL,
                            player_name TEXT,
                            details TEXT,
                            recorded_at INTEGER NOT NULL,
                            FOREIGN KEY (crystal_uuid) REFERENCES crystals(uuid) ON DELETE CASCADE
                        )
                        """);
                statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_offline_crystals_player ON offline_crystals(player_uuid)");
                statement.executeUpdate("""
                        CREATE TABLE IF NOT EXISTS areas (
                            id TEXT PRIMARY KEY,
                            world TEXT NOT NULL,
                            target_crystals INTEGER NOT NULL DEFAULT 0
                        )
                        """);
                statement.executeUpdate("""
                        CREATE TABLE IF NOT EXISTS area_chunks (
                            area_id TEXT NOT NULL,
                            chunk_x INTEGER NOT NULL,
                            chunk_z INTEGER NOT NULL,
                            PRIMARY KEY (area_id, chunk_x, chunk_z),
                            FOREIGN KEY (area_id) REFERENCES areas(id) ON DELETE CASCADE
                        )
                        """);
                statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_area_chunks_area ON area_chunks(area_id)");
            }
        } catch (SQLException exception) {
            throw new LedgerException("Unable to initialize the ledger database", exception);
        }
    }

    public synchronized boolean areaExists(String id) {
        ensureConnection();

        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT 1
                FROM areas
                WHERE id = ?
                LIMIT 1
                """)) {
            statement.setString(1, id);

            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next();
            }
        } catch (SQLException exception) {
            throw new LedgerException("Unable to query existing areas", exception);
        }
    }

    public synchronized void createArea(String id, String world, int targetCrystals, Set<ChunkCoordinate> chunkCoordinates) {
        ensureConnection();

        if (chunkCoordinates == null || chunkCoordinates.isEmpty()) {
            throw new LedgerException("Cannot create an area without any chunks");
        }

        boolean previousAutoCommit;
        try {
            previousAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
        } catch (SQLException exception) {
            throw new LedgerException("Unable to configure database transaction for area creation", exception);
        }

        try (PreparedStatement insertArea = connection.prepareStatement("""
                INSERT INTO areas (id, world, target_crystals)
                VALUES (?, ?, ?)
                """)) {
            insertArea.setString(1, id);
            insertArea.setString(2, world);
            insertArea.setInt(3, targetCrystals);
            insertArea.executeUpdate();

            try (PreparedStatement insertChunk = connection.prepareStatement("""
                    INSERT INTO area_chunks (area_id, chunk_x, chunk_z)
                    VALUES (?, ?, ?)
                    """)) {
                for (ChunkCoordinate coordinate : chunkCoordinates) {
                    insertChunk.setString(1, id);
                    insertChunk.setInt(2, coordinate.x());
                    insertChunk.setInt(3, coordinate.z());
                    insertChunk.addBatch();
                }
                insertChunk.executeBatch();
            }

            connection.commit();
        } catch (SQLException exception) {
            try {
                connection.rollback();
            } catch (SQLException rollbackException) {
                plugin.getLogger().warning("Failed to roll back area creation: " + rollbackException.getMessage());
            }
            throw new LedgerException("Unable to create ledger area", exception);
        } finally {
            try {
                connection.setAutoCommit(previousAutoCommit);
            } catch (SQLException exception) {
                plugin.getLogger().warning("Failed to restore auto-commit state: " + exception.getMessage());
            }
        }
    }

    public synchronized List<AreaRecord> listAreas() {
        ensureConnection();

        Map<String, AreaRecordBuilder> builders = new LinkedHashMap<>();

        try (PreparedStatement areaStatement = connection.prepareStatement("""
                SELECT id, world, target_crystals
                FROM areas
                """)) {
            try (ResultSet resultSet = areaStatement.executeQuery()) {
                while (resultSet.next()) {
                    String id = resultSet.getString("id");
                    String world = resultSet.getString("world");
                    int targetCrystals = resultSet.getInt("target_crystals");
                    builders.put(id, new AreaRecordBuilder(id, world, targetCrystals));
                }
            }
        } catch (SQLException exception) {
            throw new LedgerException("Unable to list areas", exception);
        }

        if (builders.isEmpty()) {
            return List.of();
        }

        try (PreparedStatement chunkStatement = connection.prepareStatement("""
                SELECT area_id, chunk_x, chunk_z
                FROM area_chunks
                WHERE area_id = ?
                """)) {
            for (AreaRecordBuilder builder : builders.values()) {
                chunkStatement.setString(1, builder.id);
                try (ResultSet resultSet = chunkStatement.executeQuery()) {
                    while (resultSet.next()) {
                        int chunkX = resultSet.getInt("chunk_x");
                        int chunkZ = resultSet.getInt("chunk_z");
                        builder.addChunk(new ChunkCoordinate(chunkX, chunkZ));
                    }
                }
            }
        } catch (SQLException exception) {
            throw new LedgerException("Unable to load area chunks", exception);
        }

        List<AreaRecord> results = new ArrayList<>();
        for (AreaRecordBuilder builder : builders.values()) {
            results.add(builder.build());
        }

        return results;
    }

    public synchronized UUID recordMint(String areaId, Location location) {
        ensureConnection();

        UUID uuid = UUID.randomUUID();
        long now = Instant.now().getEpochSecond();

        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO crystals (uuid, area_id, world, x, y, z, status, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            statement.setString(1, uuid.toString());
            statement.setString(2, areaId);
            statement.setString(3, location.getWorld().getName());
            statement.setInt(4, location.getBlockX());
            statement.setInt(5, location.getBlockY());
            statement.setInt(6, location.getBlockZ());
            statement.setString(7, STATUS_ACTIVE);
            statement.setLong(8, now);
            statement.setLong(9, now);
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw new LedgerException("Unable to record minted crystal", exception);
        }

        return uuid;
    }

    public synchronized Optional<LedgerEntry> findActiveByLocation(Location location) {
        ensureConnection();

        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT uuid, area_id, status, world, x, y, z
                FROM crystals
                WHERE status = ? AND world = ? AND x = ? AND y = ? AND z = ?
                LIMIT 1
                """)) {
            statement.setString(1, STATUS_ACTIVE);
            statement.setString(2, location.getWorld().getName());
            statement.setInt(3, location.getBlockX());
            statement.setInt(4, location.getBlockY());
            statement.setInt(5, location.getBlockZ());

            try (ResultSet resultSet = statement.executeQuery()) {
                if (resultSet.next()) {
                    return Optional.of(mapRow(resultSet));
                }
            }
        } catch (SQLException exception) {
            throw new LedgerException("Unable to query ledger by location", exception);
        }

        return Optional.empty();
    }

    public synchronized Optional<LedgerEntry> findByUuid(UUID uuid) {
        ensureConnection();

        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT uuid, area_id, status, world, x, y, z
                FROM crystals
                WHERE uuid = ?
                LIMIT 1
                """)) {
            statement.setString(1, uuid.toString());

            try (ResultSet resultSet = statement.executeQuery()) {
                if (resultSet.next()) {
                    return Optional.of(mapRow(resultSet));
                }
            }
        } catch (SQLException exception) {
            throw new LedgerException("Unable to query ledger by UUID", exception);
        }

        return Optional.empty();
    }

    public synchronized boolean markHeld(UUID uuid) {
        return updateStatus(uuid, STATUS_HELD, null, STATUS_ACTIVE);
    }

    public synchronized boolean markLost(UUID uuid) {
        return markLost(uuid, null);
    }

    public synchronized boolean markLost(UUID uuid, Location location) {
        boolean updated = updateStatus(uuid, STATUS_LOST, location, STATUS_ACTIVE, STATUS_HELD);
        if (updated) {
            clearOfflineHolding(uuid);
        }
        return updated;
    }

    public synchronized boolean markLostWithEvent(UUID uuid, Location location, String eventType, String details) {
        boolean updated = markLost(uuid, location);
        if (updated && eventType != null) {
            recordEvent(uuid, eventType, details);
        }
        return updated;
    }

    public synchronized boolean markRedeemed(UUID uuid) {
        return markRedeemed(uuid, EVENT_REDEEMED, null);
    }

    public synchronized boolean markRedeemed(UUID uuid, String eventType, String details) {
        boolean updated = updateStatus(uuid, STATUS_REDEEMED, null, STATUS_HELD);
        if (updated) {
            clearOfflineHolding(uuid);
            recordEvent(uuid, eventType == null ? EVENT_REDEEMED : eventType, details);
        }
        return updated;
    }

    public synchronized SupplySnapshot countByStatus() {
        ensureConnection();

        EnumMap<CrystalStatus, Integer> counts = new EnumMap<>(CrystalStatus.class);
        for (CrystalStatus status : CrystalStatus.values()) {
            counts.put(status, 0);
        }

        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT status, COUNT(*) AS total
                FROM crystals
                GROUP BY status
                """)) {
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    String status = resultSet.getString("status");
                    int total = resultSet.getInt("total");
                    if (status != null) {
                        try {
                            CrystalStatus crystalStatus = CrystalStatus.valueOf(status.toUpperCase(Locale.ROOT));
                            counts.put(crystalStatus, total);
                        } catch (IllegalArgumentException ignored) {
                            plugin.getLogger().warning("Unknown crystal status in ledger: " + status);
                        }
                    }
                }
            }
        } catch (SQLException exception) {
            throw new LedgerException("Unable to count crystals by status", exception);
        }

        return new SupplySnapshot(
                counts.get(CrystalStatus.ACTIVE),
                counts.get(CrystalStatus.HELD),
                counts.get(CrystalStatus.LOST),
                counts.get(CrystalStatus.REDEEMED)
        );
    }

    public synchronized List<LedgerEntry> listEntriesByStatus(String... statuses) {
        ensureConnection();

        if (statuses == null || statuses.length == 0) {
            throw new LedgerException("At least one status must be provided");
        }

        StringBuilder placeholders = new StringBuilder();
        for (int i = 0; i < statuses.length; i++) {
            if (i > 0) {
                placeholders.append(", ");
            }
            placeholders.append("?");
        }

        String sql = """
                SELECT uuid, area_id, status, world, x, y, z
                FROM crystals
                WHERE status IN (%s)
                """.formatted(placeholders);

        List<LedgerEntry> entries = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < statuses.length; i++) {
                statement.setString(i + 1, statuses[i]);
            }

            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    entries.add(mapRow(resultSet));
                }
            }
        } catch (SQLException exception) {
            throw new LedgerException("Unable to list ledger entries by status", exception);
        }

        return entries;
    }

    private boolean updateStatus(UUID uuid, String newStatus, Location location, String... allowedStatuses) {
        ensureConnection();

        long now = Instant.now().getEpochSecond();

        StringBuilder sql = new StringBuilder("""
                UPDATE crystals
                SET status = ?, updated_at = ?, world = ?, x = ?, y = ?, z = ?
                WHERE uuid = ?
                """);

        if (allowedStatuses != null && allowedStatuses.length > 0) {
            sql.append(" AND status IN (");
            for (int i = 0; i < allowedStatuses.length; i++) {
                if (i > 0) {
                    sql.append(", ");
                }
                sql.append("?");
            }
            sql.append(")");
        }

        try (PreparedStatement statement = connection.prepareStatement(sql.toString())) {
            int index = 1;
            statement.setString(index++, newStatus);
            statement.setLong(index++, now);

            if (location != null) {
                statement.setString(index++, location.getWorld().getName());
                statement.setInt(index++, location.getBlockX());
                statement.setInt(index++, location.getBlockY());
                statement.setInt(index++, location.getBlockZ());
            } else {
                statement.setNull(index++, Types.VARCHAR);
                statement.setNull(index++, Types.INTEGER);
                statement.setNull(index++, Types.INTEGER);
                statement.setNull(index++, Types.INTEGER);
            }

            statement.setString(index++, uuid.toString());

            if (allowedStatuses != null && allowedStatuses.length > 0) {
                for (String allowedStatus : allowedStatuses) {
                    statement.setString(index++, allowedStatus);
                }
            }

            return statement.executeUpdate() > 0;
        } catch (SQLException exception) {
            throw new LedgerException("Unable to update ledger entry status", exception);
        }
    }

    public synchronized void replaceOfflineHoldings(UUID playerUuid, String playerName, Map<UUID, List<String>> contexts) {
        ensureConnection();

        boolean previousAutoCommit;
        try {
            previousAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
        } catch (SQLException exception) {
            throw new LedgerException("Unable to configure database transaction for offline holdings", exception);
        }

        long now = Instant.now().getEpochSecond();

        try (PreparedStatement delete = connection.prepareStatement("DELETE FROM offline_crystals WHERE player_uuid = ?")) {
            delete.setString(1, playerUuid.toString());
            delete.executeUpdate();

            if (contexts != null && !contexts.isEmpty()) {
                try (PreparedStatement insert = connection.prepareStatement("""
                        INSERT INTO offline_crystals (crystal_uuid, player_uuid, player_name, details, recorded_at)
                        VALUES (?, ?, ?, ?, ?)
                        """)) {
                    for (Map.Entry<UUID, List<String>> entry : contexts.entrySet()) {
                        insert.setString(1, entry.getKey().toString());
                        insert.setString(2, playerUuid.toString());
                        if (playerName == null) {
                            insert.setNull(3, Types.VARCHAR);
                        } else {
                            insert.setString(3, playerName);
                        }

                        String details = joinDetails(entry.getValue());
                        if (details == null) {
                            insert.setNull(4, Types.VARCHAR);
                        } else {
                            insert.setString(4, details);
                        }

                        insert.setLong(5, now);
                        insert.addBatch();
                    }

                    insert.executeBatch();
                }
            }

            connection.commit();
        } catch (SQLException exception) {
            try {
                connection.rollback();
            } catch (SQLException rollbackException) {
                plugin.getLogger().warning("Failed to roll back offline holdings update: " + rollbackException.getMessage());
            }
            throw new LedgerException("Unable to update offline crystal holdings", exception);
        } finally {
            try {
                connection.setAutoCommit(previousAutoCommit);
            } catch (SQLException exception) {
                plugin.getLogger().warning("Failed to restore auto-commit state: " + exception.getMessage());
            }
        }
    }

    public synchronized void clearOfflineHoldings(UUID playerUuid) {
        ensureConnection();

        try (PreparedStatement statement = connection.prepareStatement("DELETE FROM offline_crystals WHERE player_uuid = ?")) {
            statement.setString(1, playerUuid.toString());
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw new LedgerException("Unable to clear offline holdings for player", exception);
        }
    }

    public synchronized List<OfflineHolding> listOfflineHoldings() {
        ensureConnection();

        List<OfflineHolding> results = new ArrayList<>();

        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT crystal_uuid, player_uuid, player_name, details
                FROM offline_crystals
                """)) {
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    UUID crystalUuid = UUID.fromString(resultSet.getString("crystal_uuid"));
                    String playerUuid = resultSet.getString("player_uuid");
                    UUID holderUuid = playerUuid == null ? null : UUID.fromString(playerUuid);
                    String playerName = resultSet.getString("player_name");
                    String details = resultSet.getString("details");
                    results.add(new OfflineHolding(crystalUuid, holderUuid, playerName, details));
                }
            }
        } catch (SQLException exception) {
            throw new LedgerException("Unable to list offline crystal holdings", exception);
        }

        return results;
    }

    private void clearOfflineHolding(UUID uuid) {
        ensureConnection();

        try (PreparedStatement statement = connection.prepareStatement("DELETE FROM offline_crystals WHERE crystal_uuid = ?")) {
            statement.setString(1, uuid.toString());
            statement.executeUpdate();
        } catch (SQLException exception) {
            plugin.getLogger().warning("Failed to clear offline record for crystal " + uuid + ": " + exception.getMessage());
        }
    }

    private String joinDetails(List<String> details) {
        if (details == null || details.isEmpty()) {
            return null;
        }

        return String.join("; ", details);
    }

    private void recordEvent(UUID uuid, String eventType, String details) {
        ensureConnection();

        long now = Instant.now().getEpochSecond();

        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO crystal_events (crystal_uuid, event_type, details, occurred_at)
                VALUES (?, ?, ?, ?)
                """)) {
            statement.setString(1, uuid.toString());
            statement.setString(2, eventType);
            if (details == null) {
                statement.setNull(3, Types.VARCHAR);
            } else {
                statement.setString(3, details);
            }
            statement.setLong(4, now);
            statement.executeUpdate();
        } catch (SQLException exception) {
            plugin.getLogger().warning("Failed to log ledger event for crystal " + uuid + ": " + exception.getMessage());
        }
    }

    public synchronized void close() {
        if (connection != null) {
            try {
                connection.close();
            } catch (SQLException exception) {
                plugin.getLogger().warning("Failed to close ledger connection: " + exception.getMessage());
            } finally {
                connection = null;
            }
        }
    }

    private void ensureConnection() {
        if (connection == null) {
            throw new LedgerException("Ledger has not been initialised");
        }
    }

    private static LedgerEntry mapRow(ResultSet resultSet) throws SQLException {
        UUID uuid = UUID.fromString(resultSet.getString("uuid"));
        String areaId = resultSet.getString("area_id");
        String status = resultSet.getString("status");
        String world = resultSet.getString("world");
        Integer x = getNullableInteger(resultSet, "x");
        Integer y = getNullableInteger(resultSet, "y");
        Integer z = getNullableInteger(resultSet, "z");
        return new LedgerEntry(uuid, areaId, status == null ? null : status.toUpperCase(Locale.ROOT), world, x, y, z);
    }

    private static Integer getNullableInteger(ResultSet resultSet, String column) throws SQLException {
        int value = resultSet.getInt(column);
        return resultSet.wasNull() ? null : value;
    }

    public record LedgerEntry(UUID uuid, String areaId, String status, String world, Integer x, Integer y, Integer z) {
        public boolean isActive() {
            return STATUS_ACTIVE.equals(status);
        }

        public boolean isHeld() {
            return STATUS_HELD.equals(status);
        }

        public boolean isRedeemed() {
            return STATUS_REDEEMED.equals(status);
        }
    }

    public record SupplySnapshot(int active, int held, int lost, int redeemed) {
        public int total() {
            return active + held + lost + redeemed;
        }
    }

    public record OfflineHolding(UUID crystalUuid, UUID playerUuid, String playerName, String details) {
    }

    public enum CrystalStatus {
        ACTIVE,
        HELD,
        LOST,
        REDEEMED
    }

    public record AreaRecord(String id, String world, int targetCrystals, List<ChunkCoordinate> chunkCoordinates) {
    }

    public record ChunkCoordinate(int x, int z) {
    }

    public static class LedgerException extends RuntimeException {
        public LedgerException(String message) {
            super(message);
        }

        public LedgerException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private static final class AreaRecordBuilder {
        private final String id;
        private final String world;
        private final int targetCrystals;
        private final Set<ChunkCoordinate> chunkCoordinates = new HashSet<>();

        private AreaRecordBuilder(String id, String world, int targetCrystals) {
            this.id = id;
            this.world = world;
            this.targetCrystals = targetCrystals;
        }

        private void addChunk(ChunkCoordinate coordinate) {
            chunkCoordinates.add(coordinate);
        }

        private AreaRecord build() {
            return new AreaRecord(id, world, targetCrystals, new ArrayList<>(chunkCoordinates));
        }
    }
}
