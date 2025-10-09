package bout2p1_ograines.chunksloader.map;

import java.util.Locale;

public record LoaderData(
    String id,
    String worldName,
    int blockX,
    int blockY,
    int blockZ,
    int chunkX,
    int chunkZ,
    int radius,
    int chunkCount,
    boolean active,
    String displayName,
    String plainDisplayName,
    String ownerName,
    long totalActiveMillis
) {

    private static final double CHUNK_SIZE = 16.0d;

    public double minX() {
        return (chunkX - radius) * CHUNK_SIZE;
    }

    public double minZ() {
        return (chunkZ - radius) * CHUNK_SIZE;
    }

    public double maxX() {
        return (chunkX + radius + 1) * CHUNK_SIZE;
    }

    public double maxZ() {
        return (chunkZ + radius + 1) * CHUNK_SIZE;
    }

    public String statusLabel() {
        return active ? "Actif" : "Inactif";
    }

    public String ownerLabel() {
        return ownerName == null || ownerName.isBlank() ? "Inconnu" : ownerName;
    }

    public String formatDuration(Locale locale) {
        long seconds = Math.max(0L, totalActiveMillis / 1000L);
        long minutes = seconds / 60L;
        long hours = minutes / 60L;
        long days = hours / 24L;
        seconds %= 60L;
        minutes %= 60L;
        hours %= 24L;
        StringBuilder builder = new StringBuilder();
        if (days > 0) {
            builder.append(days).append('j').append(' ');
        }
        if (hours > 0 || builder.length() > 0) {
            builder.append(hours).append('h').append(' ');
        }
        if (minutes > 0 || builder.length() > 0) {
            builder.append(minutes).append('m').append(' ');
        }
        builder.append(seconds).append('s');
        return builder.toString().trim();
    }
}
