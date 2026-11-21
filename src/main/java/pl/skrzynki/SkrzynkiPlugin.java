package pl.skrzynki;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.command.*;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.LeatherArmorMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.io.File;
import java.util.*;

public final class SkrzynkiPlugin extends JavaPlugin implements Listener, TabExecutor {
    static final float S = 1.2f;                 // rozmiar skrzynki w blokach
    static final int CYCLE = 300, OPEN_AT = 120, CLOSE_AT = 260; // 15 s cyklu (ticki)
    static final double HALF = S / 2.0;

    record Type(String key, String name, Material item, int color, String particle, boolean lucky, float itemScale) {}

    final Map<String, Type> types = new LinkedHashMap<>();
    final List<Chest> chests = new ArrayList<>();
    final Map<UUID, Long> pushCd = new HashMap<>(), clickCd = new HashMap<>();
    final Random rnd = new Random();
    NamespacedKey idKey;
    File dataFile;
    long tick;
    int nextId = 1;
    double pushDist, pushPower;
    int clickCooldown;
    String packUrl = "", packSha = "";

    void reg(String k, String n, Material m, int col, String particle, float sc) {
        types.put(k, new Type(k, n, m, col, particle, false, sc));
    }

    @Override public void onEnable() {
        saveDefaultConfig();
        readConfig();
        idKey = new NamespacedKey(this, "chest");
        reg("zielona", "Zielona", Material.EMERALD, -1, "happy_villager", .42f);
        reg("fioletowa", "Fioletowa", Material.AMETHYST_SHARD, -1, "witch", .42f);
        reg("czerwona", "Czerwona", Material.FIRE_CHARGE, -1, "flame", .42f);
        reg("zolta", "Zolta", Material.BONE, -1, "end_rod", .45f);
        reg("rozowa", "Rozowa", Material.LEATHER_CHESTPLATE, 0xF06CB8, "heart", .5f);
        reg("niebieska", "Jasnoniebieska", Material.LEATHER_HELMET, 0x3FC4E8, "snowflake", .5f);
        types.put("lucky", new Type("lucky", "Lucky Block", Material.PAPER, -1, "", true, 1f));
        dataFile = new File(getDataFolder(), "data.yml");
        load();
        PluginCommand pc = getCommand("skrzynka");
        if (pc != null) { pc.setExecutor(this); pc.setTabCompleter(this); }
        getServer().getPluginManager().registerEvents(this, this);
        getServer().getScheduler().runTaskTimer(this, this::tick, 1L, 1L);
    }

    @Override public void onDisable() {
        save();
        for (Chest c : chests) c.remove();
    }

    void readConfig() {
        reloadConfig();
        pushDist = getConfig().getDouble("push.distance", 0.5);
        pushPower = getConfig().getDouble("push.power", 0.35);
        clickCooldown = getConfig().getInt("click-cooldown", 40);
        packUrl = getConfig().getString("resource-pack.url", "");
        packSha = getConfig().getString("resource-pack.sha1", "");
    }

    // ---------- pomocnicze ----------
    ItemStack model(String name) {
        ItemStack s = new ItemStack(Material.PAPER);
        ItemMeta m = s.getItemMeta();
        m.setItemModel(new NamespacedKey("skrzynki", name));
        s.setItemMeta(m);
        return s;
    }

    ItemStack floating(Type t) {
        ItemStack s = new ItemStack(t.item());
        if (t.color() >= 0 && s.getItemMeta() instanceof LeatherArmorMeta lm) {
            lm.setColor(Color.fromRGB(t.color()));
            s.setItemMeta(lm);
        }
        return s;
    }

    static Transformation tf(float ty, Quaternionf rot, float sx, float sy, float sz) {
        return new Transformation(new Vector3f(0, ty, 0), rot, new Vector3f(sx, sy, sz), new Quaternionf());
    }

    Particle particle(String name) {
        return Registry.PARTICLE_TYPE.get(NamespacedKey.minecraft(name));
    }

