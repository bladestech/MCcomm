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
import dev.mccomm.cell.economy.PartnerService;
import dev.mccomm.cell.economy.ManufacturerService;
import dev.mccomm.cell.economy.CompanyService;
import dev.mccomm.cell.gui.CompanyMenu;
import dev.mccomm.cell.gui.SimulationMenu;
import dev.mccomm.cell.gui.ManufacturerMenu;
import dev.mccomm.cell.gui.CarrierMenu;
import dev.mccomm.cell.gui.ChatPrompt;
import dev.mccomm.cell.gui.DepositMenu;
import dev.mccomm.cell.gui.Menu;
import dev.mccomm.cell.gui.PhoneMenu;
import dev.mccomm.cell.signal.HudService;
import dev.mccomm.cell.signal.SignalService;
import dev.mccomm.cell.signal.SimulationService;
import dev.mccomm.cell.signal.SpeedTest;
import dev.mccomm.cell.storage.DataStore;
import dev.mccomm.cell.tower.TowerGui;
import dev.mccomm.cell.tower.TowerListener;
import dev.mccomm.cell.tower.TowerManager;
import dev.mccomm.cell.tower.TowerScreen;
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
    private TowerScreen screen;
    private TowerGui gui;
    private TowerListener towerListener;
    private SignalService signal;
    private HudService hud;
    private SimulationService simulation;
    private SpeedTest speedTest;
    private SmsService sms;
    private CallService calls;
    private SimService sims;
    private ChatPrompt prompt;
    private PhoneMenu phone;
    private BillingService billing;
    private PartnerService partners;
    private ManufacturerService manufacturers;
    private CompanyService companies;
    private CompanyMenu companyMenu;
    private SimulationMenu simulationMenu;
    private ManufacturerMenu manufacturerMenu;
    private DepositMenu deposit;
    private CarrierMenu carrierMenu;
    private InternetService internet;
    private dev.mccomm.cell.broadband.BroadbandService broadband;
    private dev.mccomm.cell.broadband.BroadbandMenu broadbandMenu;
    private dev.mccomm.cell.broadband.ComputerBrowser browser;
    private dev.mccomm.cell.workforce.WorkforceService workforce;
    private dev.mccomm.cell.economy.GroupService groups;
    private dev.mccomm.cell.economy.SaleService sales;
    private dev.mccomm.cell.gui.OpsMenu opsMenu;
    private dev.mccomm.cell.gui.GroupMenu groupMenu;
    private dev.mccomm.cell.market.MarketService market;
    private BukkitTask signalTask;
    private Object squaremap; // SquaremapHook when squaremap is installed (kept as Object so the class only loads then)

    @Override
    public void onEnable() {
        dev.mccomm.cell.util.YamlFiles.start(); // autosaves write in the background
        saveDefaultConfig();
        settings = new Settings(getConfig(), getLogger());
        Msg.prefix(settings.prefix);

        store = new DataStore(this);
        store.load();
        towers = new TowerManager(this);
        screen = new TowerScreen(this);
        gui = new TowerGui(this);
        towerListener = new TowerListener(this);
        signal = new SignalService(this);
        hud = new HudService(this);
        simulation = new SimulationService(this);
        speedTest = new SpeedTest(this);
        sms = new SmsService(this);
        calls = new CallService(this);
        sims = new SimService(this);
        prompt = new ChatPrompt(this);
        phone = new PhoneMenu(this);
        billing = new BillingService(this);
        partners = new PartnerService(this);
        manufacturers = new ManufacturerService(this);
        companies = new CompanyService(this);
        companyMenu = new CompanyMenu(this);
        simulationMenu = new SimulationMenu(this);
        manufacturerMenu = new ManufacturerMenu(this);
        deposit = new DepositMenu(this);
        carrierMenu = new CarrierMenu(this);
        internet = new InternetService(this);
        broadband = new dev.mccomm.cell.broadband.BroadbandService(this);
        broadband.store().load();
        broadbandMenu = new dev.mccomm.cell.broadband.BroadbandMenu(this);
        browser = new dev.mccomm.cell.broadband.ComputerBrowser(this);
        workforce = new dev.mccomm.cell.workforce.WorkforceService(this);
        workforce.load();
        groups = new dev.mccomm.cell.economy.GroupService(this);
        groups.load();
        sales = new dev.mccomm.cell.economy.SaleService(this);
        sales.load();
        opsMenu = new dev.mccomm.cell.gui.OpsMenu(this);
        groupMenu = new dev.mccomm.cell.gui.GroupMenu(this);
        market = new dev.mccomm.cell.market.MarketService(this);
        billing.ensureDefaults();
        manufacturers.setup();

        Bukkit.getPluginManager().registerEvents(gui, this);
        Bukkit.getPluginManager().registerEvents(towerListener, this);
        Bukkit.getPluginManager().registerEvents(calls, this);
        Bukkit.getPluginManager().registerEvents(prompt, this);
        Bukkit.getPluginManager().registerEvents(billing, this);
        Bukkit.getPluginManager().registerEvents(deposit, this);
        Bukkit.getPluginManager().registerEvents(internet, this);
        Bukkit.getPluginManager().registerEvents(new dev.mccomm.cell.broadband.BroadbandListener(this), this);
        Bukkit.getPluginManager().registerEvents(new Menu.Listen(), this);

        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, e -> {
            var r = e.registrar();
            r.register("cell", "Open your phone", List.of("phone"), new CellCommand(this));
            r.register("carrier", "Create and manage a carrier", List.of("telecom"), new CarrierCommand(this));
            r.register("tower", "Install and manage cell towers", List.of(), new TowerCommand(this));
            r.register("manufacturer", "Run a tower manufacturer", List.of("mfr"), new dev.mccomm.cell.command.ManufacturerCommand(this));
            r.register("text", "Send an SMS to a player", List.of("sms"), new CommCommands.Text(this));
            r.register("internet", "Chat on the internet (uses mobile data)", List.of("net"), new CommCommands.Internet(this));
            r.register("call", "Start, accept or end a call", List.of(), new CommCommands.Call(this));
            r.register("isp", "Start and run an internet service provider", List.of(), new dev.mccomm.cell.command.IspCommand(this));
            r.register("broadband", "Set up internet at home", List.of("wifi", "bb"), new dev.mccomm.cell.command.BroadbandCommand(this));
            r.register("cellsim", "Simulated users for roleplay", List.of(), new CommCommands.Sim(this));
            r.register("company", "Run a parent company that owns carriers, manufacturers and ISPs", List.of("group"), new dev.mccomm.cell.command.GroupCommand(this));
            r.register("celladmin", "MCcomm admin tools", List.of(), new CommCommands.Admin(this));
        });

        startSignalTask();
        // Upkeep: validate signs/screens every 5 s, autosave every minute.
        Bukkit.getScheduler().runTaskTimer(this, towers::validateLoaded, 40L, 100L);
        Bukkit.getScheduler().runTaskTimer(this, store::saveIfDirty, 1200L, 1200L);
        Bukkit.getScheduler().runTaskTimer(this, broadband::tick, 100L, 100L);
        Bukkit.getScheduler().runTaskTimer(this, broadband.store()::saveIfDirty, 1200L, 1200L);
        Bukkit.getScheduler().runTaskTimer(this, groups::saveIfDirty, 1200L, 1200L);
        Bukkit.getScheduler().runTaskTimer(this, sales::saveIfDirty, 1200L, 1200L);
        long bill = settings.billingCheckSeconds * 20L;
        Bukkit.getScheduler().runTaskTimer(this, billing::tick, 100L, bill);
        startSquaremap();
        getLogger().info("Loaded " + store.carriers().size() + " carriers and " + store.towers().size() + " towers.");
    }

    private void startSquaremap() {
        stopSquaremap();
        if (!settings.squaremapEnabled || !Bukkit.getPluginManager().isPluginEnabled("squaremap")) return;
        try {
            var hook = new dev.mccomm.cell.integration.SquaremapHook(this);
            hook.start();
            squaremap = hook;
        } catch (Throwable t) {
            getLogger().warning("Couldn't hook into squaremap (" + t + "). The map integration is off.");
        }
    }

    private void stopSquaremap() {
        if (squaremap instanceof dev.mccomm.cell.integration.SquaremapHook hook) hook.stop();
        squaremap = null;
    }

    private void startSignalTask() {
        if (signalTask != null) signalTask.cancel();
        signalTask = Bukkit.getScheduler().runTaskTimer(this, signal::tick, 20L, settings.updateInterval);
    }

    @Override
    public void onDisable() {
        dev.mccomm.cell.util.YamlFiles.stop(); // finish pending autosaves; the saves below run right away
        if (speedTest != null) speedTest.shutdown();
        if (broadband != null) {
            broadband.shutdown();
            broadband.store().save();
        }
        if (hud != null) hud.shutdown();
        if (workforce != null) workforce.shutdown();
        stopSquaremap();
        if (groups != null) groups.save();
        if (sales != null) sales.save();
        if (store != null) store.save();
    }

    public void reloadSettings() {
        reloadConfig();
        settings = new Settings(getConfig(), getLogger());
        Msg.prefix(settings.prefix);
        startSignalTask();
        manufacturers.setup();
        towers.refreshAll();
        startSquaremap();
    }

    public Settings settings() { return settings; }
    public DataStore store() { return store; }
    public TowerManager towers() { return towers; }
    public TowerScreen screen() { return screen; }
    public TowerGui gui() { return gui; }
    public TowerListener towerListener() { return towerListener; }
    public SignalService signal() { return signal; }
    public HudService hud() { return hud; }
    public SimulationService simulation() { return simulation; }
    public SpeedTest speedTest() { return speedTest; }
    public SmsService sms() { return sms; }
    public CallService calls() { return calls; }
    public SimService sims() { return sims; }
    public ChatPrompt prompt() { return prompt; }
    public PhoneMenu phone() { return phone; }
    public BillingService billing() { return billing; }
    public PartnerService partners() { return partners; }
    public ManufacturerService manufacturers() { return manufacturers; }
    public CompanyService companies() { return companies; }
    public CompanyMenu companyMenu() { return companyMenu; }
    public SimulationMenu simulationMenu() { return simulationMenu; }
    public ManufacturerMenu manufacturerMenu() { return manufacturerMenu; }
    public DepositMenu deposit() { return deposit; }
    public CarrierMenu carrierMenu() { return carrierMenu; }
    public InternetService internet() { return internet; }
    public dev.mccomm.cell.broadband.BroadbandService broadband() { return broadband; }
    public dev.mccomm.cell.broadband.BroadbandMenu broadbandMenu() { return broadbandMenu; }
    public dev.mccomm.cell.broadband.ComputerBrowser browser() { return browser; }
    public dev.mccomm.cell.workforce.WorkforceService workforce() { return workforce; }
    public dev.mccomm.cell.economy.GroupService groups() { return groups; }
    public dev.mccomm.cell.economy.SaleService sales() { return sales; }
    public dev.mccomm.cell.gui.OpsMenu opsMenu() { return opsMenu; }
    public dev.mccomm.cell.gui.GroupMenu groupMenu() { return groupMenu; }
    public dev.mccomm.cell.market.MarketService market() { return market; }
}
