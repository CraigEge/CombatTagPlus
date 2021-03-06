package net.minelink.ctplus.compat.v1_8_R2;

import net.minecraft.server.v1_8_R2.EntityPlayer;
import net.minecraft.server.v1_8_R2.FoodMetaData;
import net.minecraft.server.v1_8_R2.PacketPlayOutPlayerInfo.EnumPlayerInfoAction;
import net.minecraft.server.v1_8_R2.ItemStack;
import net.minecraft.server.v1_8_R2.MinecraftServer;
import net.minecraft.server.v1_8_R2.NBTCompressedStreamTools;
import net.minecraft.server.v1_8_R2.NBTTagCompound;
import net.minecraft.server.v1_8_R2.NBTTagList;
import net.minecraft.server.v1_8_R2.Packet;
import net.minecraft.server.v1_8_R2.PacketPlayOutEntityEquipment;
import net.minecraft.server.v1_8_R2.PacketPlayOutPlayerInfo;
import net.minecraft.server.v1_8_R2.WorldNBTStorage;
import net.minecraft.server.v1_8_R2.WorldServer;
import net.minelink.ctplus.compat.api.NpcIdentity;
import net.minelink.ctplus.compat.api.NpcPlayerHelper;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.craftbukkit.v1_8_R2.CraftWorld;
import org.bukkit.craftbukkit.v1_8_R2.entity.CraftPlayer;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.Field;

public final class NpcPlayerHelperImpl implements NpcPlayerHelper {

    @Override
    public Player spawn(Player player) {
        NpcPlayer npcPlayer = NpcPlayer.valueOf(player);
        MinecraftServer minecraftServer = MinecraftServer.getServer();
        WorldServer worldServer = minecraftServer.getWorldServer(npcPlayer.dimension);
        Location l = player.getLocation();

        npcPlayer.spawnIn(worldServer);
        npcPlayer.setPositionRotation(l.getX(), l.getY(), l.getZ(), l.getYaw(), l.getPitch());
        npcPlayer.playerInteractManager.a(worldServer);
        npcPlayer.invulnerableTicks = 0;

        for (Object o : minecraftServer.getPlayerList().players) {
            if (!(o instanceof EntityPlayer) || o instanceof NpcPlayer) continue;

            PacketPlayOutPlayerInfo packet = new PacketPlayOutPlayerInfo(EnumPlayerInfoAction.ADD_PLAYER, npcPlayer);
            ((EntityPlayer) o).playerConnection.sendPacket(packet);
        }

        worldServer.addEntity(npcPlayer);
        minecraftServer.getPlayerList().a(npcPlayer, (WorldServer) null);

        return npcPlayer.getBukkitEntity();
    }

    @Override
    public void despawn(Player player) {
        EntityPlayer entity = ((CraftPlayer) player).getHandle();

        if (!(entity instanceof NpcPlayer)) {
            throw new IllegalArgumentException();
        }

        NpcPlayer npcPlayer = (NpcPlayer) entity;

        for (Object o : MinecraftServer.getServer().getPlayerList().players) {
            if (!(o instanceof EntityPlayer) || o instanceof NpcPlayer) continue;

            PacketPlayOutPlayerInfo packet = new PacketPlayOutPlayerInfo(EnumPlayerInfoAction.REMOVE_PLAYER, npcPlayer);
            ((EntityPlayer) o).playerConnection.sendPacket(packet);
        }

        ((CraftWorld) player.getLocation().getWorld()).getHandle().removeEntity(entity);
    }

    @Override
    public boolean isNpc(Player player) {
        return ((CraftPlayer) player).getHandle() instanceof NpcPlayer;
    }

    @Override
    public NpcIdentity getIdentity(Player player) {
        if (!isNpc(player)) {
            throw new IllegalArgumentException();
        }

        return ((NpcPlayer) ((CraftPlayer) player).getHandle()).getNpcIdentity();
    }

    @Override
    public void updateEquipment(Player player) {
        EntityPlayer entity = ((CraftPlayer) player).getHandle();

        if (!(entity instanceof NpcPlayer)) {
            throw new IllegalArgumentException();
        }

        Location l = player.getLocation();
        int rangeSquared = 512 * 512;

        for (int i = 0; i < 5; ++i) {
            ItemStack item = entity.getEquipment(i);
            if (item == null) continue;

            Packet packet = new PacketPlayOutEntityEquipment(entity.getId(), i, item);

            for (Object o : entity.world.players) {
                if (!(o instanceof EntityPlayer)) continue;

                EntityPlayer p = (EntityPlayer) o;
                if (l.distanceSquared(p.getBukkitEntity().getLocation()) <= rangeSquared) {
                    p.playerConnection.sendPacket(packet);
                }
            }
        }
    }