    static byte[] hex(String s) {
        int n = s.length() / 2;
        byte[] b = new byte[n];
        for (int i = 0; i < n; i++) b[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        return b;
    }

    static String norm(String s) {
        return s.toLowerCase(Locale.ROOT).replace('ż', 'z').replace('ź', 'z').replace('ó', 'o').replace('ł', 'l')
                .replace('ą', 'a').replace('ę', 'e').replace('ś', 's').replace('ć', 'c').replace('ń', 'n');
    }

    // ---------- klasa skrzynki ----------
    final class Chest {
        final int id;
        final Type type;
        final Location loc; // srodek podstawy (poziom podlogi)
        float yaw;
        final List<String> cmds = new ArrayList<>();
        ItemDisplay body, lid, item;
        Interaction hit;
        final int phase;
        float itemAng;
        double lidAng;
        long jumpStart = -1, nextJump;

        Chest(int id, Type t, Location l, float yaw) {
            this.id = id; this.type = t; this.loc = l; this.yaw = yaw;
            this.phase = rnd.nextInt(CYCLE);
            this.nextJump = tick + 60 + rnd.nextInt(200);
        }

        boolean valid() { return body != null && body.isValid(); }

        void trySpawn() {
            World w = loc.getWorld();
            int cx = loc.getBlockX() >> 4, cz = loc.getBlockZ() >> 4;
            if (w.isChunkLoaded(cx, cz) && w.getChunkAt(cx, cz).isEntitiesLoaded()) spawn();
        }

        void remove() {
            for (Entity e : new Entity[]{body, lid, item, hit}) if (e != null) e.remove();
            body = lid = item = null; hit = null;
        }

        void setup(ItemDisplay e, ItemStack st, Transformation t) {
            e.setItemStack(st);
            e.setPersistent(false);
            e.setViewRange(1.5f);
            e.setTransformation(t);
        }

        void spawn() {
            remove();
            World w = loc.getWorld();
            double r = Math.toRadians(yaw);
            Location c = loc.clone().add(0, 0.6, 0);
            c.setYaw(yaw); c.setPitch(0);
            body = w.spawn(c, ItemDisplay.class, e -> setup(e, model(type.lucky() ? "luckyblock" : "chest_body_" + type.key()),
                    tf(0, new Quaternionf(), S, S, S)));
            if (!type.lucky()) {
                Location h = loc.clone().add(Math.sin(r) * HALF, 0.75, -Math.cos(r) * HALF);
                h.setYaw(yaw); h.setPitch(0);
                lid = w.spawn(h, ItemDisplay.class, e -> setup(e, model("chest_lid_" + type.key()),
                        tf(0, new Quaternionf(), S, S, S)));
                Location i = loc.clone().add(0, 1.6, 0);
                i.setYaw(yaw);
                item = w.spawn(i, ItemDisplay.class, e -> {
                    setup(e, floating(type), tf(0, new Quaternionf(), type.itemScale(), type.itemScale(), type.itemScale()));
                    e.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.GROUND);
                });
                lidAng = 0;
            }
            hit = w.spawn(loc, Interaction.class, e -> {
                e.setInteractionWidth(S);
                e.setInteractionHeight(S);
                e.setResponsive(true);
                e.setPersistent(false);
                e.getPersistentDataContainer().set(idKey, PersistentDataType.INTEGER, id);
            });
        }

        Transformation lidTf(float k) {
            return new Transformation(new Vector3f(), new Quaternionf().rotateX((float) -lidAng),
                    new Vector3f(S * k), new Quaternionf());
        }

        void lidTo(double ang, int dur) {
            lidAng = ang;
            lid.setInterpolationDelay(0);
            lid.setInterpolationDuration(dur);
            lid.setTransformation(lidTf(1f));
        }

        void pulse() {
            if (!valid() || (type.lucky() && jumpStart >= 0)) return;
            body.setInterpolationDelay(0); body.setInterpolationDuration(2);
            body.setTransformation(tf(0, new Quaternionf(), S * 1.06f, S * 1.06f, S * 1.06f));
            if (lid != null) { lid.setInterpolationDelay(0); lid.setInterpolationDuration(2); lid.setTransformation(lidTf(1.06f)); }
            Bukkit.getScheduler().runTaskLater(SkrzynkiPlugin.this, () -> {
                if (!valid() || (type.lucky() && jumpStart >= 0)) return;
                body.setInterpolationDelay(0); body.setInterpolationDuration(3);
                body.setTransformation(tf(0, new Quaternionf(), S, S, S));
                if (lid != null) { lid.setInterpolationDelay(0); lid.setInterpolationDuration(3); lid.setTransformation(lidTf(1f)); }
            }, 2L);
        }

        void tickChest(long t) {
            long c = (t + phase) % CYCLE;
            World w = loc.getWorld();
            if (c == OPEN_AT) {
                lidTo(Math.toRadians(100), 14);
                w.playSound(loc, Sound.BLOCK_CHEST_OPEN, 0.5f, 1.15f);
                Particle p = particle(type.particle());
                if (p != null) w.spawnParticle(p, loc.getX(), loc.getY() + 1.3, loc.getZ(), 10, 0.3, 0.1, 0.3, 0.03);
            }
            if (c == CLOSE_AT) {
                lidTo(0, 14);
                w.playSound(loc, Sound.BLOCK_CHEST_CLOSE, 0.5f, 1.15f);
            }
            if (c > OPEN_AT + 14 && c < CLOSE_AT && t % 5 == 0) {
                Particle p = particle(type.particle());
                if (p != null) w.spawnParticle(p, loc.getX(), loc.getY() + 1.35, loc.getZ(), 2, 0.22, 0.08, 0.22, 0.01);
            }
            if ((t + id * 3L) % 20 == 0) {
                long k = (t + id * 3L) / 20;
                boolean open = c >= OPEN_AT && c < CLOSE_AT;
                itemAng += (float) (Math.PI / 2);
                float y = (float) (0.06 * Math.sin(k * Math.PI / 2)) + (open ? 0.18f : 0f);
                item.setInterpolationDelay(0); item.setInterpolationDuration(20);
                float s = type.itemScale();
                item.setTransformation(tf(y, new Quaternionf().rotateY(itemAng), s, s, s));
            }
        }

        static double jumpY(long d) {
            if (d < 0 || d > 20) return 0;
            double[] ys = {0, .7, 1.15, .7, 0};
            int i = (int) (d / 5);
            if (i >= 4) return 0;
            double f = (d % 5) / 5.0;
            return ys[i] + (ys[i + 1] - ys[i]) * f;
        }

        void frame(float y, int steps, float sx, float sy, float sz, int dur) {
            body.setInterpolationDelay(0);
            body.setInterpolationDuration(dur);
            body.setTransformation(tf(y, new Quaternionf().rotateY((float) (steps * Math.PI / 2)), sx, sy, sz));
        }

        void tickLucky(long t) {
            World w = loc.getWorld();
            if (jumpStart < 0 && t >= nextJump) {
                jumpStart = t;
                w.playSound(loc, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.7f, 1.3f);
            }
            long d = jumpStart < 0 ? -1 : t - jumpStart;
            if (d == 0) frame(.7f, 1, S, S, S, 5);
            else if (d == 5) frame(1.15f, 2, S, S, S, 5);
            else if (d == 10) frame(.7f, 3, S, S, S, 5);
            else if (d == 15) frame(0f, 4, S, S, S, 5);
            else if (d == 20) {
                frame(-0.1f, 4, S * 1.15f, 1.0f, S * 1.15f, 3);
                w.playSound(loc, Sound.BLOCK_WOOD_FALL, 0.8f, 0.9f);
                w.spawnParticle(Particle.CLOUD, loc.getX(), loc.getY() + 0.1, loc.getZ(), 6, 0.5, 0.02, 0.5, 0.01);
            } else if (d == 23) frame(0f, 4, S, S, S, 3);
            else if (d > 26) {
                jumpStart = -1;
                nextJump = t + 200 + rnd.nextInt(201); // 10-20 s
                body.setInterpolationDelay(0); body.setInterpolationDuration(0);
                body.setTransformation(tf(0, new Quaternionf(), S, S, S));
            }
            if (t % 2 == 0) ring(t, d);
        }

        void ring(long t, long d) {
            double cy = loc.getY() + 0.6 + jumpY(d);
            double ph = t * 0.12, tilt = Math.toRadians(22), pr = t * 0.012;
            Particle.DustOptions a = new Particle.DustOptions(Color.fromRGB(255, 200, 40), 1.0f);
            Particle.DustOptions b = new Particle.DustOptions(Color.fromRGB(255, 120, 20), 0.8f);
            World w = loc.getWorld();
            for (int i = 0; i < 8; i++) {
                double th = ph + i * Math.PI / 4;
                Vector v = new Vector(1.05 * Math.cos(th), 0, 1.05 * Math.sin(th));
                v.rotateAroundX(tilt);
                v.rotateAroundY(pr);
                w.spawnParticle(Particle.DUST, loc.getX() + v.getX(), cy + v.getY(), loc.getZ() + v.getZ(),
                        1, 0, 0, 0, 0, i % 2 == 0 ? a : b);
            }
        }
    }

