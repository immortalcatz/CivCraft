package alexiil.mods.civ.utils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.UUID;

import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.InventoryCrafting;
import net.minecraft.item.ItemStack;
import net.minecraft.item.crafting.CraftingManager;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.NBTTagString;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.BlockPos;
import net.minecraft.world.ChunkCoordIntPair;
import net.minecraft.world.World;
import alexiil.mods.civ.CivLog;
import alexiil.mods.civ.Lib;
import alexiil.mods.civ.tech.TechTree;
import alexiil.mods.civ.tech.TechTree.Tech;
import alexiil.mods.civ.tech.Unlockable;
import alexiil.mods.civ.tech.unlock.IItemBlocker;

public class CraftUtils {
    // Ignore different dimensions for some reason... for now

    private static Map<Integer, Map<ChunkCoordIntPair, List<PlayerTechData>>> levelPlayers =
            new HashMap<Integer, Map<ChunkCoordIntPair, List<PlayerTechData>>>();

    private static Map<ChunkCoordIntPair, List<PlayerTechData>> getMapForWorld(World world) {
        int dimId = world.provider.getDimensionId();
        Map<ChunkCoordIntPair, List<PlayerTechData>> map = null;
        if (!levelPlayers.containsKey(dimId) || levelPlayers.get(dimId) == null) {
            map = new HashMap<ChunkCoordIntPair, List<PlayerTechData>>();
            levelPlayers.put(dimId, map);
            return map;
        }
        return levelPlayers.get(dimId);
    }

    private static List<PlayerTechData> getPlayers(World world, ChunkCoordIntPair ccip) {
        Map<ChunkCoordIntPair, List<PlayerTechData>> map = getMapForWorld(world);
        if (!map.containsKey(ccip) || map.get(ccip) == null) {
            map.put(ccip, new ArrayList<PlayerTechData>());
        }
        return map.get(ccip);
    }

    public static void addPlayerToChunk(World world, BlockPos pos, EntityPlayer player) {
        if (pos == null || player == null)
            return;
        addPlayerToChunk(world, pos, PlayerTechData.createPlayerTechData(player));
    }

    public static void addPlayerToChunk(World world, BlockPos pos, PlayerTechData techs) {
        ChunkCoordIntPair ccip = new ChunkCoordIntPair(pos.getX() << 4, pos.getY() << 4);
        List<PlayerTechData> list = getPlayers(world, ccip);
        boolean found = false;
        for (PlayerTechData ptd : list) {
            if (ptd.id.equals(techs.id))
                found = true;
        }
        if (!found)
            list.add(techs);
    }

    public static List<PlayerTechData> getPlayers(World world, BlockPos pos) {
        return getPlayers(world, new ChunkCoordIntPair(pos.getX() << 4, pos.getZ() << 4));
    }

    public static List<Tech> getTechs(World world, BlockPos pos) {
        return getTechs(world, new ChunkCoordIntPair(pos.getX() << 4, pos.getY() << 4));
    }

    public static List<Tech> getTechs(World world, ChunkCoordIntPair ccip) {
        if (world.isRemote) // If its on the client, just get whatever the client has
            return TechUtils.getTechs(Minecraft.getMinecraft().thePlayer);
        List<PlayerTechData> players = getPlayers(world, ccip);
        List<Tech> techs = new ArrayList<Tech>();
        for (PlayerTechData data : players)
            for (Tech t : data.getTechs())
                if (!techs.contains(t))
                    techs.add(t);
        return techs;
    }