    @Override
    public void syncOffline(Player player) {
        EntityPlayer entity = ((CraftPlayer) player).getHandle();

        if (!(entity instanceof NpcPlayer)) {
            throw new IllegalArgumentException();
        }

        NpcPlayer npcPlayer = (NpcPlayer) entity;
        NpcIdentity identity = npcPlayer.getNpcIdentity();
        Player p = Bukkit.getPlayer(identity.getId());
        if (p != null && p.isOnline()) return;

        WorldNBTStorage worldStorage = (WorldNBTStorage) npcPlayer.getWorld().getDataManager();
        NBTTagCompound playerNbt = worldStorage.getPlayerData(identity.getId().toString());
        if (playerNbt == null) return;

        // foodTickTimer is now private in 1.8.3
        Field foodTickTimerField;
        int foodTickTimer;

        try {
            foodTickTimerField = FoodMetaData.class.getDeclaredField("foodTickTimer");
            foodTickTimerField.setAccessible(true);
            foodTickTimer = foodTickTimerField.getInt(entity.getFoodData());
        } catch (NoSuchFieldException | IllegalAccessException e) {
            throw new RuntimeException(e);
        }

        playerNbt.setShort("Air", (short) entity.getAirTicks());
        playerNbt.setFloat("HealF", entity.getHealth());
        playerNbt.setShort("Health", (short) ((int) Math.ceil((double) entity.getHealth())));
        playerNbt.setFloat("AbsorptionAmount", entity.getAbsorptionHearts());
        playerNbt.setInt("XpTotal", entity.expTotal);
        playerNbt.setInt("foodLevel", entity.getFoodData().foodLevel);
        playerNbt.setInt("foodTickTimer", foodTickTimer);
        playerNbt.setFloat("foodSaturationLevel", entity.getFoodData().saturationLevel);
        playerNbt.setFloat("foodExhaustionLevel", entity.getFoodData().exhaustionLevel);
        playerNbt.setShort("Fire", (short) entity.fireTicks);
        playerNbt.set("Inventory", npcPlayer.inventory.a(new NBTTagList()));

        File file1 = new File(worldStorage.getPlayerDir(), identity.getId().toString() + ".dat.tmp");
        File file2 = new File(worldStorage.getPlayerDir(), identity.getId().toString() + ".dat");

        try {
            NBTCompressedStreamTools.a(playerNbt, new FileOutputStream(file1));
        } catch (IOException e) {
            throw new RuntimeException("Failed to save player data for " + identity.getName(), e);
        }

        if ((!file2.exists() || file2.delete()) && !file1.renameTo(file2)) {
            throw new RuntimeException("Failed to save player data for " + identity.getName());
        }
    }

    @Override
    public void createPlayerList(Player player) {
        EntityPlayer p = ((CraftPlayer) player).getHandle();

        for (WorldServer worldServer : MinecraftServer.getServer().worlds) {
            for (Object o : worldServer.players) {
                if (!(o instanceof NpcPlayer)) continue;

                NpcPlayer npcPlayer = (NpcPlayer) o;
                PacketPlayOutPlayerInfo packet = new PacketPlayOutPlayerInfo(EnumPlayerInfoAction.ADD_PLAYER, npcPlayer);
                p.playerConnection.sendPacket(packet);
            }
        }
    }

    @Override
    public void removePlayerList(Player player) {
        EntityPlayer p = ((CraftPlayer) player).getHandle();

        for (WorldServer worldServer : MinecraftServer.getServer().worlds) {
            for (Object o : worldServer.players) {
                if (!(o instanceof NpcPlayer)) continue;

                NpcPlayer npcPlayer = (NpcPlayer) o;
                PacketPlayOutPlayerInfo packet = new PacketPlayOutPlayerInfo(EnumPlayerInfoAction.REMOVE_PLAYER, npcPlayer);
                p.playerConnection.sendPacket(packet);
            }
        }
    }

}
