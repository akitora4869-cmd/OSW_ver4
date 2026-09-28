package jp.onesignalwolf;

import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.command.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.block.Block;
import org.bukkit.boss.*;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.*;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.*;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.*;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.*;
import org.bukkit.scoreboard.*;
import org.bukkit.util.Vector;
import org.bukkit.util.EulerAngle;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.TextComponent;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.io.File;
import java.io.IOException;

public final class OneSignalWolfPlugin extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {
    private final Map<UUID, PlayerData> data = new HashMap<>();
    private final Map<UUID, Long> meleeCooldown = new HashMap<>();
    private final Map<UUID, Body> bodies = new HashMap<>();
    private final Map<UUID, PendingReport> reports = new HashMap<>();
    private final Map<UUID, UUID> votes = new HashMap<>();
    private final List<EmpZone> empZones = new ArrayList<>();
    private final List<SmokeZone> smokeZones = new ArrayList<>();
    private final Map<UUID, TextDisplay> tags = new HashMap<>();
    private final Map<UUID, Integer> signalCounts = new HashMap<>();
    private final Map<UUID, Integer> healCounts = new HashMap<>();
    private final Map<UUID, Integer> bodyFindCounts = new HashMap<>();
    private final Map<UUID, Integer> killCounts = new HashMap<>();
    private final Map<UUID, Integer> reportCounts = new HashMap<>();
    private final Map<UUID, Integer> specialCollectCounts = new HashMap<>();
    private final Map<UUID, Long> darknessSince = new HashMap<>();
    private final Map<UUID, Item> willDrops = new HashMap<>();
    private final Map<UUID, String> aidMode = new HashMap<>();
    private final Map<UUID, Location> lastLocations = new HashMap<>();
    private final Map<UUID, Deque<TrackPoint>> tracks = new HashMap<>();
    private final Map<UUID, DamageCauseInfo> lastCause = new HashMap<>();
    private final Set<UUID> wildlife = new HashSet<>();
    private long nextWildlifeAt = 0L;
    private boolean running=false, meeting=false, breakerOff=false;
    private boolean meetingChoicePhase=false;
    private final Set<UUID> meetingAttendees=new HashSet<>(), meetingRefused=new HashSet<>();
    private final Map<UUID,Location> meetingOrigins=new HashMap<>();
    private boolean testMode=false;
    private final Map<UUID, BotData> bots = new LinkedHashMap<>();
    private int remainingGameSeconds=1800;
    private long timerMark=0L;
    private BossBar gameBar;
    private NamespacedKey itemKey, ammoKey;
    private int meetingTask=-1;
    private File mapFile; private YamlConfiguration mapCfg;
    private UUID finalWinner=null; private boolean executionReady=false;
    private final String[] colors={"RED","BLUE","GREEN","YELLOW","PURPLE","ORANGE","WHITE","CYAN","PINK","LIME","GRAY","BROWN"};
    private final Color[] leatherColors={Color.RED,Color.BLUE,Color.GREEN,Color.YELLOW,Color.PURPLE,Color.ORANGE,Color.WHITE,Color.AQUA,Color.FUCHSIA,Color.LIME,Color.GRAY,Color.fromRGB(128,70,30)};
    private final String[] hos={"MEDIC","GUARD","SCOUT","ENGINEER","SIGNALER","TRACKER","FORENSIC","COURIER","SURVIVOR","HUNTER","SABOTEUR","OBSERVER","NEGOTIATOR","LOOTER","OPERATOR"};
    private final String[] missions={"SEND_3_SIGNALS","HEAL_PLAYER","USE_BREAKER","USE_EMP","FIND_BODY","SURVIVE_DARKNESS","KILL_PLAYER","REPORT_BODY","COLLECT_SPECIAL"};

    @Override public void onEnable(){
        saveDefaultConfig(); itemKey=new NamespacedKey(this,"special_item"); ammoKey=new NamespacedKey(this,"ammo");
        getServer().getPluginManager().registerEvents(this,this); PluginCommand osw=Objects.requireNonNull(getCommand("osw")); osw.setExecutor(this); osw.setTabCompleter(this);
        setupNoNameTags(); loadMapConfig();
        gameBar=Bukkit.createBossBar("OneSignalWolf", BarColor.RED, BarStyle.SOLID);
        Bukkit.getScheduler().runTaskTimer(this,this::tick,2L,4L); nextWildlifeAt=System.currentTimeMillis()+getConfig().getLong("wildlife-interval-seconds",180)*1000L;
        getLogger().info("OneSignalWolf v0.14-completion enabled (Paper 1.20.1 / Java 17)");
    }
    @Override public void onDisable(){ tags.values().forEach(Entity::remove); removeAllBots(); if(gameBar!=null)gameBar.removeAll(); saveMapConfig(); }


    private void loadMapConfig(){
        mapFile=new File(getDataFolder(),"map.yml"); mapCfg=YamlConfiguration.loadConfiguration(mapFile);
    }
    private void saveMapConfig(){ if(mapCfg==null||mapFile==null)return; try{mapCfg.save(mapFile);}catch(IOException e){getLogger().warning("Could not save map.yml: "+e.getMessage());} }
    private String blockKey(Block b){return b.getWorld().getName()+":"+b.getX()+":"+b.getY()+":"+b.getZ();}
    private String locString(Location l){return l.getWorld().getName()+","+l.getX()+","+l.getY()+","+l.getZ()+","+l.getYaw()+","+l.getPitch();}
    private Location parseLoc(String raw){if(raw==null)return null;try{String[] a=raw.split(",");World w=Bukkit.getWorld(a[0]);if(w==null)return null;return new Location(w,Double.parseDouble(a[1]),Double.parseDouble(a[2]),Double.parseDouble(a[3]),Float.parseFloat(a[4]),Float.parseFloat(a[5]));}catch(Exception e){return null;}}
    private boolean configuredBlock(String path,Block b){String key=blockKey(b);return mapCfg.getStringList(path).contains(key)||key.equals(mapCfg.getString(path));}
    private void addConfiguredBlock(String path,Block b){List<String> list=new ArrayList<>(mapCfg.getStringList(path));String k=blockKey(b);if(!list.contains(k))list.add(k);mapCfg.set(path,list);saveMapConfig();}
    private Block targetBlock(Player p){return p.getTargetBlockExact(6);}
    private Location meetingLocation(){Location l=parseLoc(mapCfg.getString("meeting"));return l!=null?l:Bukkit.getWorlds().get(0).getSpawnLocation();}

    private void setupNoNameTags(){
        Scoreboard sb=Bukkit.getScoreboardManager().getMainScoreboard();
        Team t=sb.getTeam("osw_hidden_names"); if(t==null)t=sb.registerNewTeam("osw_hidden_names");
        t.setOption(Team.Option.NAME_TAG_VISIBILITY, Team.OptionStatus.NEVER);
    }

    private void tick(){
        long now=System.currentTimeMillis(); empZones.removeIf(e->e.until<now); smokeZones.removeIf(s->s.until<now);
        tickGameTimer(now);
        for(Player p:Bukkit.getOnlinePlayers()){
            if(!running) continue;
            PlayerData d=data.get(p.getUniqueId()); if(d==null)continue;
            if(breakerOff){ darknessSince.putIfAbsent(p.getUniqueId(),now); if(now-darknessSince.get(p.getUniqueId())>=60000)checkMission(p,"SURVIVE_DARKNESS"); } else darknessSince.remove(p.getUniqueId());
            if(breakerOff && !hasHotbar(p,"NIGHT_SCOPE")){
                if(hasHand(p,"FLASHLIGHT")) p.addPotionEffect(new PotionEffect(PotionEffectType.NIGHT_VISION,10,0,false,false,false));
                else p.addPotionEffect(new PotionEffect(PotionEffectType.DARKNESS,10,0,false,false,false));
            } else if(hasHotbar(p,"NIGHT_SCOPE")) p.addPotionEffect(new PotionEffect(PotionEffectType.NIGHT_VISION,10,0,false,false,false));
            updateTag(p,d);
            if(hasHotbar(p,"TRACKER")) showTracks(p);
            if(hasHotbar(p,"EMP_SHIELD") && inEmp(p.getLocation())) p.sendActionBar("§5⚠ EMP DETECTED §7- COMMUNICATION BLOCKED");
        }
        updateTagVisibility();
        processReports(now);
        processWillExpiry();
        recordTracks(now);
        tickWildlife(now);
    }

    private void updateTag(Player p, PlayerData d){
        TextDisplay td=tags.get(p.getUniqueId());
        if(td==null || !td.isValid()){
            td=p.getWorld().spawn(p.getLocation().add(0,2.25,0),TextDisplay.class,x->{x.setBillboard(Display.Billboard.CENTER);x.setSeeThrough(false);x.setShadowed(true);x.setPersistent(false);});
            tags.put(p.getUniqueId(),td);
        }
        td.setText("§f"+d.code+" §8| §f"+p.getName()); td.teleport(p.getLocation().add(0,2.25,0));
    }
    private void updateTagVisibility(){
        double max=getConfig().getDouble("nameplate-range",15);
        for(Player viewer:Bukkit.getOnlinePlayers()) for(Player target:Bukkit.getOnlinePlayers()){
            if(viewer.equals(target))continue; TextDisplay td=tags.get(target.getUniqueId()); if(td==null)continue;
            boolean visible=running && viewer.getWorld().equals(target.getWorld()) && viewer.getLocation().distanceSquared(target.getLocation())<=max*max && viewer.hasLineOfSight(target) && !smokeBlocks(viewer.getEyeLocation(),target.getEyeLocation());
            if(visible)viewer.showEntity(this,td); else viewer.hideEntity(this,td);
        }
    }
    private boolean smokeBlocks(Location a,Location b){
        Vector ab=b.toVector().subtract(a.toVector()); double len=ab.length(); if(len==0)return false; Vector dir=ab.normalize();
        for(SmokeZone s:smokeZones){ if(!s.world.equals(a.getWorld()))continue; for(double x=0;x<=len;x+=1){ Location q=a.clone().add(dir.clone().multiply(x)); if(q.distanceSquared(s.center)<=s.radius*s.radius)return true; } } return false;
    }
    private void showTracks(Player viewer){
        long cutoff=System.currentTimeMillis()-getConfig().getLong("tracker-history-seconds",30)*1000L;
        for(Map.Entry<UUID,Deque<TrackPoint>> en:tracks.entrySet()) if(!en.getKey().equals(viewer.getUniqueId())) for(TrackPoint tp:en.getValue()) if(tp.time>=cutoff&&tp.loc.getWorld().equals(viewer.getWorld())&&tp.loc.distanceSquared(viewer.getLocation())<=400) viewer.spawnParticle(Particle.ASH,tp.loc.clone().add(0,.1,0),1,0,0,0,0);
    }
    private void recordTracks(long now){
        if(!running)return;
        long cutoff=now-getConfig().getLong("tracker-history-seconds",30)*1000L;
        for(Player p:Bukkit.getOnlinePlayers()) if(data.containsKey(p.getUniqueId())&&p.getGameMode()!=GameMode.SPECTATOR){
            Location old=lastLocations.put(p.getUniqueId(),p.getLocation().clone());
            if(old==null||!old.getWorld().equals(p.getWorld())||old.distanceSquared(p.getLocation())>.5){Deque<TrackPoint> q=tracks.computeIfAbsent(p.getUniqueId(),k->new ArrayDeque<>());q.addLast(new TrackPoint(p.getLocation().clone(),now));while(!q.isEmpty()&&q.peekFirst().time<cutoff)q.removeFirst();}
        }
    }
    private void tickWildlife(long now){
        if(!running||meeting||!getConfig().getBoolean("wildlife-enabled",true)||now<nextWildlifeAt)return;
        nextWildlifeAt=now+getConfig().getLong("wildlife-interval-seconds",180)*1000L;
        if(mapCfg.getConfigurationSection("wildlife")==null)return;
        List<String> ids=new ArrayList<>(mapCfg.getConfigurationSection("wildlife").getKeys(false)); if(ids.isEmpty())return;
        String id=ids.get(ThreadLocalRandom.current().nextInt(ids.size())); Location l=parseLoc(mapCfg.getString("wildlife."+id)); if(l==null)return;
        Wolf w=l.getWorld().spawn(l,Wolf.class); w.setCustomName("§4Wild Beast"); w.setAngry(true); wildlife.add(w.getUniqueId());
        Player target=l.getWorld().getPlayers().stream().filter(x->data.containsKey(x.getUniqueId())&&x.getGameMode()!=GameMode.SPECTATOR).min(Comparator.comparingDouble(x->x.getLocation().distanceSquared(l))).orElse(null); if(target!=null)w.setTarget(target);
        Bukkit.getScheduler().runTaskLater(this,()->{wildlife.remove(w.getUniqueId());if(w.isValid())w.remove();},20L*getConfig().getLong("wildlife-duration-seconds",60));
    }