    public static NBTTagCompound save() {
        NBTTagCompound nbt = new NBTTagCompound();
        for (Entry<Integer, Map<ChunkCoordIntPair, List<PlayerTechData>>> entry : levelPlayers.entrySet()) {
            int dimId = entry.getKey();
            NBTTagCompound dim = new NBTTagCompound();
            for (Entry<ChunkCoordIntPair, List<PlayerTechData>> innerEntry : entry.getValue().entrySet()) {
                ChunkCoordIntPair ccip = innerEntry.getKey();
                NBTTagList chunk = new NBTTagList();
                for (PlayerTechData ptd : innerEntry.getValue())
                    chunk.appendTag(new NBTTagString(ptd.id.toString()));
                dim.setTag(ccip.chunkXPos + ":" + ccip.chunkZPos, chunk);
            }
            nbt.setTag(Integer.toString(dimId), dim);
        }
        return nbt;
    }

    public static void load(NBTTagCompound nbt) {
        levelPlayers.clear();

        for (Object obj : nbt.getKeySet()) {
            String key = (String) obj;
            int dimId = Integer.parseInt(key);
            Map<ChunkCoordIntPair, List<PlayerTechData>> map = new HashMap<ChunkCoordIntPair, List<PlayerTechData>>();
            NBTTagCompound chunks = nbt.getCompoundTag(key);
            for (Object obj1 : chunks.getKeySet()) {
                String chunkName = (String) obj1;
                NBTTagList tagList = chunks.getTagList(chunkName, Lib.NBT.STRING);
                String[] coords = chunkName.split(":");
                ChunkCoordIntPair ccip = new ChunkCoordIntPair(Integer.parseInt(coords[0]), Integer.parseInt(coords[1]));
                List<PlayerTechData> list = new ArrayList<PlayerTechData>();
                for (int i = 0; i < tagList.tagCount(); i++) {
                    String s = tagList.getStringTagAt(i);
                    UUID uuid = UUID.fromString(s);
                    list.add(PlayerTechData.createFromUUID(uuid));
                }
                map.put(ccip, list);
            }
            levelPlayers.put(dimId, map);
        }
    }

    /** @param in
     *            The IInventory to craft from (usually an instance of InventoryCrafting)
     * @param pos
     *            The position that this is at
     * @return The recipe that matches it */
    public static ItemStack findMatchingRecipe(InventoryCrafting in, World world, BlockPos pos) {
        for (int i = 0; i < in.getSizeInventory(); i++) {
            ItemStack item = in.getStackInSlot(i);
            if (!canUse(world, pos, item)) {
                CivLog.debugInfo("Could not use " + item + ", aborting");
                return null;
            }
        }
        ItemStack item = CraftingManager.getInstance().findMatchingRecipe(in, world);
        if (canUse(world, pos, item))
            return item;
        CivLog.debugInfo("Could not use " + item + ", aborting");
        return null;
    }

    public static boolean canCraft(ItemStack out, ItemStack[] in, TileEntity tile) {
        if (tile == null)
            return true;
        if (!canUse(tile.getWorld(), tile.getPos(), out))
            return false;
        for (ItemStack i : in)
            if (i != null)
                if (!canUse(tile.getWorld(), tile.getPos(), i))
                    return false;
        return true;
    }

    public static boolean canUse(ItemStack item, List<Tech> techs) {
        if (item == null)
            return true;
        for (Unlockable u : TechTree.currentTree.getUnlockables().values()) {
            if (u instanceof IItemBlocker) {
                IItemBlocker ib = (IItemBlocker) u;
                if ((!u.isUnlocked(techs)) && ib.doesBlockItem(item)) {
                    List<Tech> required = new ArrayList<Tech>();
                    for (Tech t : u.requiredTechs())
                        if (!techs.contains(t))
                            required.add(t);
                    CivLog.debugInfo("Could not use " + item + ", because \"" + u.getLocalisedName() + "\" is blocking it! (requires "
                            + Arrays.toString(required.toArray()) + ")");
                    return false;
                }
            }
        }
        return true;
    }

    public static boolean canUse(World world, BlockPos pos, ItemStack item) {
        return canUse(item, getTechs(world, pos));
    }

    public static boolean canUse(EntityPlayer player, ItemStack item) {
        return canUse(item, TechUtils.getTechs(player));
    }
}