    // ---------- petla ----------
    void tick() {
        tick++;
        for (Chest c : chests) {
            if (!c.valid()) { if (tick % 40 == 0) c.trySpawn(); continue; }
            if (c.type.lucky()) c.tickLucky(tick); else c.tickChest(tick);
        }
        if (tick % 2 == 0) knock();
    }

    void knock() {
        for (Chest c : chests) {
            if (!c.valid()) continue;
            double psi = Math.toRadians(c.yaw), cs = Math.cos(psi), sn = Math.sin(psi);
            for (Player p : c.loc.getWorld().getPlayers()) {
                if (p.getGameMode() == GameMode.SPECTATOR || p.hasPermission("skrzynki.bypass")) continue;
                Location pl = p.getLocation();
                double dx = pl.getX() - c.loc.getX(), dz = pl.getZ() - c.loc.getZ(), dy = pl.getY() - c.loc.getY();
                if (Math.abs(dx) > 3 || Math.abs(dz) > 3 || dy > S - 0.05 || dy < -1.8) continue;
                double lx = dx * cs + dz * sn, lz = -dx * sn + dz * cs;
                double ex = lx - Math.max(-HALF, Math.min(HALF, lx));
                double ez = lz - Math.max(-HALF, Math.min(HALF, lz));
                double dist = Math.hypot(ex, ez);
                if (dist > pushDist) continue;
                if (dist < 1e-6) {
                    if (Math.abs(lx) > Math.abs(lz)) { ex = lx >= 0 ? 1 : -1; ez = 0; }
                    else { ex = 0; ez = lz >= 0 ? 1 : -1; }
                } else { ex /= dist; ez /= dist; }
                Long last = pushCd.get(p.getUniqueId());
                if (last != null && tick - last < 8) continue;
                pushCd.put(p.getUniqueId(), tick);
                double wx = ex * cs - ez * sn, wz = ex * sn + ez * cs;
                p.setVelocity(new Vector(wx * pushPower, 0.14, wz * pushPower));
                p.getWorld().playSound(pl, Sound.ENTITY_SLIME_JUMP, 0.5f, 1.5f);
                c.pulse();
            }
        }
    }

