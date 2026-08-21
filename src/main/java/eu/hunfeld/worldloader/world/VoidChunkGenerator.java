package eu.hunfeld.worldloader.world;

import org.bukkit.generator.ChunkGenerator;

final class VoidChunkGenerator extends ChunkGenerator {

    static final VoidChunkGenerator INSTANCE = new VoidChunkGenerator();

    private VoidChunkGenerator() {
    }
}
