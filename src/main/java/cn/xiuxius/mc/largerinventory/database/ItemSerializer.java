package cn.xiuxius.mc.largerinventory.database;

import org.bukkit.inventory.ItemStack;
import org.bukkit.util.io.BukkitObjectInputStream;
import org.bukkit.util.io.BukkitObjectOutputStream;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

/**
 * ItemStack 序列化工具
 */
public final class ItemSerializer {

    private ItemSerializer() {
    }

    public static byte[] serialize(ItemStack item) throws IOException {
        if (item == null || item.getType().isAir()) return null;
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             BukkitObjectOutputStream boos = new BukkitObjectOutputStream(baos)) {
            boos.writeObject(item);
            return baos.toByteArray();
        }
    }

    public static ItemStack deserialize(byte[] data) throws IOException, ClassNotFoundException {
        if (data == null || data.length == 0) return null;
        try (ByteArrayInputStream bais = new ByteArrayInputStream(data);
             BukkitObjectInputStream bois = new BukkitObjectInputStream(bais)) {
            return (ItemStack) bois.readObject();
        }
    }
}