    // ---------- zdarzenia ----------
    @EventHandler public void onClick(PlayerInteractEntityEvent e) {
        if (!(e.getRightClicked() instanceof Interaction in) || e.getHand() != EquipmentSlot.HAND) return;
        Integer id = in.getPersistentDataContainer().get(idKey, PersistentDataType.INTEGER);
        if (id == null) return;
        e.setCancelled(true);
        Chest c = byId(id);
        if (c == null) return;
        Player p = e.getPlayer();
        Long last = clickCd.get(p.getUniqueId());
        if (last != null && tick - last < clickCooldown) return;
        clickCd.put(p.getUniqueId(), tick);
        c.pulse();
        for (String cmd : c.cmds)
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd.replace("%player%", p.getName()));
    }

    @EventHandler public void onJoin(PlayerJoinEvent e) {
        if (packUrl.isBlank()) return;
        try { e.getPlayer().setResourcePack(packUrl, packSha.isBlank() ? null : hex(packSha)); }
        catch (Exception ex) { getLogger().warning("Nie udalo sie wyslac resource packa: " + ex.getMessage()); }
    }

    Chest byId(int id) { for (Chest c : chests) if (c.id == id) return c; return null; }

    Chest nearest(Location l, double r) {
        Chest best = null; double bd = r * r;
        for (Chest c : chests) {
            if (c.loc.getWorld() != l.getWorld()) continue;
            double d = c.loc.distanceSquared(l);
            if (d <= bd) { bd = d; best = c; }
        }
        return best;
    }

    // ---------- dane ----------
    void save() {
        getDataFolder().mkdirs();
        YamlConfiguration y = new YamlConfiguration();
        for (Chest c : chests) {
            String p = "chests." + c.id + ".";
            y.set(p + "type", c.type.key());
            y.set(p + "world", c.loc.getWorld().getName());
            y.set(p + "x", c.loc.getX()); y.set(p + "y", c.loc.getY()); y.set(p + "z", c.loc.getZ());
            y.set(p + "yaw", c.yaw);
            y.set(p + "commands", c.cmds);
        }
        try { y.save(dataFile); } catch (Exception ex) { getLogger().severe("Blad zapisu data.yml: " + ex.getMessage()); }
    }

    void load() {
        if (!dataFile.exists()) return;
        ConfigurationSection sec = YamlConfiguration.loadConfiguration(dataFile).getConfigurationSection("chests");
        if (sec == null) return;
        for (String k : sec.getKeys(false)) {
            ConfigurationSection c = sec.getConfigurationSection(k);
            if (c == null) continue;
            Type t = types.get(c.getString("type", ""));
            World w = Bukkit.getWorld(c.getString("world", ""));
            if (t == null || w == null) continue;
            Chest ch = new Chest(Integer.parseInt(k), t, new Location(w, c.getDouble("x"), c.getDouble("y"), c.getDouble("z")), (float) c.getDouble("yaw"));
            ch.cmds.addAll(c.getStringList("commands"));
            chests.add(ch);
            nextId = Math.max(nextId, ch.id + 1);
        }
    }

    // ---------- komendy ----------
    void help(CommandSender s) {
        s.sendMessage("§e/skrzynka <typ> [kat] §7- postaw skrzynke tam gdzie patrzysz (typy: " + String.join(", ", types.keySet()) + ")");
        s.sendMessage("§e/skrzynka usun §7| §eobroc <kat> §7| §elista §7| §eczysc §7| §ereload");
        s.sendMessage("§e/skrzynka komenda dodaj <komenda> §7| §ekomenda lista §7| §ekomenda usun <nr> §7(uzyj %player%)");
    }

    @Override public boolean onCommand(CommandSender s, Command cmd, String lbl, String[] a) {
        if (!s.hasPermission("skrzynki.admin")) { s.sendMessage("§cBrak uprawnien."); return true; }
        if (a.length == 0) { help(s); return true; }
        String sub = norm(a[0]);
        if (sub.equals("reload")) { readConfig(); s.sendMessage("§aPrzeladowano config."); return true; }
        if (sub.equals("lista")) {
            for (Chest c : chests) s.sendMessage("§7#" + c.id + " §f" + c.type.key() + " §7" + c.loc.getWorld().getName()
                    + " " + c.loc.getBlockX() + " " + c.loc.getBlockY() + " " + c.loc.getBlockZ() + " kat " + Math.round(c.yaw) + " komend: " + c.cmds.size());
            if (chests.isEmpty()) s.sendMessage("§7Brak skrzynek.");
            return true;
        }
        if (sub.equals("czysc")) {
            for (Chest c : chests) c.remove();
            chests.clear(); save(); s.sendMessage("§aUsunieto wszystkie skrzynki.");
            return true;
        }
        if (!(s instanceof Player p)) { s.sendMessage("Ta komenda dziala tylko w grze."); return true; }
        switch (sub) {
            case "usun" -> {
                Chest c = nearest(p.getLocation(), 6);
                if (c == null) { p.sendMessage("§cNie ma skrzynki w poblizu (6 blokow)."); return true; }
                c.remove(); chests.remove(c); save(); p.sendMessage("§aUsunieto skrzynke #" + c.id);
            }
            case "obroc" -> {
                Chest c = nearest(p.getLocation(), 6);
                if (c == null || a.length < 2) { p.sendMessage("§cUzyj: /skrzynka obroc <kat> przy skrzynce."); return true; }
                try { c.yaw = Float.parseFloat(a[1]); } catch (NumberFormatException ex) { p.sendMessage("§cZly kat."); return true; }
                c.spawn(); save(); p.sendMessage("§aObrocono #" + c.id + " na " + c.yaw + "°");
            }
            case "komenda" -> {
                Chest c = nearest(p.getLocation(), 6);
                if (c == null || a.length < 2) { p.sendMessage("§cStan przy skrzynce: /skrzynka komenda <dodaj|lista|usun> ..."); return true; }
                String o = norm(a[1]);
                if (o.equals("dodaj") && a.length > 2) {
                    String line = String.join(" ", Arrays.copyOfRange(a, 2, a.length));
                    if (line.startsWith("/")) line = line.substring(1);
                    c.cmds.add(line); save(); p.sendMessage("§aDodano komende #" + c.cmds.size() + ": §f" + line);
                } else if (o.equals("lista")) {
                    for (int i = 0; i < c.cmds.size(); i++) p.sendMessage("§7" + (i + 1) + ". §f" + c.cmds.get(i));
                    if (c.cmds.isEmpty()) p.sendMessage("§7Brak komend.");
                } else if (o.equals("usun") && a.length > 2) {
                    try { c.cmds.remove(Integer.parseInt(a[2]) - 1); save(); p.sendMessage("§aUsunieto."); }
                    catch (Exception ex) { p.sendMessage("§cZly numer."); }
                } else p.sendMessage("§cUzyj: komenda dodaj <komenda> | lista | usun <nr>");
            }
            default -> {
                Type t = types.get(sub.equals("jasnoniebieska") ? "niebieska" : sub);
                if (t == null) { help(p); return true; }
                float yaw = p.getLocation().getYaw() + 180f;
                if (a.length > 1) {
                    try { yaw = Float.parseFloat(a[1]); } catch (NumberFormatException ex) { p.sendMessage("§cZly kat."); return true; }
                }
                Block b = p.getTargetBlockExact(6);
                Location at = b != null ? b.getLocation().add(0.5, 1, 0.5)
                        : p.getLocation().getBlock().getLocation().add(0.5, 0, 0.5);
                Chest c = new Chest(nextId++, t, at, yaw);
                chests.add(c); c.spawn(); save();
                p.sendMessage("§aPostawiono " + t.name() + " (#" + c.id + ") pod katem " + Math.round(yaw) + "°");
            }
        }
        return true;
    }

    @Override public List<String> onTabComplete(CommandSender s, Command c, String l, String[] a) {
        List<String> out = new ArrayList<>();
        if (a.length == 1) {
            out.addAll(types.keySet());
            out.addAll(List.of("usun", "obroc", "lista", "czysc", "komenda", "reload"));
        } else if (a.length == 2 && a[0].equalsIgnoreCase("komenda")) out.addAll(List.of("dodaj", "lista", "usun"));
        else if (a.length == 2) out.addAll(List.of("0", "45", "90", "135", "180", "225", "270", "315"));
        String pre = a[a.length - 1].toLowerCase(Locale.ROOT);
        out.removeIf(x -> !x.startsWith(pre));
        return out;
    }
}