    @EventHandler public void onJoin(PlayerJoinEvent e){ Team t=Bukkit.getScoreboardManager().getMainScoreboard().getTeam("osw_hidden_names"); if(t!=null)t.addEntry(e.getPlayer().getName()); }
    @EventHandler public void onAdvancement(PlayerAdvancementDoneEvent e){ if(running)e.message(null); }
    @EventHandler public void onChat(AsyncPlayerChatEvent e){
        if(!running)return;e.setCancelled(true);Player from=e.getPlayer();PlayerData fd=data.get(from.getUniqueId());String name=fd==null?from.getName():fd.code+"|"+from.getName();String msg="§7<§f"+name+"§7> §f"+e.getMessage();
        Bukkit.getScheduler().runTask(this,()->{if(meeting){if(meetingAttendees.contains(from.getUniqueId()))for(Player to:Bukkit.getOnlinePlayers())if(meetingAttendees.contains(to.getUniqueId()))to.sendMessage(msg);return;}double r=getConfig().getDouble("chat-radius",8);for(Player to:Bukkit.getOnlinePlayers())if(to.getWorld().equals(from.getWorld())&&to.getLocation().distanceSquared(from.getLocation())<=r*r&&from.hasLineOfSight(to))to.sendMessage(msg);});
    }
    @EventHandler public void onDeath(PlayerDeathEvent e){
        if(!running)return; Player p=e.getEntity(); e.deathMessage(null); e.setKeepInventory(false); e.getDrops().removeIf(this::isBoundItem);
        PlayerData d=data.get(p.getUniqueId()); if(d!=null){
            for(int i=0;i<d.backpacks;i++)e.getDrops().add(item("BACKPACK"));
            ItemStack note=findItem(p,"NOTE"); if(note!=null){ Item wi=p.getWorld().dropItemNaturally(p.getLocation(),makeWill(note,d.code)); willDrops.put(wi.getUniqueId(),wi); Bukkit.getScheduler().runTaskLater(this,()->expireWill(wi.getUniqueId()),20L*180); }
            DamageCauseInfo dc=lastCause.get(p.getUniqueId());spawnBody(p,d.code,dc);if(dc!=null&&dc.actor!=null&&dc.type.startsWith("環境キル")){Player killer=Bukkit.getPlayer(dc.actor);if(killer!=null){killCounts.merge(killer.getUniqueId(),1,Integer::sum);checkMission(killer,"KILL_PLAYER");killer.sendMessage("§6環境キル成立 §7+撃破判定");}}
        }
        cancelReportsBy(p.getUniqueId()); Bukkit.getScheduler().runTask(this,this::checkLastSurvivor);
    }
    private void spawnBody(Player p,String code,DamageCauseInfo cause){
        Location l=p.getLocation().clone(); List<ArmorStand> parts=new ArrayList<>();
        // Invisible support entities: only equipped items are visible; no trapdoor/log is required.
        ArmorStand head=p.getWorld().spawn(l.clone().add(0,.22,0),ArmorStand.class,a->{a.setInvisible(true);a.setMarker(true);a.setGravity(false);a.setPersistent(false);a.setRotation(l.getYaw(),0);});
        ItemStack skull=new ItemStack(Material.PLAYER_HEAD); SkullMeta sm=(SkullMeta)skull.getItemMeta(); sm.setOwningPlayer(p); skull.setItemMeta(sm); head.getEquipment().setHelmet(skull); parts.add(head);
        ArmorStand torso=p.getWorld().spawn(l.clone().add(0,.08,0),ArmorStand.class,a->{a.setInvisible(true);a.setMarker(true);a.setGravity(false);a.setPersistent(false);a.setRotation(l.getYaw(),0);a.setBodyPose(new EulerAngle(Math.PI/2,0,0));});
        torso.getEquipment().setChestplate(coloredChest(data.get(p.getUniqueId()).colorIndex)); parts.add(torso);
        Interaction hit=p.getWorld().spawn(l.clone().add(0,.45,0),Interaction.class,i->{i.setInteractionWidth(1.35f);i.setInteractionHeight(.8f);i.setResponsive(true);i.setPersistent(false);});
        Body b=new Body(UUID.randomUUID(),p.getUniqueId(),code,p.getName(),parts,hit,l,System.currentTimeMillis(),cause==null?"不明":cause.type,false,false); bodies.put(b.id,b);
    }
    private void spawnBotBody(BotData bot,Location l){
        List<ArmorStand> parts=new ArrayList<>(); ArmorStand h=l.getWorld().spawn(l.clone().add(0,.22,0),ArmorStand.class,a->{a.setInvisible(true);a.setMarker(true);a.setGravity(false);a.setPersistent(false);});
        h.getEquipment().setHelmet(new ItemStack(woolFor(bot.colorIndex)));parts.add(h);
        ArmorStand t=l.getWorld().spawn(l.clone().add(0,.08,0),ArmorStand.class,a->{a.setInvisible(true);a.setMarker(true);a.setGravity(false);a.setPersistent(false);a.setBodyPose(new EulerAngle(Math.PI/2,0,0));});t.getEquipment().setChestplate(coloredChest(bot.colorIndex));parts.add(t);
        Interaction hit=l.getWorld().spawn(l.clone().add(0,.45,0),Interaction.class,i->{i.setInteractionWidth(1.35f);i.setInteractionHeight(.8f);i.setResponsive(true);i.setPersistent(false);});
        Body b=new Body(UUID.randomUUID(),bot.entity.getUniqueId(),bot.code,bot.id,parts,hit,l.clone(),System.currentTimeMillis(),"不明",false,true);bodies.put(b.id,b);
    }
    private ItemStack coloredChest(int i){ItemStack x=new ItemStack(Material.LEATHER_CHESTPLATE);LeatherArmorMeta m=(LeatherArmorMeta)x.getItemMeta();m.setColor(leatherColors[i%leatherColors.length]);m.setUnbreakable(true);x.setItemMeta(m);return x;}
    private Material woolFor(int i){Material[] w={Material.RED_WOOL,Material.BLUE_WOOL,Material.GREEN_WOOL,Material.YELLOW_WOOL,Material.PURPLE_WOOL,Material.ORANGE_WOOL,Material.WHITE_WOOL,Material.CYAN_WOOL,Material.PINK_WOOL,Material.LIME_WOOL,Material.GRAY_WOOL,Material.BROWN_WOOL};return w[i%w.length];}
    @EventHandler public void onInteractEntity(PlayerInteractAtEntityEvent e){
        if(!running||meeting)return; Body b=bodies.values().stream().filter(x->x.hit.getUniqueId().equals(e.getRightClicked().getUniqueId())).findFirst().orElse(null);if(b==null)return;e.setCancelled(true);bodyFindCounts.merge(e.getPlayer().getUniqueId(),1,Integer::sum);checkMission(e.getPlayer(),"FIND_BODY");e.getPlayer().sendMessage("§7死体を発見した：§f"+b.code+" §8| §f"+b.playerName+"§7。通報端末を使用すると通報できます。");
    }
    private Body nearestBody(Player p,double range){return bodies.values().stream().filter(b->b.location.getWorld().equals(p.getWorld())&&b.location.distanceSquared(p.getLocation())<=range*range).min(Comparator.comparingDouble(b->b.location.distanceSquared(p.getLocation()))).orElse(null);}
    private void startReport(Player p,Body b){ if(reports.containsKey(p.getUniqueId()))return; PlayerData pd=data.get(p.getUniqueId()); if(pd!=null&&"FORENSIC".equals(pd.ho))p.sendMessage("§b鑑識 §7死亡推定: "+Math.max(0,(System.currentTimeMillis()-b.diedAt)/1000)+"秒前 / 死因: §f"+b.cause); long due=System.currentTimeMillis()+getConfig().getLong("report-delay-seconds",10)*1000; reports.put(p.getUniqueId(),new PendingReport(p.getUniqueId(),b.id,due)); p.sendMessage("§e通報中… §7あと10秒生存すると会議が開かれます。"); }
    private void processReports(long now){ for(PendingReport r:new ArrayList<>(reports.values())) if(now>=r.due){ Player p=Bukkit.getPlayer(r.reporter); if(p!=null&&!p.isDead()&&p.getGameMode()!=GameMode.SPECTATOR){ Body body=bodies.get(r.body);if(body!=null)body.discovered=true;reports.clear(); reportCounts.merge(p.getUniqueId(),1,Integer::sum); checkMission(p,"REPORT_BODY"); startMeeting(); break;} else reports.remove(r.reporter); } }
    private void cancelReportsBy(UUID id){reports.remove(id);}

