package dev.aurelium.auraskills.bukkit.region;

import dev.aurelium.auraskills.api.source.SkillSource;
import dev.aurelium.auraskills.api.source.type.BlockXpSource;
import dev.aurelium.auraskills.bukkit.AuraSkills;
import dev.aurelium.auraskills.bukkit.hooks.WorldGuardHook;
import dev.aurelium.auraskills.bukkit.source.BlockLeveler;
import dev.aurelium.auraskills.bukkit.util.BlockFaceUtil;
import dev.aurelium.auraskills.common.config.Option;
import dev.aurelium.auraskills.common.scheduler.Task;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.entity.Entity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockFertilizeEvent;
import org.bukkit.event.block.BlockGrowEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockSpreadEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.world.StructureGrowEvent;

import java.util.EnumSet;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class RegionBlockListener implements Listener {

    // Plants that grow as a column and pop when the block below them is removed.
    // BlockLevelerHelper counts these upwards and stops at the first placed block,
    // so a stale placed mark inside a column cuts the XP of every block above it.
    private static final EnumSet<Material> TALL_PLANTS = EnumSet.of(
            Material.SUGAR_CANE, Material.BAMBOO, Material.BAMBOO_SAPLING, Material.CACTUS,
            Material.KELP, Material.KELP_PLANT);

    private final AuraSkills plugin;
    private final BukkitRegionManager regionManager;
    private final BlockLeveler blockLeveler;

    public RegionBlockListener(AuraSkills plugin) {
        this.plugin = plugin;
        this.regionManager = plugin.getRegionManager();
        this.blockLeveler = plugin.getLevelManager().getLeveler(BlockLeveler.class);
    }

    @EventHandler
    public void checkPlace(BlockPlaceEvent event) {
        // Checks if world is blocked
        if (plugin.getWorldManager().isCheckReplaceDisabled(event.getBlock().getLocation())) {
            return;
        }
        // Checks if region is blocked
        if (plugin.getHookManager().isRegistered(WorldGuardHook.class)) {
            if (plugin.getHookManager().getHook(WorldGuardHook.class).isInBlockedCheckRegion(event.getBlock().getLocation())) {
                return;
            }
        }
        if (!plugin.configBoolean(Option.CHECK_BLOCK_REPLACE_ENABLED)) return;

        Block block = event.getBlock();
        regionManager.handleBlockPlace(block);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSandFall(EntityChangeBlockEvent event) {
        Block block = event.getBlock();
        if (!regionManager.isPlacedBlock(block)) return;
        Material type = block.getType();
        if (type == Material.SAND || type == Material.RED_SAND || type == Material.GRAVEL) {
            Block below = block.getRelative(BlockFace.DOWN);
            if (below.getType() == Material.AIR || below.getType() == Material.CAVE_AIR || below.getType() == Material.VOID_AIR
                    || below.getType() == Material.WATER || below.getType() == Material.BUBBLE_COLUMN || below.getType() == Material.LAVA) {
                regionManager.removePlacedBlock(block);
                Entity entity = event.getEntity();
                AtomicInteger counter = new AtomicInteger();

                final Task[] taskHolder = new Task[1];

                taskHolder[0] = plugin.getScheduler().timerAtEntity(entity, () -> {
                    Block currentBlock = entity.getLocation().getBlock();
                    if (entity.isDead() || !entity.isValid()) {
                        if (currentBlock.getType() == type) {
                            regionManager.addPlacedBlock(entity.getLocation().getBlock());
                        }
                        taskHolder[0].cancel();
                    } else if (currentBlock.getType().toString().contains("WEB")) {
                        taskHolder[0].cancel();
                    } else if (counter.incrementAndGet() >= 200) {
                        taskHolder[0].cancel();
                    }
                }, 50, 50, TimeUnit.MILLISECONDS);
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void checkBreak(BlockBreakEvent event) {
        if (event.isCancelled()) return;
        Block block = event.getBlock();
        regionManager.removePlacedBlock(block);
        clearPlantColumnAbove(block);
        checkSupportBelow(block);
        checkSupportSide(block);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockPistonExtend(BlockPistonExtendEvent event) {
        for (Block block : event.getBlocks()) {
            regionManager.addPlacedBlock(block.getRelative(event.getDirection()));
        }
        regionManager.removePlacedBlock(event.getBlock().getRelative(event.getDirection()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockPistonRetract(BlockPistonRetractEvent event) {
        Block lastBlock = event.getBlock();
        for (Block block : event.getBlocks()) {
            if (regionManager.isPlacedBlock(block)) {
                regionManager.addPlacedBlock(block.getRelative(event.getDirection()));
                if (block.getLocation().distanceSquared(event.getBlock().getLocation()) > lastBlock.getLocation().distanceSquared(event.getBlock().getLocation())) {
                    lastBlock = block;
                }
            }
        }
        regionManager.removePlacedBlock(lastBlock);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onStructureGrow(StructureGrowEvent event) {
        int growY = event.getLocation().getBlockY();
        for (BlockState state : event.getBlocks()) {
            // Only remove placed blocks at same y level as sapling
            if (state.getLocation().getY() != growY) continue;

            regionManager.removePlacedBlock(state.getBlock());
        }
    }

    // A tall plant that grows into a spot was never placed there, so any mark left on that spot
    // is stale (the block placed there earlier was removed without a player breaking it).
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlantGrow(BlockGrowEvent event) {
        clearIfGrownInto(event.getBlock(), event.getNewState().getType());
    }

    // Kelp and bamboo grow through BlockSpreadEvent instead of BlockGrowEvent
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlantSpread(BlockSpreadEvent event) {
        clearIfGrownInto(event.getBlock(), event.getNewState().getType());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlantFertilize(BlockFertilizeEvent event) {
        for (BlockState state : event.getBlocks()) {
            clearIfGrownInto(state.getBlock(), state.getType());
        }
    }

    private void clearIfGrownInto(Block block, Material newType) {
        if (!TALL_PLANTS.contains(newType)) return;
        // Only new blocks: a plant that is already there (like a placed bamboo base whose
        // leaves change when bone mealed) keeps its mark
        if (TALL_PLANTS.contains(block.getType())) return;
        if (regionManager.isPlacedBlock(block)) {
            regionManager.removePlacedBlock(block);
        }
    }

    /**
     * Clears the placed marks of the whole plant column above a broken block, since every block
     * of it pops with the broken one. The walk does not stop at blocks without a mark: a column
     * often mixes grown and placed blocks (a placed base, or kelp placed on top of grown kelp),
     * and stopping at the first grown block left the marks above it on spots that are now empty,
     * where they cut the count of the next column that grows through them.
     *
     * @param block The broken block
     */
    private void clearPlantColumnAbove(Block block) {
        Block above = block.getRelative(BlockFace.UP);
        Material type = above.getType();
        if (!TALL_PLANTS.contains(type)) return;
        boolean kelp = type == Material.KELP || type == Material.KELP_PLANT;
        int maxY = block.getWorld().getMaxHeight();
        while (above.getY() < maxY && isSamePlant(above.getType(), type, kelp)) {
            regionManager.removePlacedBlock(above);
            above = above.getRelative(BlockFace.UP);
        }
    }

    private boolean isSamePlant(Material current, Material first, boolean kelp) {
        if (kelp) {
            return current == Material.KELP || current == Material.KELP_PLANT;
        }
        return current == first;
    }

    private void checkSupportBelow(Block block) {
        // Check if the block above requires support
        Block above = block.getRelative(BlockFace.UP);
        SkillSource<BlockXpSource> skillSource = blockLeveler.getSource(above, BlockXpSource.BlockTriggers.BREAK);
        BlockXpSource source = skillSource == null ? null : skillSource.source();
        if (source != null && source.requiresSupportBlock(BlockXpSource.SupportBlockType.BELOW) && regionManager.isPlacedBlock(above)) {
            plugin.getScheduler().scheduleAtLocation(above.getLocation(), () -> {
                // Remove if block was destroyed
                if (blockLeveler.isDifferentSource(above, source, BlockXpSource.BlockTriggers.BREAK)) {
                    regionManager.removePlacedBlock(above);
                }
            }, 50, TimeUnit.MILLISECONDS);
        }
    }

    private void checkSupportSide(Block block) {
        // Check each side
        for (BlockFace face : BlockFaceUtil.getBlockSides()) {
            Block checkedBlock = block.getRelative(face);
            SkillSource<BlockXpSource> skillSource = blockLeveler.getSource(checkedBlock, BlockXpSource.BlockTriggers.BREAK);
            BlockXpSource source = skillSource == null ? null : skillSource.source();
            if (source != null && source.requiresSupportBlock(BlockXpSource.SupportBlockType.SIDE) && regionManager.isPlacedBlock(checkedBlock)) {
                plugin.getScheduler().scheduleAtLocation(checkedBlock.getLocation(), () -> {
                    // Remove if block was destroyed
                    if (blockLeveler.isDifferentSource(checkedBlock, source, BlockXpSource.BlockTriggers.BREAK)) {
                        regionManager.removePlacedBlock(checkedBlock);
                    }
                }, 50, TimeUnit.MILLISECONDS);
            }
        }
    }

}
