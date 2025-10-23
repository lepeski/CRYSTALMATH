package dev.crystalmath.claims;

public enum BeaconTier {
    NONE(0, false, false, "Inactive"),
    BASIC(0, true, false, "Basic"),
    IRON_ONE(1, true, true, "Iron Tier I"),
    IRON_TWO(2, true, true, "Iron Tier II"),
    IRON_THREE(3, true, true, "Iron Tier III"),
    IRON_FOUR(4, true, true, "Iron Tier IV");

    private final int ironLayers;
    private final boolean blockProtection;
    private final boolean playerProtection;
    private final String displayName;

    BeaconTier(int ironLayers, boolean blockProtection, boolean playerProtection, String displayName) {
        this.ironLayers = ironLayers;
        this.blockProtection = blockProtection;
        this.playerProtection = playerProtection;
        this.displayName = displayName;
    }

    public static BeaconTier fromIronLayers(int layers) {
        int sanitized = Math.max(0, Math.min(4, layers));
        return switch (sanitized) {
            case 0 -> BASIC;
            case 1 -> IRON_ONE;
            case 2 -> IRON_TWO;
            case 3 -> IRON_THREE;
            default -> IRON_FOUR;
        };
    }

    public boolean protectsBlocks() {
        return blockProtection;
    }

    public boolean protectsPlayers() {
        return playerProtection;
    }

    public boolean hasAura() {
        return ironLayers > 0;
    }

    public int getAuraAmplifier() {
        return Math.max(0, ironLayers - 1);
    }

    public String getDisplayName() {
        return displayName;
    }
}