    private void startMeeting(){
        if(meeting)return; meeting=true;meetingChoicePhase=true;timerMark=System.currentTimeMillis();votes.clear();meetingAttendees.clear();meetingRefused.clear();meetingOrigins.clear();
        Bukkit.broadcastMessage("§c§l死体が通報された §7- 会議への参加を選択してください。");
        for(Player p:Bukkit.getOnlinePlayers()) if(data.containsKey(p.getUniqueId())&&!p.isDead()&&p.getGameMode()!=GameMode.SPECTATOR){meetingOrigins.put(p.getUniqueId(),p.getLocation().clone());p.setInvulnerable(true);p.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS,20*12,10,false,false,false));sendMeetingChoice(p);}
        Bukkit.getScheduler().runTaskLater(this,this::resolveMeetingChoices,20L*10);
    }
    private void sendMeetingChoice(Player p){TextComponent root=new TextComponent("§e会議が開かれた  ");TextComponent yes=new TextComponent("§a§l[参加する]");yes.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND,"/osw meetingjoin yes"));TextComponent no=new TextComponent(" §c§l[参加しない]");no.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND,"/osw meetingjoin no"));root.addExtra(yes);root.addExtra(no);p.spigot().sendMessage(root);}
    private void chooseMeeting(Player p,boolean join){if(!meeting||!meetingChoicePhase||!meetingOrigins.containsKey(p.getUniqueId()))return;meetingAttendees.remove(p.getUniqueId());meetingRefused.remove(p.getUniqueId());if(join)meetingAttendees.add(p.getUniqueId());else meetingRefused.add(p.getUniqueId());p.sendMessage(join?"§a会議に参加します。":"§7会議への参加を拒否しました。あなたは『行方不明』として扱われます。");}
    private void resolveMeetingChoices(){if(!meeting)return;meetingChoicePhase=false;for(UUID id:meetingOrigins.keySet())if(!meetingAttendees.contains(id)&&!meetingRefused.contains(id))meetingRefused.add(id);Location loc=meetingLocation();for(Player p:Bukkit.getOnlinePlayers()){if(!data.containsKey(p.getUniqueId()))continue;p.removePotionEffect(PotionEffectType.BLINDNESS);if(meetingAttendees.contains(p.getUniqueId())){p.teleport(loc);openVoteGui(p);}}castBotVotes();int sec=getConfig().getInt("meeting-seconds",90);meetingTask=Bukkit.getScheduler().scheduleSyncDelayedTask(this,this::finishVote,sec*20L);}
    private void openVoteGui(Player p){Inventory inv=Bukkit.createInventory(null,54,"追放投票");int i=0;for(Map.Entry<UUID,PlayerData> en:data.entrySet()){UUID id=en.getKey();if(id.equals(p.getUniqueId()))continue;Player q=Bukkit.getPlayer(id);Body body=bodies.values().stream().filter(b->b.owner.equals(id)).findFirst().orElse(null);boolean dead=q==null||q.isDead()||q.getGameMode()==GameMode.SPECTATOR;boolean knownDead=dead&&body!=null&&body.discovered;if(knownDead)continue;ItemStack x=new ItemStack(Material.PLAYER_HEAD);SkullMeta m=(SkullMeta)x.getItemMeta();if(q!=null)m.setOwningPlayer(q);String status=meetingAttendees.contains(id)?"§a会議参加中":"§e行方不明";m.setDisplayName("§f"+en.getValue().code+" §8| §f"+(q!=null?q.getName():(body!=null?body.playerName:"不明")));m.setLore(List.of(status));m.getPersistentDataContainer().set(itemKey,PersistentDataType.STRING,"VOTE:"+id);x.setItemMeta(m);inv.setItem(i++,x);if(i>=45)break;}for(BotData b:bots.values())if(b.alive()&&i<45){ItemStack x=new ItemStack(woolFor(b.colorIndex));ItemMeta m=x.getItemMeta();m.setDisplayName("§f"+b.code+" §8| §f"+b.id);m.setLore(List.of("§e行方不明"));m.getPersistentDataContainer().set(itemKey,PersistentDataType.STRING,"VOTE:"+b.entity.getUniqueId());x.setItemMeta(m);inv.setItem(i++,x);}ItemStack skip=guiItem(Material.GRAY_DYE,"§7棄権",List.of());ItemMeta sm=skip.getItemMeta();sm.getPersistentDataContainer().set(itemKey,PersistentDataType.STRING,"VOTE:SKIP");skip.setItemMeta(sm);inv.setItem(53,skip);p.openInventory(inv);}
    @EventHandler public void onVoteGui(InventoryClickEvent e){if(!"追放投票".equals(e.getView().getTitle())||!(e.getWhoClicked() instanceof Player p))return;e.setCancelled(true);String v=id(e.getCurrentItem());if(v==null||!v.startsWith("VOTE:"))return;if(v.equals("VOTE:SKIP"))votes.remove(p.getUniqueId());else try{votes.put(p.getUniqueId(),UUID.fromString(v.substring(5)));}catch(Exception ignored){}p.closeInventory();p.sendMessage("§a投票を受け付けました。");}
    private void finishVote(){Map<UUID,Integer> c=new HashMap<>();votes.values().forEach(v->c.merge(v,1,Integer::sum));int max=c.values().stream().max(Integer::compare).orElse(0);List<UUID> top=new ArrayList<>();c.forEach((k,v)->{if(v==max)top.add(k);});if(max>0&&top.size()==1){UUID target=top.get(0);Body body=bodies.values().stream().filter(b->b.owner.equals(target)).findFirst().orElse(null);Player x=Bukkit.getPlayer(target);BotData bot=bots.get(target);boolean alreadyDead=(x!=null&&(x.isDead()||x.getGameMode()==GameMode.SPECTATOR))||(body!=null);if(alreadyDead){Bukkit.broadcastMessage("§7投票対象は§c既に死亡していた§7。追放者はいません。");if(body!=null)body.discovered=true;}else if(x!=null){Bukkit.broadcastMessage("§c"+code(x)+" §7| §f"+x.getName()+" §cが追放された。");x.setGameMode(GameMode.SPECTATOR);}else if(bot!=null&&bot.alive()){Bukkit.broadcastMessage("§c"+bot.code+" §7| §f"+bot.id+" §cが追放された。");bot.dead=true;if(bot.entity.isValid())bot.entity.remove();}}else Bukkit.broadcastMessage(max==0?"§7追放者なし":"§7同票のため追放者なし");meeting=false;meetingChoicePhase=false;votes.clear();for(Player p:Bukkit.getOnlinePlayers()){p.closeInventory();p.removePotionEffect(PotionEffectType.BLINDNESS);p.setInvulnerable(true);Location back=meetingOrigins.get(p.getUniqueId());if(back!=null&&meetingAttendees.contains(p.getUniqueId()))p.teleport(back);}meetingOrigins.clear();meetingAttendees.clear();meetingRefused.clear();Bukkit.broadcastMessage("§e3... 2... 1... §aゲーム再開");Bukkit.getScheduler().runTaskLater(this,()->{for(Player p:Bukkit.getOnlinePlayers())p.setInvulnerable(false);timerMark=System.currentTimeMillis();checkLastSurvivor();},60L);}
    @EventHandler(priority=EventPriority.HIGHEST) public void meetingSafety(EntityDamageEvent e){if(meeting&&e.getEntity() instanceof Player p&&data.containsKey(p.getUniqueId()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST) public void meetingMove(PlayerMoveEvent e){if(!meeting||meetingChoicePhase||!meetingAttendees.contains(e.getPlayer().getUniqueId())||e.getTo()==null)return;Location c=meetingLocation();double r=getConfig().getDouble("meeting-room-radius",10);if(!e.getTo().getWorld().equals(c.getWorld())||e.getTo().distanceSquared(c)>r*r)e.setTo(e.getFrom());}

    @EventHandler public void onDamage(EntityDamageByEntityEvent e){
        if(!running||meeting)return; if(!(e.getEntity() instanceof Player victim))return;
        Player attacker=null; if(e.getDamager() instanceof Player p)attacker=p; else if(e.getDamager() instanceof Projectile pr && pr.getShooter() instanceof Player p)attacker=p;
        if(attacker==null)return;
        lastCause.put(victim.getUniqueId(),new DamageCauseInfo(attacker.getUniqueId(),"PLAYER",System.currentTimeMillis()));
        String id=id(attacker.getInventory().getItemInMainHand());
        if("KNIFE".equals(id)){ lastCause.put(victim.getUniqueId(),new DamageCauseInfo(attacker.getUniqueId(),"BLADE",System.currentTimeMillis()));
            long until=meleeCooldown.getOrDefault(attacker.getUniqueId(),0L); if(System.currentTimeMillis()<until){e.setCancelled(true);attacker.sendActionBar("§cKNIFE COOLDOWN");return;}
            if(isBehind(attacker,victim)){e.setDamage(1000);}
            else e.setDamage(5.0);
        }
        if(hasPassive(victim,"BODY_ARMOR")){ e.setDamage(e.getDamage()*.7); damageArmor(victim); }
    }
    @EventHandler(priority=EventPriority.MONITOR, ignoreCancelled=true) public void onDamageMonitor(EntityDamageByEntityEvent e){
        if(!running||meeting||!(e.getEntity() instanceof Player victim))return;
        Player attacker=null; if(e.getDamager() instanceof Player p)attacker=p; else if(e.getDamager() instanceof Projectile pr&&pr.getShooter() instanceof Player p)attacker=p;
        if(attacker==null||attacker.equals(victim))return;
        if(victim.getHealth()-e.getFinalDamage()<=0.001){
            String weapon=id(attacker.getInventory().getItemInMainHand());
            if("KNIFE".equals(weapon)) meleeCooldown.put(attacker.getUniqueId(),System.currentTimeMillis()+getConfig().getLong("knife-kill-cooldown-seconds",30)*1000);
            killCounts.merge(attacker.getUniqueId(),1,Integer::sum); checkMission(attacker,"KILL_PLAYER");
        }
    }

    @EventHandler(priority=EventPriority.MONITOR, ignoreCancelled=true) public void onAnyDamage(EntityDamageEvent e){
        if(!running||!(e.getEntity() instanceof Player p))return;
        if(SIGNAL_GUI_TITLE.equals(p.getOpenInventory().getTitle())){signalSessions.remove(p.getUniqueId());p.closeInventory();p.sendMessage("§c攻撃を受けたためSignal入力が中断されました。");}
        if(e instanceof EntityDamageByEntityEvent by){ if(by.getDamager() instanceof Wolf w&&wildlife.contains(w.getUniqueId()))lastCause.put(p.getUniqueId(),new DamageCauseInfo(null,"WILDLIFE",System.currentTimeMillis())); return; }
        String t=switch(e.getCause()){case FALL->"落下";case FIRE,FIRE_TICK,LAVA,HOT_FLOOR->"炎";case DROWNING->"溺死";case BLOCK_EXPLOSION,ENTITY_EXPLOSION->"爆発";case SUFFOCATION,CRAMMING->"圧死";default->"環境";};
        DamageCauseInfo prev=lastCause.get(p.getUniqueId());UUID actor=(prev!=null&&prev.actor!=null&&System.currentTimeMillis()-prev.time<=8000)?prev.actor:null;lastCause.put(p.getUniqueId(),new DamageCauseInfo(actor,actor==null?t:"環境キル/"+t,System.currentTimeMillis()));
    }
    private boolean isBehind(Player a,Player v){ Vector facing=v.getLocation().getDirection().setY(0).normalize(); Vector toA=a.getLocation().toVector().subtract(v.getLocation().toVector()).setY(0).normalize(); return facing.dot(toA)<-0.5 && a.getLocation().distanceSquared(v.getLocation())<=9; }
    private void damageArmor(Player p){ ItemStack armor=findItem(p,"BODY_ARMOR"); if(armor==null)return; ItemMeta m=armor.getItemMeta(); if(m instanceof org.bukkit.inventory.meta.Damageable d){d.setDamage(d.getDamage()+1);armor.setItemMeta(m);if(d.getDamage()>=8){p.getInventory().removeItem(armor);p.sendMessage("§cBody Armor broke.");}} }

    @EventHandler public void onInteract(PlayerInteractEvent e){
        if(e.getHand()!=EquipmentSlot.HAND)return; Action a=e.getAction(); Player p=e.getPlayer();
        String held=id(p.getInventory().getItemInMainHand());
        if((a==Action.LEFT_CLICK_AIR||a==Action.LEFT_CLICK_BLOCK)&&"FIRST_AID".equals(held)){String m=aidMode.getOrDefault(p.getUniqueId(),"SELF").equals("SELF")?"TARGET":"SELF";aidMode.put(p.getUniqueId(),m);p.sendActionBar("§cFIRST AID §7MODE: §f"+m);e.setCancelled(true);return;}
        if(a!=Action.RIGHT_CLICK_AIR&&a!=Action.RIGHT_CLICK_BLOCK)return;
        if(e.getClickedBlock()!=null){Block b=e.getClickedBlock();
            if(running&&!meeting&&configuredBlock("breakers",b)){breakerOff=!breakerOff; checkMission(p,"USE_BREAKER"); Bukkit.broadcastMessage(breakerOff?"§4POWER DOWN":"§aPOWER RESTORED"); if(!breakerOff)for(Player q:Bukkit.getOnlinePlayers()){q.removePotionEffect(PotionEffectType.DARKNESS);q.removePotionEffect(PotionEffectType.NIGHT_VISION);} return;}
            if(running&&!meeting&&configuredBlock("emps",b)){double radius=mapCfg.getDouble("emp-settings."+blockKey(b)+".radius",getConfig().getDouble("emp-radius",30));long secs=mapCfg.getLong("emp-settings."+blockKey(b)+".seconds",getConfig().getLong("emp-seconds",60));empZones.add(new EmpZone(b.getWorld(),b.getLocation().add(.5,.5,.5),radius,System.currentTimeMillis()+secs*1000));checkMission(p,"USE_EMP");p.sendMessage("§5EMP activated.");return;}
            if(executionReady&&configuredBlock("execution-switch",b)){executeFinal(p);return;}
        }
        if(!running||meeting)return; ItemStack it=p.getInventory().getItemInMainHand(); String id=id(it); if(id==null)return;
        if("REPORTER".equals(id)){e.setCancelled(true);Body b=nearestBody(p,4);if(b!=null)startReport(p,b);else p.sendMessage("§7近くに通報できる死体がありません。");return;}
        switch(id){
            case "RADIO" -> openSignalGui(p);
            case "BACKPACK" -> useBackpack(p,it);
            case "SMOKE" -> useSmoke(p,it);
            case "FIRST_AID" -> useAid(p,it);
            case "HANDGUN" -> shootGun(p,it);
            case "STUN" -> shootStun(p,it);
            case "EMERGENCY_BATTERY" -> p.sendMessage("§eEmergency Battery ready: use it at a compatible powered device.");
        }
    }
    private final List<String> signalWords=List.of("ME","RED","BLUE","GREEN","YELLOW","PURPLE","ORANGE","WHITE","CYAN","DANGER","SAFE","HELP","TRUST","DOUBT","ATTACK","KILL","FOUND","BODY","BREAKER","EMP","NORTH","SOUTH","EAST","WEST","NOW","PAST","NOT","MAYBE","POINT","HIGH","LOW","WEAPON","FOLLOW","LAB","TOWER");
    private final Map<UUID,SignalSession> signalSessions=new HashMap<>();
    private final Map<UUID,GmPreset> gmPresets=new HashMap<>();
    private static final String GM_GUI_TITLE="§0OneSignalWolf GM設定";
    private static final String GM_PLAYER_TITLE="§0GM個別設定: ";
    private static final String SIGNAL_GUI_TITLE="§0OneSignal 通信機";
    private void openSignalGui(Player p){
        if(!running||meeting)return;
        SignalSession session=new SignalSession();
        signalSessions.put(p.getUniqueId(),session);
        renderSignalGui(p,session);
    }
    private void renderSignalGui(Player p,SignalSession session){
        Inventory inv=Bukkit.createInventory(null,54,SIGNAL_GUI_TITLE);
        inv.setItem(0,guiItem(Material.COMPASS,"§b送信先: §f"+session.target,List.of("§7クリックで送信先を変更","§7ALL または各カラーへ送信")));
        int max=signalMaxWords(p);
        for(int i=0;i<5;i++){
            String value=i<session.words.size()?session.words.get(i):"-";
            Material mat=i<max?Material.LIGHT_BLUE_STAINED_GLASS_PANE:Material.GRAY_STAINED_GLASS_PANE;
            inv.setItem(2+i,guiItem(mat,"§bWORD "+(i+1)+": §f"+value,List.of(i<max?"§7選択した単語が入ります":"§8このスロットは使用できません")));
        }
        inv.setItem(7,guiItem(Material.RED_DYE,"§cCLEAR",List.of("§7選択した単語をすべて消去")));
        inv.setItem(8,guiItem(Material.LIME_DYE,"§aSEND",List.of("§7Signalを送信","§7GUI操作中も攻撃を受けます")));
        int slot=9;
        for(String word:signalWords){
            inv.setItem(slot++,guiItem(Material.PAPER,"§f"+word,List.of("§7クリックして追加")));
        }
        while(slot<45)inv.setItem(slot++,guiItem(Material.BLACK_STAINED_GLASS_PANE," ",List.of()));
        inv.setItem(49,guiItem(inEmp(p.getLocation())?Material.REDSTONE_TORCH:Material.LIME_DYE,
                inEmp(p.getLocation())?"§c通信不能":"§a通信可能",
                List.of(inEmp(p.getLocation())?"§7EMPの影響下では送受信できません":"§7現在Signalを送信できます")));
        p.openInventory(inv);
    }
    private ItemStack guiItem(Material material,String name,List<String> lore){ItemStack x=new ItemStack(material);ItemMeta m=x.getItemMeta();m.setDisplayName(name);m.setLore(lore);x.setItemMeta(m);return x;}
    private int signalMaxWords(Player p){PlayerData d=data.get(p.getUniqueId());return 3+(hasHotbar(p,"SIGNAL_BOOSTER")?1:0)+(d!=null&&"SIGNALER".equals(d.ho)?1:0);}
    private List<String> signalTargets(Player p){
        List<String> out=new ArrayList<>();out.add("ALL");
        for(Player q:Bukkit.getOnlinePlayers())if(data.containsKey(q.getUniqueId())&&!q.equals(p))out.add(code(q));
        for(BotData b:bots.values())if(b.alive())out.add(b.code);
        return out;
    }
    private void cycleSignalTarget(Player p,SignalSession session){List<String> targets=signalTargets(p);int idx=targets.indexOf(session.target);session.target=targets.get((idx+1+targets.size())%targets.size());renderSignalGui(p,session);}
    @EventHandler public void onSignalGui(InventoryClickEvent e){
        if(!SIGNAL_GUI_TITLE.equals(e.getView().getTitle())||!(e.getWhoClicked() instanceof Player p))return;
        e.setCancelled(true); SignalSession session=signalSessions.computeIfAbsent(p.getUniqueId(),k->new SignalSession()); int slot=e.getRawSlot();
        if(slot==0){cycleSignalTarget(p,session);return;}
        if(slot==7){session.words.clear();renderSignalGui(p,session);return;}
        if(slot==8){
            if(session.words.isEmpty()){p.sendMessage("§c送信する単語を選択してください。");return;}
            if(inEmp(p.getLocation())){p.sendMessage("§cEMPの影響によりSignalを送信できません。");return;}
            sendSignal(p,session.target,session.words.toArray(String[]::new));signalSessions.remove(p.getUniqueId());p.closeInventory();return;
        }
        if(slot>=9&&slot<45){ItemStack x=e.getCurrentItem();if(x==null||x.getType()!=Material.PAPER||!x.hasItemMeta())return;String w=ChatColor.stripColor(x.getItemMeta().getDisplayName());int max=signalMaxWords(p);if(session.words.size()>=max){p.sendMessage("§cこれ以上単語を追加できません。");return;}session.words.add(w);renderSignalGui(p,session);}
    }
    @EventHandler public void onSignalClose(InventoryCloseEvent e){if(e.getPlayer() instanceof Player p&&SIGNAL_GUI_TITLE.equals(e.getView().getTitle()))Bukkit.getScheduler().runTask(this,()->{if(!SIGNAL_GUI_TITLE.equals(p.getOpenInventory().getTitle()))signalSessions.remove(p.getUniqueId());});}

    private void openGmGui(Player gm){
        Inventory inv=Bukkit.createInventory(null,54,GM_GUI_TITLE); int slot=0;
        for(Player target:Bukkit.getOnlinePlayers()){
            if(slot>=45)break; GmPreset pr=gmPresets.get(target.getUniqueId());
            ItemStack head=new ItemStack(Material.PLAYER_HEAD); SkullMeta sm=(SkullMeta)head.getItemMeta(); sm.setOwningPlayer(target);
            sm.setDisplayName("§f"+target.getName());
            sm.setLore(List.of("§7HO: "+(pr==null||pr.ho==null?"§eランダム":"§d"+pr.ho),"§7使命II: "+(pr==null||pr.mission==null?"§eランダム":"§6"+pr.mission),"§7カラー: "+(pr==null||pr.color==null?"§eランダム":"§f"+pr.color),"§aクリックで個別設定"));
            sm.getPersistentDataContainer().set(itemKey,PersistentDataType.STRING,"GM_PLAYER:"+target.getUniqueId());head.setItemMeta(sm);inv.setItem(slot++,head);
        }
        inv.setItem(45,guiItem(Material.ENDER_EYE,"§e全員ランダム",List.of("§7GM指定をすべて解除")));
        inv.setItem(49,guiItem(Material.LIME_CONCRETE,"§aゲーム開始",List.of("§7現在の指定内容で開始","§7未指定項目はランダム抽選")));
        inv.setItem(53,guiItem(Material.BOOK,"§b設定について",List.of("§7使命Iは全員共通: ゲーム終了まで生存","§7HO/使命II/カラーを個別指定できます")));
        gm.openInventory(inv);
    }
    private void openGmPlayerGui(Player gm,Player target){
        GmPreset pr=gmPresets.computeIfAbsent(target.getUniqueId(),k->new GmPreset());
        Inventory inv=Bukkit.createInventory(null,27,GM_PLAYER_TITLE+target.getName());
        ItemStack head=new ItemStack(Material.PLAYER_HEAD);SkullMeta sm=(SkullMeta)head.getItemMeta();sm.setOwningPlayer(target);sm.setDisplayName("§f"+target.getName());head.setItemMeta(sm);inv.setItem(4,head);
        inv.setItem(10,guiItem(Material.ENCHANTED_BOOK,"§dHO: §f"+(pr.ho==null?"ランダム":pr.ho),List.of("§7左クリック: 次へ","§7右クリック: ランダムへ")));
        inv.setItem(13,guiItem(Material.WRITABLE_BOOK,"§6使命II: §f"+(pr.mission==null?"ランダム":pr.mission),List.of("§7左クリック: 次へ","§7右クリック: ランダムへ","§8使命I: ゲーム終了まで生存（固定）")));
        inv.setItem(16,guiItem(Material.LEATHER_HELMET,"§bカラー: §f"+(pr.color==null?"ランダム":pr.color),List.of("§7左クリック: 次へ","§7右クリック: ランダムへ")));
        inv.setItem(18,guiItem(Material.ARROW,"§e一覧へ戻る",List.of()));
        inv.setItem(22,guiItem(Material.BARRIER,"§cこのプレイヤーをリセット",List.of("§7HO・使命II・カラーをランダムへ")));
        gm.openInventory(inv);
    }
    private String nextValue(String current,String[] values){if(current==null)return values[0];for(int i=0;i<values.length;i++)if(values[i].equals(current))return values[(i+1)%values.length];return values[0];}
    @EventHandler public void onGmGui(InventoryClickEvent e){
        if(!(e.getWhoClicked() instanceof Player gm)||(!GM_GUI_TITLE.equals(e.getView().getTitle())&&!e.getView().getTitle().startsWith(GM_PLAYER_TITLE)))return;
        e.setCancelled(true);if(running||!gm.hasPermission("osw.admin"))return;
        if(GM_GUI_TITLE.equals(e.getView().getTitle())){
            if(e.getRawSlot()==45){gmPresets.clear();openGmGui(gm);return;}
            if(e.getRawSlot()==49){gm.closeInventory();startGame();return;}
            String sid=id(e.getCurrentItem());if(sid!=null&&sid.startsWith("GM_PLAYER:")){try{Player t=Bukkit.getPlayer(UUID.fromString(sid.substring(10)));if(t!=null)openGmPlayerGui(gm,t);}catch(Exception ignored){} }return;
        }
        String name=ChatColor.stripColor(e.getView().getTitle().substring(GM_PLAYER_TITLE.length()));Player target=Bukkit.getPlayerExact(name);if(target==null){gm.closeInventory();return;}GmPreset pr=gmPresets.computeIfAbsent(target.getUniqueId(),k->new GmPreset());
        boolean right=e.isRightClick();int slot=e.getRawSlot();
        if(slot==10)pr.ho=right?null:nextValue(pr.ho,hos);else if(slot==13)pr.mission=right?null:nextValue(pr.mission,missions);else if(slot==16)pr.color=right?null:nextValue(pr.color,colors);else if(slot==18){openGmGui(gm);return;}else if(slot==22){gmPresets.remove(target.getUniqueId());openGmPlayerGui(gm,target);return;}else return;
        openGmPlayerGui(gm,target);
    }

    private void useBackpack(Player p,ItemStack it){ PlayerData d=data.get(p.getUniqueId()); if(d.backpacks>=getConfig().getInt("backpack-max",3)){p.sendMessage("§cBACKPACK CAPACITY MAX");return;} d.backpacks++; consume(it); unlockSlots(p,d.backpacks*3); p.sendMessage("§aBACKPACK EQUIPPED §7Capacity +3"); }
    private void unlockSlots(Player p,int n){ for(int slot=9;slot<36;slot++){ if(slot-9<n && "LOCK".equals(id(p.getInventory().getItem(slot))))p.getInventory().setItem(slot,null); } }
    private void useSmoke(Player p,ItemStack it){ consume(it); double r=getConfig().getDouble("smoke-radius",7); long until=System.currentTimeMillis()+getConfig().getLong("smoke-seconds",10)*1000; smokeZones.add(new SmokeZone(p.getWorld(),p.getLocation(),r,until)); for(int i=0;i<250;i++){double x=(Math.random()-.5)*r*2,z=(Math.random()-.5)*r*2,y=Math.random()*3;p.getWorld().spawnParticle(Particle.CAMPFIRE_COSY_SMOKE,p.getLocation().add(x,y,z),1,0,0,0,0);} }
    private void useAid(Player p,ItemStack it){ Player target=p; if("TARGET".equals(aidMode.getOrDefault(p.getUniqueId(),"SELF"))){target=null;for(Entity en:p.getNearbyEntities(3,3,3))if(en instanceof Player q&&p.hasLineOfSight(q)){target=q;break;}if(target==null){p.sendMessage("§cNo target in range.");return;}} double max=Objects.requireNonNull(target.getAttribute(Attribute.GENERIC_MAX_HEALTH)).getValue(); target.setHealth(Math.min(max,target.getHealth()+6)); healCounts.merge(p.getUniqueId(),1,Integer::sum); checkMission(p,"HEAL_PLAYER"); p.sendMessage("§aFirst Aid: healed "+code(target)); consume(it); }
    private void shootGun(Player p,ItemStack gun){ ItemMeta m=gun.getItemMeta(); int ammo=m.getPersistentDataContainer().getOrDefault(ammoKey,PersistentDataType.INTEGER,getConfig().getInt("handgun-magazine",6)); if(ammo<=0){p.sendMessage("§cCLICK — EMPTY");return;} ammo--;m.getPersistentDataContainer().set(ammoKey,PersistentDataType.INTEGER,ammo);m.setDisplayName("§6Handgun §7["+ammo+"/"+getConfig().getInt("handgun-magazine",6)+"]");gun.setItemMeta(m);p.getWorld().playSound(p.getLocation(),Sound.ENTITY_GENERIC_EXPLODE,4f,1.6f); Arrow ar=p.launchProjectile(Arrow.class);ar.setDamage(8);ar.setPickupStatus(AbstractArrow.PickupStatus.DISALLOWED); }
    private void shootStun(Player p,ItemStack it){ Snowball s=p.launchProjectile(Snowball.class); s.getPersistentDataContainer().set(itemKey,PersistentDataType.STRING,"STUN_PROJECTILE"); consume(it); }
    @EventHandler public void onProjectileHit(ProjectileHitEvent e){ if(e.getEntity() instanceof Snowball s && "STUN_PROJECTILE".equals(s.getPersistentDataContainer().get(itemKey,PersistentDataType.STRING)) && e.getHitEntity() instanceof Player p){int ticks=getConfig().getInt("stun-seconds",3)*20;p.addPotionEffect(new PotionEffect(PotionEffectType.SLOW,ticks,10));p.addPotionEffect(new PotionEffect(PotionEffectType.WEAKNESS,ticks,10));p.addPotionEffect(new PotionEffect(PotionEffectType.JUMP,ticks,200));} }

    @EventHandler public void onPickup(EntityPickupItemEvent e){
        if(!running||!(e.getEntity() instanceof Player p))return;
        String sid=id(e.getItem().getItemStack());
        if(sid!=null&&!isBoundItem(e.getItem().getItemStack())&&!"WILL".equals(sid)&&!"LOCK".equals(sid)){
            specialCollectCounts.merge(p.getUniqueId(),1,Integer::sum); checkMission(p,"COLLECT_SPECIAL");
        }
        if("WILL".equals(sid)) willDrops.remove(e.getItem().getUniqueId());
    }
    @EventHandler public void onInventory(InventoryClickEvent e){ if(!running||!(e.getWhoClicked() instanceof Player p))return; if(e.getClickedInventory()==p.getInventory()&&e.getSlot()>=9&&e.getSlot()<36&&"LOCK".equals(id(e.getCurrentItem())))e.setCancelled(true); }
    @EventHandler public void onDrop(PlayerDropItemEvent e){ if(isBoundItem(e.getItemDrop().getItemStack()))e.setCancelled(true); }

    private void startGame(){
        running=true;meeting=false;breakerOff=false;data.clear();signalCounts.clear();healCounts.clear();bodyFindCounts.clear();killCounts.clear();reportCounts.clear();specialCollectCounts.clear();darknessSince.clear();bodies.values().forEach(Body::remove);bodies.clear();
        remainingGameSeconds=getConfig().getInt("game-duration-seconds",1800); timerMark=System.currentTimeMillis();
        if(gameBar!=null){gameBar.removeAll(); for(Player p:Bukkit.getOnlinePlayers())gameBar.addPlayer(p); updateGameBar();}
        List<Player> ps=new ArrayList<>(Bukkit.getOnlinePlayers()); Collections.shuffle(ps); int idx=0; for(Player pl:ps)setupPlayer(pl,idx++);
        for(BotData bot:bots.values()){bot.code=colors[idx%colors.length];bot.colorIndex=idx%colors.length;applyBotLook(bot);idx++;}
        Bukkit.broadcastMessage("§4§lOneSignalWolf START §7- participants: §f"+participantCount());
        checkLastSurvivor();
    }
    private void setupPlayer(Player p,int i){
        GmPreset preset=gmPresets.get(p.getUniqueId());
        String ho=(preset!=null&&preset.ho!=null)?preset.ho:hos[ThreadLocalRandom.current().nextInt(hos.length)];
        String mission=(preset!=null&&preset.mission!=null)?preset.mission:missions[ThreadLocalRandom.current().nextInt(missions.length)];
        int ci=i%colors.length;
        if(preset!=null&&preset.color!=null){for(int n=0;n<colors.length;n++)if(colors[n].equalsIgnoreCase(preset.color)){ci=n;break;}}
        PlayerData d=new PlayerData(colors[ci],ci,ho,mission);data.put(p.getUniqueId(),d);p.getInventory().clear();p.setGameMode(GameMode.SURVIVAL);p.setHealth(Objects.requireNonNull(p.getAttribute(Attribute.GENERIC_MAX_HEALTH)).getValue());
        p.getInventory().setHelmet(coloredHelmet(d.colorIndex));p.getInventory().setItem(0,bound(Material.BOOK,"HO","§dHO: "+d.ho,List.of("§7Mission I: SURVIVE +5pt","§7Mission II: "+d.mission)));
        p.getInventory().setItem(1,bound(Material.COMPASS,"RADIO","§bOneSignal Radio",List.of("§7/osw signal WORD WORD WORD")));
        p.getInventory().setItem(2,bound(Material.WRITABLE_BOOK,"NOTE","§f個人ノート",List.of("§7死亡すると遺書になります")));
        p.getInventory().setItem(3,bound(Material.BELL,"REPORTER","§c通報端末",List.of("§7死体の近くで右クリックして通報")));
        for(int s=9;s<36;s++)p.getInventory().setItem(s,bound(Material.BLACK_STAINED_GLASS_PANE,"LOCK","§8Locked inventory slot",List.of("§7Use a Backpack to unlock")));
        if("GUARD".equals(d.ho))p.getInventory().setItem(4,item("BODY_ARMOR")); if("MEDIC".equals(d.ho))p.getInventory().setItem(4,item("FIRST_AID")); if("TRACKER".equals(d.ho))p.getInventory().setItem(4,item("TRACKER")); if("SIGNALER".equals(d.ho))p.getInventory().setItem(4,item("SIGNAL_BOOSTER")); if("ENGINEER".equals(d.ho))p.getInventory().setItem(4,item("EMERGENCY_BATTERY"));
        Team t=Bukkit.getScoreboardManager().getMainScoreboard().getTeam("osw_hidden_names");if(t!=null)t.addEntry(p.getName());
        p.sendMessage("§fYOU ARE §l"+d.code+" §7| HO: §d"+d.ho+" §7| Mission II: §e"+d.mission);
    }
    private ItemStack coloredHelmet(int i){ItemStack x=new ItemStack(Material.LEATHER_HELMET);LeatherArmorMeta m=(LeatherArmorMeta)x.getItemMeta();m.setColor(leatherColors[i%leatherColors.length]);m.setUnbreakable(true);m.setDisplayName("§fCode Helmet: "+colors[i%colors.length]);x.setItemMeta(m);return x;}

    private void sendSignal(Player p,String[] words){sendSignal(p,"ALL",words);}
    private void sendSignal(Player p,String target,String[] words){
        if(!running||meeting||inEmp(p.getLocation())){p.sendMessage("§cSignalを送信できません。");return;}
        int max=signalMaxWords(p);if(words.length>max){p.sendMessage("§c単語数が上限を超えています。最大: "+max);return;}
        signalCounts.merge(p.getUniqueId(),1,Integer::sum);checkMission(p,"SEND_3_SIGNALS");
        String msg="§b[SIGNAL] §f"+code(p)+" §7→ §f"+target+" §7: §f"+String.join(" / ",words).toUpperCase(Locale.ROOT);
        int delivered=0;
        for(Player q:Bukkit.getOnlinePlayers()){
            if(!data.containsKey(q.getUniqueId())||inEmp(q.getLocation()))continue;
            if("ALL".equalsIgnoreCase(target)||code(q).equalsIgnoreCase(target)){q.sendMessage(msg);delivered++;}
        }
        p.sendActionBar("§bSignal送信 §7→ §f"+target+" §7(受信: "+delivered+")");
    }
    private boolean inEmp(Location l){long n=System.currentTimeMillis();return empZones.stream().anyMatch(e->e.until>n&&e.world.equals(l.getWorld())&&e.center.distanceSquared(l)<=e.radius*e.radius);}

    @Override public boolean onCommand(CommandSender sender,Command command,String label,String[] args){
        if(args.length==0){sendHelp(sender);return true;}
        String sub=args[0].toLowerCase(Locale.ROOT); Player p=sender instanceof Player x?x:null;
        if(sub.equals("help")){sendHelp(sender);return true;}
        if(sub.equals("meetingjoin")&&p!=null&&args.length>=2){chooseMeeting(p,args[1].equalsIgnoreCase("yes")||args[1].equalsIgnoreCase("join"));return true;}
        if(sub.equals("setup")){if(!admin(sender))return true;if(p==null){sender.sendMessage("§cRun setup commands in-game.");return true;}return handleSetup(p,args);}
        if(sub.equals("gm")&&admin(sender)){if(p==null){sender.sendMessage("§cゲーム内で実行してください。");return true;}if(running){p.sendMessage("§cゲーム開始後はGM設定を変更できません。");return true;}if(args.length>=2&&args[1].equalsIgnoreCase("reset")){gmPresets.clear();p.sendMessage("§aGM指定をすべてランダムに戻しました。");return true;}openGmGui(p);return true;}
        if(sub.equals("start")&&admin(sender)){startGame();return true;} if(sub.equals("stop")&&admin(sender)){resetGame();sender.sendMessage("Stopped and reset.");return true;}
        if(sub.equals("testmode")&&admin(sender)){ if(args.length<2){sender.sendMessage("§eTEST MODE: "+(testMode?"ON":"OFF"));return true;} testMode=args[1].equalsIgnoreCase("on"); if(!testMode)removeAllBots(); sender.sendMessage("§eTEST MODE: "+(testMode?"ON":"OFF")); return true; }
        if(sub.equals("test")&&admin(sender)&&args.length>=3&&args[1].equalsIgnoreCase("start")){int target=Math.max(1,Integer.parseInt(args[2]));testMode=true;removeAllBots();int humans=Bukkit.getOnlinePlayers().size();spawnBots(Math.max(0,target-humans),p!=null?p.getLocation():Bukkit.getWorlds().get(0).getSpawnLocation());startGame();return true;}
        if(sub.equals("bot")&&admin(sender)){return handleBotCommand(sender,p,args);}
        if(p==null)return true;
        if(sub.equals("ho")){PlayerData d=data.get(p.getUniqueId());if(d!=null)p.sendMessage("§f"+d.code+" §7HO=§d"+d.ho+" §7Mission I=SURVIVE(+5) Mission II=§e"+d.mission+" §7Mission II Status="+(d.missionDone?"§aDONE":"§cPENDING")+" §7Points=§a"+d.points);return true;}
        if(sub.equals("point")){PlayerData d=data.get(p.getUniqueId());if(d!=null)p.sendMessage("§aPOINTS: "+d.points);return true;}
        if(sub.equals("signal")&&args.length>=2){sendSignal(p,Arrays.copyOfRange(args,1,args.length));return true;}
        if(sub.equals("breaker")&&admin(sender)){breakerOff=!breakerOff;if(!breakerOff)for(Player q:Bukkit.getOnlinePlayers()){q.removePotionEffect(PotionEffectType.DARKNESS);q.removePotionEffect(PotionEffectType.NIGHT_VISION);}Bukkit.broadcastMessage(breakerOff?"§4POWER DOWN":"§aPOWER RESTORED");return true;}
        if(sub.equals("emp")&&admin(sender)){empZones.add(new EmpZone(p.getWorld(),p.getLocation(),getConfig().getDouble("emp-radius",30),System.currentTimeMillis()+getConfig().getLong("emp-seconds",60)*1000));p.sendMessage("§5EMP activated.");return true;}
        if(sub.equals("meeting")&&admin(sender)){startMeeting();return true;}
        if(sub.equals("vote")&&args.length>=2&&meeting){Player t=findByCode(args[1]);if(t!=null){votes.put(p.getUniqueId(),t.getUniqueId());p.sendMessage("§aVote: "+code(t));}return true;}
        if(sub.equals("report")){Body b=nearestBody(p,4);if(b!=null)startReport(p,b);else p.sendMessage("§7近くに通報できる死体がありません。");return true;}
        if(sub.equals("item")&&admin(sender)&&args.length>=2){p.getInventory().addItem(item(args[1].toUpperCase(Locale.ROOT)));return true;}
        if(sub.equals("end")&&admin(sender)){endGame();return true;}
        return true;
    }
    private void checkMission(Player p,String event){
        PlayerData d=data.get(p.getUniqueId()); if(d==null||d.missionDone||!d.mission.equals(event))return;
        boolean ok=switch(event){
            case "SEND_3_SIGNALS" -> signalCounts.getOrDefault(p.getUniqueId(),0)>=3;
            case "HEAL_PLAYER" -> healCounts.getOrDefault(p.getUniqueId(),0)>=1;
            case "FIND_BODY" -> bodyFindCounts.getOrDefault(p.getUniqueId(),0)>=1;
            case "KILL_PLAYER" -> killCounts.getOrDefault(p.getUniqueId(),0)>=1;
            case "REPORT_BODY" -> reportCounts.getOrDefault(p.getUniqueId(),0)>=1;
            case "COLLECT_SPECIAL" -> specialCollectCounts.getOrDefault(p.getUniqueId(),0)>=1;
            case "USE_BREAKER","USE_EMP" -> true;
            case "SURVIVE_DARKNESS" -> darknessSince.containsKey(p.getUniqueId()) && System.currentTimeMillis()-darknessSince.get(p.getUniqueId())>=60000;
            default -> false;
        };
        if(ok){d.missionDone=true;d.points+=getConfig().getInt("mission-ii-points",4);p.sendMessage("§6MISSION II COMPLETE §a+"+getConfig().getInt("mission-ii-points",4)+"pt");}
    }
    private void expireWill(UUID entityId){ Item it=willDrops.remove(entityId); if(it!=null&&it.isValid())it.remove(); }
    private void processWillExpiry(){ willDrops.entrySet().removeIf(en->!en.getValue().isValid()); }
    private void resetGame(){running=false;meeting=false;meetingChoicePhase=false;meetingAttendees.clear();meetingRefused.clear();meetingOrigins.clear();breakerOff=false;executionReady=false;finalWinner=null;remainingGameSeconds=getConfig().getInt("game-duration-seconds",1800);timerMark=0L;if(gameBar!=null)gameBar.removeAll();empZones.clear();smokeZones.clear();reports.clear();votes.clear();for(Body b:bodies.values())b.remove();bodies.clear();for(UUID id:new HashSet<>(wildlife)){Entity en=Bukkit.getEntity(id);if(en!=null)en.remove();}wildlife.clear();for(Player p:Bukkit.getOnlinePlayers()){p.setInvulnerable(false);p.removePotionEffect(PotionEffectType.DARKNESS);p.removePotionEffect(PotionEffectType.NIGHT_VISION);p.getInventory().clear();p.getInventory().setHelmet(null);if(p.getGameMode()==GameMode.SPECTATOR)p.setGameMode(GameMode.SURVIVAL);}data.clear();tags.values().forEach(Entity::remove);tags.clear();removeAllBots();}
    private void endGame(){
        if(!running)return; List<? extends Player> alive=Bukkit.getOnlinePlayers().stream().filter(p->data.containsKey(p.getUniqueId())&&p.getGameMode()!=GameMode.SPECTATOR&&!p.isDead()).toList(); boolean all=alive.size()==data.size();
        for(Player p:alive){PlayerData d=data.get(p.getUniqueId());if(!d.survivalAwarded){d.points+=5;d.survivalAwarded=true;}} Player winner=data.entrySet().stream().sorted((a,b)->{int c=Integer.compare(b.getValue().points,a.getValue().points);if(c!=0)return c;c=Boolean.compare(b.getValue().missionDone,a.getValue().missionDone);if(c!=0)return c;Player ap=Bukkit.getPlayer(a.getKey()),bp=Bukkit.getPlayer(b.getKey());boolean aa=ap!=null&&ap.getGameMode()!=GameMode.SPECTATOR&&!ap.isDead(),ba=bp!=null&&bp.getGameMode()!=GameMode.SPECTATOR&&!bp.isDead();return Boolean.compare(ba,aa);}).map(e->Bukkit.getPlayer(e.getKey())).filter(Objects::nonNull).findFirst().orElse(null);
        if(all){Bukkit.broadcastMessage("§a§lALL SURVIVED");executionReady=false;}
        if(winner!=null)Bukkit.broadcastMessage("§6§lWINNER — "+code(winner)+" §f("+data.get(winner.getUniqueId()).points+"pt)");
        if(!all&&winner!=null){finalWinner=winner.getUniqueId();executionReady=true;prepareExecution(winner);Bukkit.broadcastMessage("§4FINAL EXECUTION READY §7- "+code(winner)+" may use the execution switch.");}
        running=false;
    }
    private void prepareExecution(Player winner){
        Location wl=parseLoc(mapCfg.getString("winner"));if(wl!=null)winner.teleport(wl);int i=1;
        for(Player p:Bukkit.getOnlinePlayers())if(data.containsKey(p.getUniqueId())&&!p.equals(winner)&&p.getGameMode()!=GameMode.SPECTATOR&&!p.isDead()){Location c=parseLoc(mapCfg.getString("cages."+i));if(c==null)c=parseLoc(mapCfg.getString("cages.1"));if(c!=null)p.teleport(c);p.setInvulnerable(true);i++;}
    }
    private void executeFinal(Player p){if(!executionReady||finalWinner==null||!p.getUniqueId().equals(finalWinner)){p.sendMessage("§cOnly the winner can use this switch.");return;}executionReady=false;Bukkit.broadcastMessage("§4§lEXECUTION");for(Player q:Bukkit.getOnlinePlayers())if(data.containsKey(q.getUniqueId())&&!q.getUniqueId().equals(finalWinner)&&q.getGameMode()!=GameMode.SPECTATOR){q.setInvulnerable(false);q.setHealth(0.0);}}


    private void tickGameTimer(long now){
        if(!running){timerMark=now;return;}
        if(meeting){timerMark=now;updateGameBar();return;}
        if(timerMark==0L){timerMark=now;return;}
        long elapsed=(now-timerMark)/1000L;
        if(elapsed<=0)return;
        remainingGameSeconds-= (int)elapsed; timerMark+=elapsed*1000L; updateGameBar();
        if(remainingGameSeconds<=0){remainingGameSeconds=0;endGame();}
    }
    private void updateGameBar(){
        if(gameBar==null)return; int total=Math.max(1,getConfig().getInt("game-duration-seconds",1800));
        int m=Math.max(0,remainingGameSeconds)/60,s=Math.max(0,remainingGameSeconds)%60;
        gameBar.setTitle("OneSignalWolf  §f"+String.format("%02d:%02d",m,s)+(meeting?" §e[会議中・停止]":""));
        gameBar.setProgress(Math.max(0.0,Math.min(1.0,remainingGameSeconds/(double)total)));
        gameBar.setVisible(running);
    }
    private int aliveHumanCount(){return (int)Bukkit.getOnlinePlayers().stream().filter(p->data.containsKey(p.getUniqueId())&&p.getGameMode()!=GameMode.SPECTATOR&&!p.isDead()).count();}
    private int aliveBotCount(){return (int)bots.values().stream().filter(BotData::alive).count();}
    private int participantCount(){return data.size()+bots.size();}
    private void checkLastSurvivor(){
        if(!running||meeting)return; int alive=aliveHumanCount()+aliveBotCount(); if(alive!=1)return;
        running=false;if(gameBar!=null)gameBar.setVisible(false);
        Player hp=Bukkit.getOnlinePlayers().stream().filter(p->data.containsKey(p.getUniqueId())&&p.getGameMode()!=GameMode.SPECTATOR&&!p.isDead()).findFirst().orElse(null);
        BotData bp=bots.values().stream().filter(BotData::alive).findFirst().orElse(null);
        String who=hp!=null?code(hp):(bp!=null?bp.code:"UNKNOWN");
        Bukkit.broadcastMessage("§4§l最後の生存者"); Bukkit.broadcastMessage("§6§l"+who+" — 一人勝ち");
        executionReady=false;finalWinner=hp==null?null:hp.getUniqueId();
    }
    private void spawnBots(int count,Location base){
        for(int i=0;i<count;i++){
            Location at=base.clone().add((i%4)*1.5,0,(i/4)*1.5);
            Zombie z=at.getWorld().spawn(at,Zombie.class);z.setAI(false);z.setSilent(true);z.setCanPickupItems(false);z.setRemoveWhenFarAway(false);z.setAdult();z.setCustomNameVisible(false);
            BotData bd=new BotData(z,"BOT-"+(bots.size()+1));bots.put(z.getUniqueId(),bd);applyBotLook(bd);
        }
    }
    private void applyBotLook(BotData b){if(b.entity==null||!b.entity.isValid())return; if(b.code==null)b.code="BOT";b.entity.setCustomName(b.code);if(b.entity.getEquipment()!=null)b.entity.getEquipment().setHelmet(coloredHelmet(b.colorIndex));}
    private void removeAllBots(){for(BotData b:new ArrayList<>(bots.values()))if(b.entity!=null&&b.entity.isValid())b.entity.remove();bots.clear();}
    private BotData findBot(String key){for(BotData b:bots.values())if(b.id.equalsIgnoreCase(key)||b.code.equalsIgnoreCase(key)||b.entity.getUniqueId().toString().startsWith(key))return b;return null;}
    private boolean handleBotCommand(CommandSender sender,Player p,String[] args){
        if(args.length<2){sender.sendMessage("§e/osw bot add [n] | remove [n|all] | list | kill <id> | tp <id>");return true;}
        String a=args[1].toLowerCase(Locale.ROOT);
        if(a.equals("add")){int n=args.length>=3?Integer.parseInt(args[2]):1;Location l=p!=null?p.getLocation():Bukkit.getWorlds().get(0).getSpawnLocation();spawnBots(Math.max(1,n),l);sender.sendMessage("§aBots: "+bots.size());return true;}
        if(a.equals("remove")){if(args.length>=3&&args[2].equalsIgnoreCase("all")){removeAllBots();}else{int n=args.length>=3?Integer.parseInt(args[2]):1;for(BotData b:new ArrayList<>(bots.values()).subList(0,Math.min(n,bots.size()))){if(b.entity.isValid())b.entity.remove();bots.remove(b.entity.getUniqueId());}}sender.sendMessage("§aBots: "+bots.size());checkLastSurvivor();return true;}
        if(a.equals("list")){sender.sendMessage("§eBots (alive "+aliveBotCount()+"/"+bots.size()+")");for(BotData b:bots.values())sender.sendMessage("§7- "+b.id+" §f"+b.code+" §7"+(b.alive()?"ALIVE":"DEAD"));return true;}
        if((a.equals("kill")||a.equals("tp"))&&args.length>=3){BotData b=findBot(args[2]);if(b==null){sender.sendMessage("§cBot not found.");return true;}if(a.equals("kill")){if(b.entity.isValid())b.entity.setHealth(0);else b.dead=true;}else if(p!=null&&b.entity.isValid())b.entity.teleport(p.getLocation());return true;}
        return true;
    }
    private void castBotVotes(){
        List<UUID> humanTargets=Bukkit.getOnlinePlayers().stream().filter(p->data.containsKey(p.getUniqueId())&&p.getGameMode()!=GameMode.SPECTATOR&&!p.isDead()).map(Player::getUniqueId).toList();
        if(humanTargets.isEmpty())return; for(BotData b:bots.values())if(b.alive()&&ThreadLocalRandom.current().nextDouble()<0.8)votes.put(b.entity.getUniqueId(),humanTargets.get(ThreadLocalRandom.current().nextInt(humanTargets.size())));
    }
    @EventHandler public void onBotDeath(EntityDeathEvent e){
        BotData b=bots.get(e.getEntity().getUniqueId());if(b==null)return;Location dl=e.getEntity().getLocation().clone();b.dead=true;e.getDrops().clear();e.setDroppedExp(0);spawnBotBody(b,dl);Bukkit.getScheduler().runTask(this,this::checkLastSurvivor);
    }

    private boolean handleSetup(Player p,String[] args){
        if(args.length<2){p.sendMessage("§e/osw setup breaker | emp [radius] [seconds] | wildlife <id> | meeting | cage <n> | winner | execution-switch | list | remove <type> [id] | tp <type> [id] | test <type>");return true;}
        String t=args[1].toLowerCase(Locale.ROOT);
        if(t.equals("breaker")){Block b=targetBlock(p);if(b==null){p.sendMessage("§cLook at the breaker lever/button.");return true;}addConfiguredBlock("breakers",b);p.spawnParticle(Particle.VILLAGER_HAPPY,b.getLocation().add(.5,.5,.5),20,.3,.3,.3);p.sendMessage("§aBreaker registered: "+blockKey(b));return true;}
        if(t.equals("emp")){Block b=targetBlock(p);if(b==null){p.sendMessage("§cLook at the EMP block.");return true;}addConfiguredBlock("emps",b);double r=args.length>=3?Double.parseDouble(args[2]):getConfig().getDouble("emp-radius",30);long sec=args.length>=4?Long.parseLong(args[3]):getConfig().getLong("emp-seconds",60);mapCfg.set("emp-settings."+blockKey(b)+".radius",r);mapCfg.set("emp-settings."+blockKey(b)+".seconds",sec);saveMapConfig();p.sendMessage("§aEMP registered: radius="+r+" duration="+sec+"s");return true;}
        if(t.equals("wildlife")&&args.length>=3){mapCfg.set("wildlife."+args[2],locString(p.getLocation()));saveMapConfig();p.sendMessage("§aWildlife spawn registered: "+args[2]);return true;}
        if(t.equals("meeting")){mapCfg.set("meeting",locString(p.getLocation()));saveMapConfig();p.sendMessage("§aMeeting location registered.");return true;}
        if(t.equals("cage")&&args.length>=3){mapCfg.set("cages."+args[2],locString(p.getLocation()));saveMapConfig();p.sendMessage("§aCage position "+args[2]+" registered.");return true;}
        if(t.equals("winner")){mapCfg.set("winner",locString(p.getLocation()));saveMapConfig();p.sendMessage("§aWinner position registered.");return true;}
        if(t.equals("execution-switch")){Block b=targetBlock(p);if(b==null){p.sendMessage("§cLook at the execution switch.");return true;}mapCfg.set("execution-switch",blockKey(b));saveMapConfig();p.sendMessage("§aExecution switch registered: "+blockKey(b));return true;}
        if(t.equals("list")){p.sendMessage("§e--- OneSignalWolf map.yml ---");p.sendMessage("§7Breakers: §f"+mapCfg.getStringList("breakers").size()+" §7EMPs: §f"+mapCfg.getStringList("emps").size()+" §7Wildlife: §f"+(mapCfg.getConfigurationSection("wildlife")==null?0:mapCfg.getConfigurationSection("wildlife").getKeys(false).size()));p.sendMessage("§7Meeting: §f"+(mapCfg.contains("meeting")?"SET":"NOT SET")+" §7Winner: §f"+(mapCfg.contains("winner")?"SET":"NOT SET")+" §7Execution switch: §f"+(mapCfg.contains("execution-switch")?"SET":"NOT SET"));return true;}
        if(t.equals("tp")&&args.length>=3){Location l=null;String id=args.length>=4?args[3]:"1";if(args[2].equalsIgnoreCase("meeting"))l=parseLoc(mapCfg.getString("meeting"));else if(args[2].equalsIgnoreCase("winner"))l=parseLoc(mapCfg.getString("winner"));else if(args[2].equalsIgnoreCase("cage"))l=parseLoc(mapCfg.getString("cages."+id));else if(args[2].equalsIgnoreCase("wildlife"))l=parseLoc(mapCfg.getString("wildlife."+id));if(l!=null)p.teleport(l);else p.sendMessage("§cLocation not found.");return true;}
        if(t.equals("remove")&&args.length>=3){String type=args[2].toLowerCase(Locale.ROOT);String id=args.length>=4?args[3]:null;if(type.equals("meeting")||type.equals("winner")||type.equals("execution-switch"))mapCfg.set(type,null);else if(type.equals("cage")&&id!=null)mapCfg.set("cages."+id,null);else if(type.equals("wildlife")&&id!=null)mapCfg.set("wildlife."+id,null);else{p.sendMessage("§cFor breaker/emp, look at the registered block and use: /osw setup remove-look "+type);return true;}saveMapConfig();p.sendMessage("§aRemoved.");return true;}
        if(t.equals("remove-look")&&args.length>=3){Block b=targetBlock(p);if(b==null)return true;String path=args[2].equalsIgnoreCase("emp")?"emps":"breakers";List<String> list=new ArrayList<>(mapCfg.getStringList(path));list.remove(blockKey(b));mapCfg.set(path,list);saveMapConfig();p.sendMessage("§aRemoved "+blockKey(b));return true;}
        if(t.equals("test")&&args.length>=3){String type=args[2].toLowerCase(Locale.ROOT);if(type.equals("meeting")){p.teleport(meetingLocation());return true;}if(type.equals("emp")){empZones.add(new EmpZone(p.getWorld(),p.getLocation(),getConfig().getDouble("emp-radius",30),System.currentTimeMillis()+10000));p.sendMessage("§5Test EMP: 10s");return true;}if(type.equals("breaker")){breakerOff=!breakerOff;p.sendMessage("§eBreaker test: "+(breakerOff?"OFF":"ON"));return true;}if(type.equals("wildlife")){String id=args.length>=4?args[3]:null;Location l=id==null?null:parseLoc(mapCfg.getString("wildlife."+id));if(l!=null){Wolf w=l.getWorld().spawn(l,Wolf.class);w.setAngry(true);p.sendMessage("§aTest wildlife spawned at "+id);}else p.sendMessage("§cUse /osw setup test wildlife <id>");return true;}if(type.equals("execution")){Player oldWinner=finalWinner==null?null:Bukkit.getPlayer(finalWinner);finalWinner=p.getUniqueId();executionReady=true;prepareExecution(p);p.sendMessage("§4Execution test ready. Use the configured switch.");return true;}}
        p.sendMessage("§cUnknown setup command.");return true;
    }

    private void sendHelp(CommandSender s){
        s.sendMessage("§6§l===== OneSignalWolf コマンド =====");
        s.sendMessage("§eゲーム §7/osw ho §8| §7/osw point §8| §7/osw report §8| §7/osw vote <COLOR>");
        if(s.hasPermission("osw.admin")){
            s.sendMessage("§e管理 §7/osw start §8| §7stop §8| §7end §8| §7meeting §8| §7breaker §8| §7emp");
            s.sendMessage("§eGM設定 §7/osw gm §8| §7/osw gm reset §8(開始前のHO・使命・カラー指定)");
            s.sendMessage("§eテスト §7/osw testmode <on|off> §8| §7/osw test start <人数> §8| §7/osw bot ...");
            s.sendMessage("§eアイテム §7/osw item <ID>");
            s.sendMessage("§eマップ §7/osw setup <...> §8(Tab補完対応)");
        }
        s.sendMessage("§bSignal §7通信機を右クリックしてGUIから送信します。§8(/osw signal はデバッグ用)");
        s.sendMessage("§7各階層で §fTAB §7を押すと利用可能な候補を表示します。");
    }

    private List<String> complete(String token, Collection<String> values){
        String q=token==null?"":token.toLowerCase(Locale.ROOT);
        return values.stream().filter(Objects::nonNull).filter(v->v.toLowerCase(Locale.ROOT).startsWith(q)).distinct().sorted(String.CASE_INSENSITIVE_ORDER).toList();
    }
    private List<String> sectionKeys(String path){
        if(mapCfg==null||mapCfg.getConfigurationSection(path)==null)return List.of();
        return new ArrayList<>(mapCfg.getConfigurationSection(path).getKeys(false));
    }
    private List<String> botIds(){return bots.values().stream().map(b->b.id).sorted(String.CASE_INSENSITIVE_ORDER).toList();}
    private List<String> voteCodes(){
        List<String> out=new ArrayList<>();
        for(Player q:Bukkit.getOnlinePlayers())if(data.containsKey(q.getUniqueId())&&q.getGameMode()!=GameMode.SPECTATOR&&!q.isDead())out.add(code(q));
        for(BotData b:bots.values())if(b.alive())out.add(b.code);
        return out;
    }

    @Override public List<String> onTabComplete(CommandSender sender,Command command,String alias,String[] args){
        boolean adm=sender.hasPermission("osw.admin");
        if(args.length==1){
            List<String> root=new ArrayList<>(List.of("help","ho","point","report","vote"));
            if(adm)root.addAll(List.of("start","stop","end","meeting","breaker","emp","item","testmode","test","bot","setup","signal","gm"));
            return complete(args[0],root);
        }
        String a=args[0].toLowerCase(Locale.ROOT);
        if(a.equals("gm")&&args.length==2)return complete(args[1],List.of("open","reset"));
        if(a.equals("vote")&&args.length==2)return complete(args[1],voteCodes());
        if(a.equals("testmode")&&args.length==2)return complete(args[1],List.of("on","off"));
        if(a.equals("test")){
            if(args.length==2)return complete(args[1],List.of("start"));
            if(args.length==3&&args[1].equalsIgnoreCase("start"))return complete(args[2],List.of("4","6","8","10","12"));
        }
        if(a.equals("bot")){
            if(args.length==2)return complete(args[1],List.of("add","remove","list","kill","tp"));
            if(args.length==3&&args[1].equalsIgnoreCase("add"))return complete(args[2],List.of("1","2","4","7"));
            if(args.length==3&&args[1].equalsIgnoreCase("remove")){List<String> x=new ArrayList<>(List.of("1","2","4","all"));return complete(args[2],x);}
            if(args.length==3&&(args[1].equalsIgnoreCase("kill")||args[1].equalsIgnoreCase("tp")))return complete(args[2],botIds());
        }
        if(a.equals("item")&&args.length==2)return complete(args[1],List.of("NIGHT_SCOPE","BACKPACK","SIGNAL_BOOSTER","EMERGENCY_BATTERY","FIRST_AID","BODY_ARMOR","TRACKER","FLASHLIGHT","KNIFE","SMOKE","HANDGUN","STUN","EMP_SHIELD"));
        if(a.equals("setup")){
            if(args.length==2)return complete(args[1],List.of("breaker","emp","wildlife","meeting","cage","winner","execution-switch","list","tp","remove","remove-look","test"));
            String b=args.length>1?args[1].toLowerCase(Locale.ROOT):"";
            if(b.equals("emp")){if(args.length==3)return complete(args[2],List.of("20","30","40","50"));if(args.length==4)return complete(args[3],List.of("30","60","90","120"));}
            if(b.equals("cage")&&args.length==3)return complete(args[2],List.of("1","2","3","4","5","6","7","8"));
            if((b.equals("tp")||b.equals("remove"))&&args.length==3)return complete(args[2],List.of("meeting","winner","cage","wildlife","execution-switch"));
            if((b.equals("tp")||b.equals("remove"))&&args.length==4){
                if(args[2].equalsIgnoreCase("cage"))return complete(args[3],sectionKeys("cages"));
                if(args[2].equalsIgnoreCase("wildlife"))return complete(args[3],sectionKeys("wildlife"));
            }
            if(b.equals("remove-look")&&args.length==3)return complete(args[2],List.of("breaker","emp"));
            if(b.equals("test")&&args.length==3)return complete(args[2],List.of("meeting","emp","breaker","wildlife","execution"));
            if(b.equals("test")&&args.length==4&&args[2].equalsIgnoreCase("wildlife"))return complete(args[3],sectionKeys("wildlife"));
        }
        return Collections.emptyList();
    }

    private boolean admin(CommandSender s){if(!s.hasPermission("osw.admin")){s.sendMessage("§cNo permission.");return false;}return true;}
    private Player findByCode(String c){return Bukkit.getOnlinePlayers().stream().filter(p->code(p).equalsIgnoreCase(c)).findFirst().orElse(null);} private String code(Player p){PlayerData d=data.get(p.getUniqueId());return d==null?p.getName():d.code;}

    private ItemStack item(String id){
        return switch(id){
            case "NIGHT_SCOPE"->special(Material.SPYGLASS,id,"§aNight Vision Scope",List.of("§7HOTBAR: see during blackout"));
            case "BACKPACK"->special(Material.BUNDLE,id,"§6Backpack",List.of("§7RIGHT CLICK: unlock +3 slots"));
            case "SIGNAL_BOOSTER"->special(Material.AMETHYST_SHARD,id,"§bSignal Booster",List.of("§7HOTBAR: +1 Signal word"));
            case "EMERGENCY_BATTERY"->special(Material.REDSTONE,id,"§eEmergency Battery",List.of("§7PASSIVE/device: extra powered use"));
            case "FIRST_AID"->special(Material.PAPER,id,"§cFirst Aid Kit",List.of("§7HAND: heal self/near target"));
            case "BODY_ARMOR"->special(Material.IRON_CHESTPLATE,id,"§7Body Armor",List.of("§7PASSIVE: physical damage -30%"));
            case "TRACKER"->special(Material.RECOVERY_COMPASS,id,"§aTracker",List.of("§7HOTBAR: recent nearby traces"));
            case "FLASHLIGHT"->special(Material.TORCH,id,"§eFlashlight",List.of("§7HAND: blackout visibility"));
            case "KNIFE"->special(Material.IRON_SWORD,id,"§fKnife",List.of("§7HAND: backstab is lethal"));
            case "SMOKE"->special(Material.FIREWORK_STAR,id,"§7Smoke Grenade",List.of("§7HAND: dense smoke screen"));
            case "HANDGUN"->{ItemStack x=special(Material.CROSSBOW,id,"§6Handgun §7[6/6]",List.of("§7HAND: loud, one magazine only"));ItemMeta m=x.getItemMeta();m.getPersistentDataContainer().set(ammoKey,PersistentDataType.INTEGER,getConfig().getInt("handgun-magazine",6));x.setItemMeta(m);yield x;}
            case "STUN"->special(Material.SNOWBALL,id,"§bStun Device",List.of("§7HAND: medium-range stun"));
            case "EMP_SHIELD"->special(Material.ECHO_SHARD,id,"§5EMP Shield",List.of("§7HOTBAR: detects EMP field"));
            default->special(Material.BARRIER,id,"§cUnknown: "+id,List.of());
        };
    }
    private ItemStack special(Material mat,String id,String name,List<String> lore){ItemStack x=new ItemStack(mat);ItemMeta m=x.getItemMeta();m.setDisplayName(name);m.setLore(lore);m.getPersistentDataContainer().set(itemKey,PersistentDataType.STRING,id);x.setItemMeta(m);return x;}
    private ItemStack bound(Material mat,String id,String name,List<String> lore){return special(mat,id,name,lore);}
    private String id(ItemStack x){if(x==null||x.getType()==Material.AIR||!x.hasItemMeta())return null;return x.getItemMeta().getPersistentDataContainer().get(itemKey,PersistentDataType.STRING);}
    private boolean isBoundItem(ItemStack x){String i=id(x);return "HO".equals(i)||"RADIO".equals(i)||"NOTE".equals(i)||"LOCK".equals(i);}
    private boolean hasHotbar(Player p,String wanted){for(int i=0;i<9;i++)if(wanted.equals(id(p.getInventory().getItem(i))))return true;return false;}
    private boolean hasHand(Player p,String wanted){return wanted.equals(id(p.getInventory().getItemInMainHand()))||wanted.equals(id(p.getInventory().getItemInOffHand()));}
    private boolean hasPassive(Player p,String wanted){return findItem(p,wanted)!=null;}
    private ItemStack findItem(Player p,String wanted){for(ItemStack x:p.getInventory().getContents())if(wanted.equals(id(x)))return x;return null;}
    private void consume(ItemStack x){if(x.getAmount()<=1)x.setAmount(0);else x.setAmount(x.getAmount()-1);}
    private ItemStack makeWill(ItemStack note,String code){ItemStack w=new ItemStack(Material.WRITTEN_BOOK);BookMeta wm=(BookMeta)w.getItemMeta();wm.setTitle(code+"'s Will");wm.setAuthor(code);if(note.getItemMeta() instanceof BookMeta bm)wm.setPages(bm.getPages());wm.getPersistentDataContainer().set(itemKey,PersistentDataType.STRING,"WILL");w.setItemMeta(wm);return w;}

    private static final class SignalSession{String target="ALL";final List<String> words=new ArrayList<>();}
    private static final class GmPreset{String ho;String mission;String color;}
    private static final class BotData{final Zombie entity;final String id;String code="BOT";int colorIndex=0;boolean dead=false;BotData(Zombie e,String i){entity=e;id=i;}boolean alive(){return !dead&&entity!=null&&entity.isValid()&&!entity.isDead();}}
    private static final class PlayerData{final String code;final int colorIndex;final String ho,mission;int points=0,backpacks=0;boolean missionDone=false,survivalAwarded=false;PlayerData(String c,int i,String h,String m){code=c;colorIndex=i;ho=h;mission=m;}}
    private static final class Body{final UUID id,owner;final String code,playerName;final List<ArmorStand> parts;final Interaction hit;final Location location;final long diedAt;final String cause;boolean discovered;final boolean bot;Body(UUID i,UUID o,String c,String n,List<ArmorStand> p,Interaction h,Location l,long d,String ca,boolean di,boolean bo){id=i;owner=o;code=c;playerName=n;parts=p;hit=h;location=l;diedAt=d;cause=ca;discovered=di;bot=bo;}void remove(){for(Entity e:parts)if(e.isValid())e.remove();if(hit!=null&&hit.isValid())hit.remove();}}
    private record PendingReport(UUID reporter,UUID body,long due){}
    private record EmpZone(World world,Location center,double radius,long until){}
    private record SmokeZone(World world,Location center,double radius,long until){}
    private record TrackPoint(Location loc,long time){}
    private record DamageCauseInfo(UUID actor,String type,long time){}
}
