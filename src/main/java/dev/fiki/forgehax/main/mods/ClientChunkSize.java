package dev.fiki.forgehax.main.mods;

import dev.fiki.forgehax.main.Common;
import dev.fiki.forgehax.main.events.ClientTickEvent;
import dev.fiki.forgehax.main.util.mod.Category;
import dev.fiki.forgehax.main.util.mod.ToggleMod;
import dev.fiki.forgehax.main.util.mod.loader.RegisterMod;
import dev.fiki.forgehax.main.util.SimpleTimer;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.text.NumberFormat;
import java.util.concurrent.Executors;
import java.util.zip.DeflaterOutputStream;

import dev.fiki.forgehax.main.util.reflection.FastReflection;
import net.minecraft.nbt.CompoundNBT;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.storage.ChunkLoader;
import net.minecraftforge.eventbus.api.SubscribeEvent;

@RegisterMod
public class ClientChunkSize extends ToggleMod {

  private static final File DUMMY = Common.getFileManager().getBaseResolve("dummy").toFile();

  private final SimpleTimer timer = new SimpleTimer();

  private boolean running = false;
  private long size = 0L;
  private long previousSize = 0L;
  private ChunkPos current = null;

  public ClientChunkSize() {
    super(Category.MISC, "ClientChunkSize", false, "Shows the client-side chunk size in bytes");
  }

  private static String toFormattedBytes(long size) {
    NumberFormat format = NumberFormat.getInstance();
    format.setGroupingUsed(true);
    if (size < 1000) // less than 1KB
    {
      return format.format(size) + " B";
    } else if (size < 1000000) // less than 1MB
    {
      return format.format((double) size / 1000.D) + " KB";
    } else {
      return format.format((double) size / 1000000.D) + " MB";
    }
  }

  private static String difference(long size) {
    if (size == 0) {
      return "+0 B";
    }
    if (size > 0) {
      return "+" + toFormattedBytes(size);
    } else {
      return "-" + toFormattedBytes(Math.abs(size));
    }
  }

  @Override
  protected void onEnabled() {
    timer.reset();
    running = false;
    size = previousSize = 0L;
    current = null;
  }

  @Override
  public String getDisplayText() {
    return super.getDisplayText()
        + " "
        + String.format(
        "[%s | %s]",
        size == -1 ? "<error>" : toFormattedBytes(size), difference(size - previousSize));
  }

  @SubscribeEvent
  public void onTick(ClientTickEvent.Pre event) {
    if (!Common.isInWorld() || running) {
      return;
    }

    Chunk chunk = Common.getWorld().getChunkAt(Common.getLocalPlayer().getPosition());
    if (chunk.isEmpty()) {
      return;
    }

    ChunkPos pos = chunk.getPos();
    if (!pos.equals(current) || (timer.isStarted() && timer.hasTimeElapsed(1000L))) {
      // chunk changed, don't show diff between different chunks
      if (current != null && !pos.equals(current)) {
        size = previousSize = 0L;
      }

      current = pos;
      running = true;

      // process size calculation on another thread
      Executors.defaultThreadFactory()
          .newThread(
              () -> {
                try {
                  final CompoundNBT root = new CompoundNBT();
                  CompoundNBT level = new CompoundNBT();
                  root.put("Level", level);
                  root.putInt("DataVersion", 1337);

                  try {
                    // this should be done on the main mc thread but it works 99% of the
                    // time outside it
                    ChunkLoader loader = new ChunkLoader(DUMMY, null);
                    FastReflection.Methods.AnvilChunkLoader_writeChunkToNBT.invoke(
                        loader, chunk, Common.getWorld(), level);
                  } catch (Throwable t) {
                    size = -1L;
                    previousSize = 0L;
                    return; // couldn't save chunk
                  }

                  DataOutputStream compressed = new DataOutputStream(new BufferedOutputStream(
                      new DeflaterOutputStream(new ByteArrayOutputStream(8096))));
                  try {
                    CompressedStreamTools.write(root, compressed);
                    previousSize = size;
                    size = compressed.size();
                  } catch (IOException e) {
                    size = -1L;
                    previousSize = 0L;
                  }
                } finally {
                  timer.start();
                  running = false;
                }
              })
          .start();
    }
  }
}