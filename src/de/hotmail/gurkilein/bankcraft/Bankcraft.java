package de.hotmail.gurkilein.bankcraft;

import java.io.File;
import java.util.logging.Logger;

import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.permission.Permission;

import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

import de.hotmail.gurkilein.bankcraft.banking.BankingHandler;
import de.hotmail.gurkilein.bankcraft.banking.DebitorHandler;
import de.hotmail.gurkilein.bankcraft.banking.ExperienceBankingHandler;
import de.hotmail.gurkilein.bankcraft.banking.InteractionHandler;
import de.hotmail.gurkilein.bankcraft.banking.MoneyBankingHandler;
import de.hotmail.gurkilein.bankcraft.database.AccountDatabaseInterface;
import de.hotmail.gurkilein.bankcraft.database.DatabaseManagerInterface;
import de.hotmail.gurkilein.bankcraft.database.SignDatabaseInterface;
import de.hotmail.gurkilein.bankcraft.database.flatfile.DatabaseManagerFlatFile;
import de.hotmail.gurkilein.bankcraft.database.flatfile.ExperienceFlatFileInterface;
import de.hotmail.gurkilein.bankcraft.database.flatfile.MoneyFlatFileInterface;
import de.hotmail.gurkilein.bankcraft.database.flatfile.SignFlatFileInterface;
import de.hotmail.gurkilein.bankcraft.database.mysql.DatabaseManagerMysql;
import de.hotmail.gurkilein.bankcraft.database.mysql.ExperienceMysqlInterface;
import de.hotmail.gurkilein.bankcraft.database.mysql.MoneyMysqlInterface;
import de.hotmail.gurkilein.bankcraft.database.mysql.SignMysqlInterface;
import de.hotmail.gurkilein.bankcraft.tasks.InterestGrantingTask;
import de.hotmail.gurkilein.bankcraft.tasks.PlayerPositionTask;

public final class Bankcraft extends JavaPlugin{

	public static Logger log;
	public static Economy econ = null;
    public static Permission perms = null;
    
