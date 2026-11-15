package dev.mccomm.cell;

import dev.mccomm.cell.command.CarrierCommand;
import dev.mccomm.cell.command.CellCommand;
import dev.mccomm.cell.command.CommCommands;
import dev.mccomm.cell.command.TowerCommand;
import dev.mccomm.cell.comm.CallService;
import dev.mccomm.cell.comm.InternetService;
import dev.mccomm.cell.comm.SimService;
import dev.mccomm.cell.comm.SmsService;
import dev.mccomm.cell.economy.BillingService;
import dev.mccomm.cell.gui.CarrierMenu;
import dev.mccomm.cell.gui.ChatPrompt;
import dev.mccomm.cell.gui.DepositMenu;
import dev.mccomm.cell.gui.Menu;
import dev.mccomm.cell.gui.PhoneMenu;
import dev.mccomm.cell.signal.HudService;
import dev.mccomm.cell.signal.SignalService;
import dev.mccomm.cell.signal.SpeedTest;
import dev.mccomm.cell.storage.DataStore;
import dev.mccomm.cell.tower.TowerGui;
import dev.mccomm.cell.tower.TowerListener;
import dev.mccomm.cell.tower.TowerManager;
import dev.mccomm.cell.util.Msg;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.List;

public final class CellPlugin extends JavaPlugin {
    private Settings settings;
    private DataStore store;
    private TowerManager towers;
    private TowerGui gui;
    private TowerListener towerListener;
    private SignalService signal;
    private HudService hud;
    private SpeedTest speedTest;
    private SmsService sms;
    private CallService calls;
    private SimService sims;
    private ChatPrompt prompt;
    private PhoneMenu phone;
    private BillingService billing;
    private DepositMenu deposit;
    private CarrierMenu carrierMenu;
    private InternetService internet;
    private BukkitTask signalTask;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        settings = new Settings(getConfig(), getLogger());
        Msg.prefix(settings.prefix);

        store = new DataStore(this);
        store.load();
        towers = new TowerManager(this);
        gui = new TowerGui(this);
        towerListener = new TowerListener(this);
        signal = new SignalService(this);
        hud = new HudService(this);
        speedTest = new SpeedTest(this);
        sms = new SmsService(this);
        calls = new CallService(this);
        sims = new SimService(this);
        prompt = new ChatPrompt(this);
        phone = new PhoneMenu(this);
        billing = new BillingService(this);
        deposit = new DepositMenu(this);
        carrierMenu = new CarrierMenu(this);
        internet = new InternetService(this);
        billing.ensureDefaults();

        Bukkit.getPluginManager().registerEvents(gui, this);
        Bukkit.getPluginManager().registerEvents(towerListener, this);
        Bukkit.getPluginManager().registerEvents(calls, this);
        Bukkit.getPluginManager().registerEvents(prompt, this);
        Bukkit.getPluginManager().registerEvents(billing, this);
        Bukkit.getPluginManager().registerEvents(deposit, this);
        Bukkit.getPluginManager().registerEvents(internet, this);
        Bukkit.getPluginManager().registerEvents(new Menu.Listen(), this);

        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, e -> {
            var r = e.registrar();
            r.register("cell", "Open your phone", List.of("phone"), new CellCommand(this));
            r.register("carrier", "Create and manage a carrier", List.of("telecom"), new CarrierCommand(this));
            r.register("tower", "Build and manage cell towers", List.of(), new TowerCommand(this));
            r.register("text", "Send an SMS to a player", List.of("sms"), new CommCommands.Text(this));
            r.register("internet", "Chat on the internet (uses mobile data)", List.of("net"), new CommCommands.Internet(this));
            r.register("call", "Start, accept or end a call", List.of(), new CommCommands.Call(this));
            r.register("celladmin", "CellTowers admin tools", List.of(), new CommCommands.Admin(this));
        });

        startSignalTask();
        // Upkeep: validate signs/screens every 5 s, autosave every minute.
        Bukkit.getScheduler().runTaskTimer(this, towers::validateLoaded, 40L, 100L);
        Bukkit.getScheduler().runTaskTimer(this, store::saveIfDirty, 1200L, 1200L);
        long bill = settings.billingCheckSeconds * 20L;
        Bukkit.getScheduler().runTaskTimer(this, billing::tick, 100L, bill);
        getLogger().info("Loaded " + store.carriers().size() + " carriers and " + store.towers().size() + " towers.");
    }

    private void startSignalTask() {
        if (signalTask != null) signalTask.cancel();
        signalTask = Bukkit.getScheduler().runTaskTimer(this, signal::tick, 20L, settings.updateInterval);
    }

    @Override
    public void onDisable() {
        if (speedTest != null) speedTest.shutdown();
        if (hud != null) hud.shutdown();
        if (store != null) store.save();
    }

    public void reloadSettings() {
        reloadConfig();
        settings = new Settings(getConfig(), getLogger());
        Msg.prefix(settings.prefix);
        startSignalTask();
        towers.refreshAll();
    }

    public Settings settings() { return settings; }
    public DataStore store() { return store; }
    public TowerManager towers() { return towers; }
    public TowerGui gui() { return gui; }
    public TowerListener towerListener() { return towerListener; }
    public SignalService signal() { return signal; }
    public HudService hud() { return hud; }
    public SpeedTest speedTest() { return speedTest; }
    public SmsService sms() { return sms; }
    public CallService calls() { return calls; }
    public SimService sims() { return sims; }
    public ChatPrompt prompt() { return prompt; }
    public PhoneMenu phone() { return phone; }
    public BillingService billing() { return billing; }
    public DepositMenu deposit() { return deposit; }
    public CarrierMenu carrierMenu() { return carrierMenu; }
    public InternetService internet() { return internet; }
}
