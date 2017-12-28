/*
    GriefPrevention Server Plugin for Minecraft
    Copyright (C) 2012 Ryan Hamshire

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package me.ryanhamshire.GriefPrevention.data;

import java.io.*;
import java.nio.charset.Charset;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;

import me.ryanhamshire.GriefPrevention.CustomLogEntryTypes;
import me.ryanhamshire.GriefPrevention.GriefPrevention;
import me.ryanhamshire.GriefPrevention.UUIDFetcher;
import me.ryanhamshire.GriefPrevention.claim.Claim;
import me.ryanhamshire.GriefPrevention.player.PlayerData;
import org.bukkit.*;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import com.google.common.io.Files;

//manages data stored in the file system
public class FlatFileDataStore implements DataStore
{
    private long nextClaimID; //Holds next available claim ID.
	private final static String claimDataFolderPath = dataLayerFolderPath + File.separator + "ClaimData";
	private final static String nextClaimIdFilePath = claimDataFolderPath + File.separator + "_nextClaimID";
	private final static String schemaVersionFilePath = dataLayerFolderPath + File.separator + "_schemaVersion";
    private final static String playerDataFolderPath = dataLayerFolderPath + File.separator + "PlayerData";
    private final int latestSchemaVersion = 1;
	
	static boolean hasData()
	{
		File claimsDataFolder = new File(claimDataFolderPath);
		
		return claimsDataFolder.exists();
	}

	public long nextClaimID()
    {
        //TODO: atomic modification

        this.nextClaimID++;

        BufferedWriter outStream = null;

        try
        {
            //open the file and write the new value
            File nextClaimIdFile = new File(nextClaimIdFilePath);
            nextClaimIdFile.createNewFile();
            outStream = new BufferedWriter(new FileWriter(nextClaimIdFile));

            outStream.write(String.valueOf(this.nextClaimID));
        }

        //if any problem, log it
        catch(Exception e)
        {
            GriefPrevention.AddLogEntry("Unexpected exception saving next claim ID: " + e.getMessage());
            e.printStackTrace();
        }

        //close the file
        try
        {
            if(outStream != null) outStream.close();
        }
        catch(IOException exception) {}

        return nextClaimID;
    }

	FlatFileDataStore(GriefPrevention instance) throws Exception
	{
        //create data folders
        boolean newDataStore = false;
        File playerDataFolder = new File(playerDataFolderPath);
        File claimDataFolder = new File(claimDataFolderPath);
        if(!playerDataFolder.exists() || !claimDataFolder.exists())
        {
            newDataStore = true;
            playerDataFolder.mkdirs();
            claimDataFolder.mkdirs();
        }

        //load next claim id number TODO: sanity check with loaded claims
        File nextClaimIdFile = new File(nextClaimIdFilePath);
        if(nextClaimIdFile.exists())
        {
            BufferedReader inStream = null;
            try
            {
                inStream = new BufferedReader(new FileReader(nextClaimIdFile.getAbsolutePath()));

                //read the id
                String line = inStream.readLine();

                //try to parse into a long value
                this.nextClaimID = Long.parseLong(line);
            }
            catch(Exception e){ }

            try
            {
                if(inStream != null) inStream.close();
            }
            catch(IOException exception) {}
        }

        //load claims
        //get a list of all the files in the claims data folder
        files = claimDataFolder.listFiles();
        this.loadClaimData(files);
	}
	
	void loadClaimData(File [] files) throws Exception
	{
	    ConcurrentHashMap<Claim, Long> orphans = new ConcurrentHashMap<Claim, Long>();
        for(int i = 0; i < files.length; i++)
        {           
            if(files[i].isFile())  //avoids folders
            {
                //skip any file starting with an underscore, to avoid special files not representing land claims
                if(files[i].getName().startsWith("_")) continue;
                
                //the filename is the claim ID.  try to parse it
                long claimID;
                
                try
                {
                    claimID = Long.parseLong(files[i].getName().split("\\.")[0]);
                }
                catch(Exception e)
                {
                    //TODO: log
                    continue;
                }
                
                try
                {
                    Claim claim = this.loadClaim(files[i], out_parentID, claimID);
                    //this.addClaim(claim, false); //TODO: call ClaimManager
                }
                
                //if there's any problem with the file's content, log an error message and skip it
                catch(Exception e)
                {
                    StringWriter errors = new StringWriter();
                    e.printStackTrace(new PrintWriter(errors));
                    GriefPrevention.AddLogEntry(files[i].getName() + " " + errors.toString(), CustomLogEntryTypes.Exception);
                    continue;
                }
            }
        }
	}
	
	Claim loadClaim(File file, ArrayList<Long> out_parentID, long claimID) throws IOException, InvalidConfigurationException, Exception
	{
	    List<String> lines = Files.readLines(file, Charset.forName("UTF-8"));
        StringBuilder builder = new StringBuilder();
        for(String line : lines)
        {
            builder.append(line).append('\n');
        }
        
        return this.loadClaim(builder.toString(), out_parentID, file.lastModified(), claimID, Bukkit.getServer().getWorlds());
	}
	
	Claim loadClaim(String input, ArrayList<Long> out_parentID, long lastModifiedDate, long claimID, List<World> validWorlds) throws InvalidConfigurationException, Exception
	{
	    Claim claim = null;
	    YamlConfiguration yaml = new YamlConfiguration();
        yaml.loadFromString(input);
        
        //boundaries
        Location lesserBoundaryCorner = this.locationFromString(yaml.getString("Lesser Boundary Corner"), validWorlds);
        Location greaterBoundaryCorner = this.locationFromString(yaml.getString("Greater Boundary Corner"), validWorlds);
        
        //owner
        String ownerIdentifier = yaml.getString("Owner");
        UUID ownerID = null;
        if(!ownerIdentifier.isEmpty())
        {
            try
            {
                ownerID = UUID.fromString(ownerIdentifier);
            }
            catch(Exception ex)
            {
                GriefPrevention.AddLogEntry("Error - this is not a valid UUID: " + ownerIdentifier + ".");
                GriefPrevention.AddLogEntry("  Converted land claim to administrative @ " + lesserBoundaryCorner.toString());
            }
        }
        
        List<String> builders = yaml.getStringList("Builders");
        
        List<String> containers = yaml.getStringList("Containers");
        
        List<String> accessors = yaml.getStringList("Accessors");
        
        List<String> managers = yaml.getStringList("Managers");
        
        out_parentID.add(yaml.getLong("Parent Claim ID", -1L));
        
        //instantiate
        claim = new Claim(lesserBoundaryCorner, greaterBoundaryCorner, ownerID, builders, containers, accessors, managers, claimID);
        
        return claim;
	}
	
	String getYamlForClaim(Claim claim)
	{
        YamlConfiguration yaml = new YamlConfiguration();
        
        //boundaries
        yaml.set("Lesser Boundary Corner",  this.locationToString(claim.lesserBoundaryCorner));
        yaml.set("Greater Boundary Corner",  this.locationToString(claim.greaterBoundaryCorner));
        
        //owner
        String ownerID = "";
        if(claim.ownerID != null) ownerID = claim.ownerID.toString();
        yaml.set("Owner", ownerID);
        
        ArrayList<String> builders = new ArrayList<String>();
        ArrayList<String> containers = new ArrayList<String>();
        ArrayList<String> accessors = new ArrayList<String>();
        ArrayList<String> managers = new ArrayList<String>();
        claim.getPermissions(builders, containers, accessors, managers);
        
        yaml.set("Builders", builders);
        yaml.set("Containers", containers);
        yaml.set("Accessors", accessors);
        yaml.set("Managers", managers);
        
        return yaml.saveToString();
	}
	
	@Override
	public void writeClaimToStorage(Claim claim)
	{
		String claimID = String.valueOf(claim.getID());
		
		String yaml = this.getYamlForClaim(claim);
		
		try
		{
			//open the claim's file						
			File claimFile = new File(claimDataFolderPath + File.separator + claimID + ".yml");
			claimFile.createNewFile();
			Files.write(yaml.getBytes("UTF-8"), claimFile);
		}		
		
		//if any problem, log it
		catch(Exception e)
		{
		    StringWriter errors = new StringWriter();
            e.printStackTrace(new PrintWriter(errors));
            GriefPrevention.AddLogEntry(claimID + " " + errors.toString(), CustomLogEntryTypes.Exception);
		}
	}
	
	//deletes a claim from the file system
	@Override
	public void deleteClaimFromSecondaryStorage(Claim claim)
	{
		String claimID = String.valueOf(claim.getID());
		
		//remove from disk
		File claimFile = new File(claimDataFolderPath + File.separator + claimID + ".yml");
		if(claimFile.exists() && !claimFile.delete())
		{
			GriefPrevention.AddLogEntry("Error: Unable to delete claim file \"" + claimFile.getAbsolutePath() + "\".");
		}		
	}
	
	@Override
	synchronized PlayerData getPlayerDataFromStorage(UUID playerID)
	{
		File playerFile = new File(playerDataFolderPath + File.separator + playerID.toString());
					
		PlayerData playerData = new PlayerData();
		playerData.playerID = playerID;
		
		//if it exists as a file, read the file
		if(playerFile.exists())
		{			
			boolean needRetry = false;
			int retriesRemaining = 5;
			Exception latestException = null;
			do
    			{
    			try
    			{					
    				needRetry = false;
    			    
    			    //read the file content and immediately close it
    			    List<String> lines = Files.readLines(playerFile, Charset.forName("UTF-8"));
    			    Iterator<String> iterator = lines.iterator();
    				
    				//second line is accrued claim blocks
    				String accruedBlocksString = iterator.next();
    				
    				//convert that to a number and store it
    				playerData.setAccruedClaimBlocks(Integer.parseInt(accruedBlocksString));
    				
    				//third line is any bonus claim blocks granted by administrators
    				String bonusBlocksString = iterator.next();
    				
    				//convert that to a number and store it										
    				playerData.setBonusClaimBlocks(Integer.parseInt(bonusBlocksString));
    				
    				//fourth line is a double-semicolon-delimited list of claims, which is currently ignored
    				//String claimsString = inStream.readLine();
    				//iterator.next();
    			}
    				
    			//if there's any problem with the file's content, retry up to 5 times with 5 milliseconds between
    			catch(Exception e)
    			{
    				latestException = e;
    				needRetry = true;
    				retriesRemaining--;
    			}
    			
    			try
    			{
                    if(needRetry) Thread.sleep(5);
    			}
    			catch(InterruptedException exception) {}
    			
			}while(needRetry && retriesRemaining >= 0);
			
			//if last attempt failed, log information about the problem
			if(needRetry)
			{
			    StringWriter errors = new StringWriter();
	            latestException.printStackTrace(new PrintWriter(errors));
	            GriefPrevention.AddLogEntry(playerID + " " + errors.toString(), CustomLogEntryTypes.Exception);
			}
		}
			
		return playerData;
	}
	
	//saves changes to player data.
	@Override
	public void savePlayerDataSync(UUID playerID, PlayerData playerData)
	{
		//never save data for the "administrative" account.  null for claim owner ID indicates administrative account
		if(playerID == null) return;
		
		StringBuilder fileContent = new StringBuilder();
		try
		{
			//first line is accrued claim blocks
			fileContent.append(String.valueOf(playerData.getAccruedClaimBlocks()));
			fileContent.append("\n");			
			
			//second line is bonus claim blocks
			fileContent.append(String.valueOf(playerData.getBonusClaimBlocks()));
			fileContent.append("\n");
			
			//third line is blank
			fileContent.append("\n");
			
			//write data to file
            File playerDataFile = new File(playerDataFolderPath + File.separator + playerID.toString());
            Files.write(fileContent.toString().getBytes("UTF-8"), playerDataFile);
		}		
		
		//if any problem, log it
		catch(Exception e)
		{
			GriefPrevention.AddLogEntry("GriefPrevention: Unexpected exception saving data for player \"" + playerID.toString() + "\": " + e.getMessage());
			e.printStackTrace();
		}
	}

    @Override
    int getSchemaVersionFromStorage()
    {
        File schemaVersionFile = new File(schemaVersionFilePath);
        if(schemaVersionFile.exists())
        {
            BufferedReader inStream = null;
            int schemaVersion = 0;
            try
            {
                inStream = new BufferedReader(new FileReader(schemaVersionFile.getAbsolutePath()));
                
                //read the version number
                String line = inStream.readLine();
                
                //try to parse into an int value
                schemaVersion = Integer.parseInt(line);
            }
            catch(Exception e){ }
            
            try
            {
                if(inStream != null) inStream.close();                  
            }
            catch(IOException exception) {}
            
            return schemaVersion;
        }
        else
        {
            this.updateSchemaVersionInStorage(0);
            return 0;
        }
    }

    @Override
    void updateSchemaVersionInStorage(int versionToSet)
    {
        BufferedWriter outStream = null;
        
        try
        {
            //open the file and write the new value
            File schemaVersionFile = new File(schemaVersionFilePath);
            schemaVersionFile.createNewFile();
            outStream = new BufferedWriter(new FileWriter(schemaVersionFile));
            
            outStream.write(String.valueOf(versionToSet));
        }       
        
        //if any problem, log it
        catch(Exception e)
        {
            GriefPrevention.AddLogEntry("Unexpected exception saving schema version: " + e.getMessage());
        }
        
        //close the file
        try
        {
            if(outStream != null) outStream.close();
        }
        catch(IOException exception) {}
        
    }

    @Override
    public void savePlayerData(UUID playerID, PlayerData playerData)
    {
        new SavePlayerDataThread(playerID, playerData).start();
    }

    private class SavePlayerDataThread extends Thread
    {
        private UUID playerID;
        private PlayerData playerData;

        SavePlayerDataThread(UUID playerID, PlayerData playerData)
        {
            this.playerID = playerID;
            this.playerData = playerData;
        }

        public void run()
        {
            //ensure player data is already read from file before trying to save
            playerData.getAccruedClaimBlocks();
            playerData.getClaims();
            savePlayerDataSync(this.playerID, this.playerData);
        }
    }
}