    private AccountDatabaseInterface<Double> moneyDatabaseInterface;
    private AccountDatabaseInterface<Integer> experienceDatabaseInterface;
    private SignDatabaseInterface signDatabaseInterface;
    private ConfigurationHandler configurationHandler;
    private SignHandler signHandler;
    private DebitorHandler debitorHandler;
    private DatabaseManagerInterface databaseManager;
	private BankingHandler<?>[] bankingHandlers;
	private InterestGrantingTask interestGrantingTask;
	private PlayerPositionTask playerPositionTask;
	private InteractionHandler interactionHandler;
	private static int taskIdInterest = -1;
	private static int taskIdPlayerPos = -1;

	
    @Override
    public void onEnable(){
    	log = getLogger();
    	log.info("Loading Bankcraft "+getDescription().getVersion()+"... ");
    	
    	//Create Bankcraft folder
    	(new File("plugins"+System.getProperty("file.separator")+"Bankcraft")).mkdir();
    	
    	
    	//Setup Vault for economy and permissions
        if (!setupEconomy() ) {
            log.severe(String.format("[%s] - Disabled due to no Vault dependency found!(Is Vault installed?)", getDescription().getName()));
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        setupPermissions();
        
        //Load Configuration
        configurationHandler = new ConfigurationHandler(this);
        
        //Setup Database
        if (configurationHandler.getString("database.typeOfDatabase").equalsIgnoreCase("mysql")) {
        	log.info("Using MYSQL as Datasource...");
        	databaseManager = new DatabaseManagerMysql(this);
        	moneyDatabaseInterface = new MoneyMysqlInterface(this);
        	experienceDatabaseInterface = new ExperienceMysqlInterface(this);
        	signDatabaseInterface = new SignMysqlInterface(this);
        } else {
        	//Go for FlatFile
        	log.info("Using FlatFile as Datasource...");
        	databaseManager = new DatabaseManagerFlatFile(this);
        	moneyDatabaseInterface = new MoneyFlatFileInterface(this);
        	experienceDatabaseInterface = new ExperienceFlatFileInterface(this);
        	signDatabaseInterface = new SignFlatFileInterface(this);
        }
        
    	OldDataImportHandler odih = new OldDataImportHandler(this);
    	
        //Importing data from bankcraft 1.0
        if (configurationHandler.getString("database.importOldData").equalsIgnoreCase("true")) {
        	log.info("Importing old data... ");
        	odih.importOldData();
        }
        
        //Importing data from bankcraft 2.2
        if (configurationHandler.getString("database.eliminateCaseSensitives").equalsIgnoreCase("true")) {
        	log.info("Removing case sensitive entries...");
        	odih.migratev2_3();
        }
        
        //Setup DebitorHandler
        debitorHandler = new DebitorHandler(this);
        
        //Setup BankingHandlers
        bankingHandlers = new BankingHandler[2];
        bankingHandlers[0] = new MoneyBankingHandler(this);
        bankingHandlers[1] = new ExperienceBankingHandler(this);
        
        
        //Setup SignHandler
        signHandler = new SignHandler(this);
        interactionHandler = new InteractionHandler(this);
        
        
    	//Register Listeners
    	PluginManager pm = getServer().getPluginManager();
    	pm.registerEvents(new MinecraftPlayerListener(this), this);
    	pm.registerEvents(new MinecraftBlockListener(this), this);
    	pm.registerEvents(new MinecraftChatListener(this), this);
    	MinecraftCommandListener bcl = new MinecraftCommandListener(this);
    	getCommand("bank").setExecutor(bcl);
    	getCommand("bankadmin").setExecutor(bcl);
    	getCommand("bc").setExecutor(bcl);
    	getCommand("bcadmin").setExecutor(bcl);
        
        
    	//Start Tasks
        toggleTimerTasks();
    	
    	log.info("Bankcraft has been successfully loaded!");
    }
 
	private void toggleTimerTasks() {
    	Integer timerInterest = Integer.parseInt(configurationHandler.getString("interest.timeBetweenInterestsInMinutes"));
    if (taskIdInterest != -1) {
    	getServer().getScheduler().cancelTask(taskIdInterest);
    	taskIdInterest = -1;
    	} else {
    	interestGrantingTask = new InterestGrantingTask(this, timerInterest);
    	taskIdInterest = getServer().getScheduler().scheduleSyncRepeatingTask(this, interestGrantingTask, 1200 ,1200);
    	}
    
	double timerPlayer = Double.parseDouble(configurationHandler.getString("general.secondsBetweenPlayerPositionChecks"));
	if (taskIdPlayerPos != -1) {
		getServer().getScheduler().cancelTask(taskIdPlayerPos);
		taskIdPlayerPos = -1;
		} else {
			if (Boolean.parseBoolean(configurationHandler.getString("general.enableAutoExitSigns"))) {
				playerPositionTask = new PlayerPositionTask(this);
				taskIdPlayerPos = getServer().getScheduler().scheduleSyncRepeatingTask(this, playerPositionTask, (long)timerPlayer*20 , (long)timerPlayer*20);
			}
		}
	}

	@Override
    public void onDisable() {
    	log.info("Bankcraft has been disabled");
    }	
    
    
    
    //Methods for setting up Vault
    private boolean setupEconomy() {
        if (getServer().getPluginManager().getPlugin("Vault") == null) {
            return false;
        }
        RegisteredServiceProvider<Economy> rsp = getServer().getServicesManager().getRegistration(Economy.class);
        if (rsp == null) {
            return false;
        }
        econ = rsp.getProvider();
        return econ != null;
    }
    
    private boolean setupPermissions() {
        RegisteredServiceProvider<Permission> rsp = getServer().getServicesManager().getRegistration(Permission.class);
        perms = rsp.getProvider();
        return perms != null;
    }
    
    
    //Getter for Database Interfaces
    public AccountDatabaseInterface<Double> getMoneyDatabaseInterface() {
    	return moneyDatabaseInterface;
    }
    
    public SignDatabaseInterface getSignDatabaseInterface() {
    	return signDatabaseInterface;
    }
    
    public AccountDatabaseInterface<Integer> getExperienceDatabaseInterface() {
    	return experienceDatabaseInterface;
    }

	public BankingHandler<?>[] getBankingHandlers() {
		return bankingHandlers;
	}

	public ConfigurationHandler getConfigurationHandler() {
		return configurationHandler;
	}

	public SignHandler getSignHandler() {
		return signHandler;
	}
	
	public DebitorHandler getDebitorHandler() {
		return debitorHandler;
	}
	
	public InterestGrantingTask getInterestGrantingTask() {
		return interestGrantingTask;
	}
	
	public DatabaseManagerInterface getDatabaseManagerInterface() {
		return databaseManager;
	}
	
	public InteractionHandler getInteractionHandler() {
		return interactionHandler;
	}
	
}
