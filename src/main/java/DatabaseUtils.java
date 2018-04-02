import java.security.KeyPair;
import java.sql.*;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

public class DatabaseUtils
{
    // form a database connection every time a function in this class is called
    // terribly inefficient, however I don't know a technique to establish a persistent connection
    private static Connection connection = null;
    static
    {
        try
        {
            Class.forName("org.postgresql.Driver");
        }
        catch (ClassNotFoundException e)
        {
            System.out.println("Unable to load PostgreSQL JDBC Driver! "
                    + "The driver needs to be included in your library path!");
            e.printStackTrace();
        }

        try
        {
            String uri  = System.getenv("dbURI");
            String user = System.getenv("dbUser");
            String pass = System.getenv("dbPassword");
            if (uri!=null && user!=null && pass!=null)
                connection = DriverManager.getConnection(uri, user, pass);
        }
        catch (SQLException e)
        {
            System.out.println("Connection Failed!");
            e.printStackTrace();
        }
    }

    /**
     * creates a new voter
     * @param fname  registrant's first name
     * @param lname  registrant's last name
     * @param pubKey unique public key to associate with the registration name
     * @return true if registration was successful
     */
    public static Boolean registerVoter(String pubKey, String fname, String lname)
    {
        if (connection == null) return false;
        String rst; PreparedStatement pst;
        try
        {   // create voter table if not exists
            rst = "CREATE TABLE IF NOT EXISTS voters (" +
                    "fname varchar (40) NOT NULL, " +
                    "lname varchar (40) NOT NULL, " +
                    "key varchar (344) PRIMARY KEY, " +
                    "UNIQUE (lname,fname)" +
                  ");";
            connection.prepareStatement(rst).executeUpdate();

            // insert new voter record into the table
            rst = "INSERT INTO voters VALUES(?, ?, ?);";
            pst = connection.prepareStatement(rst);
            pst.setString(1, fname);
            pst.setString(2, lname);
            pst.setString(3, pubKey);
            return 1 == pst.executeUpdate(); // return true if one entry was update
        }
        catch (SQLException e)
        {
            e.printStackTrace();
            return false;
        }
    }

    /**
     * retrieves list of currently registered voters
     * @return sorted list of voter names
     */
    public static List<String> getVoters()
    {
        String st; ResultSet res;
        List<String> list = new LinkedList<String>();
        if (connection == null) return list;
        try
        {
            st  = "SELECT * FROM voters;";
            res = connection.prepareStatement(st).executeQuery();
            while(res.next())
            {
                String name = res.getString("fname") + " " + res.getString("lname");
                list.add(name);
            }
            Collections.sort(list);
            return list;
        }
        catch (SQLException e)
        {
            e.printStackTrace();
            return list;
        }
    }

    public static List<String> getElections()
    {
    	String st; ResultSet res;
    	List<String> list = new LinkedList<String>();
    	if (connection == null) return list;
    	try{
    		st	= "SELECT * FROM elections WHERE active LIKE 'Y'";
    		res = connection.prepareStatement(st).executeQuery();
    		while(res.next()){
    			String election = res.getString("election_name") + " | " + res.getString("block_count") + 
    					"\n" + res.getString("public_key");
    			list.add(election);
    		}
    		Collections.sort(list);
    		return list;
    	}
    	catch(SQLException e)
    	{
    		e.printStackTrace();
    		return list;
    	}
    }

    public static List<String> getUpcomingElections()
    {
        String st; ResultSet res;
        List<String> list = new LinkedList<String>();
        if (connection == null) return list;
        try{
            st	= "SELECT * FROM elections WHERE active LIKE 'U'";
            res = connection.prepareStatement(st).executeQuery();
            while(res.next()){
                String election = res.getString("election_name") + " | " + res.getString("block_count") +
                        "\n" + res.getString("public_key");
                list.add(election);
            }
            Collections.sort(list);
            return list;
        }
        catch(SQLException e)
        {
            e.printStackTrace();
            return list;
        }
    }

    public static List<String> getClosedElections()
    {
        String st; ResultSet res;
        List<String> list = new LinkedList<String>();
        if (connection == null) return list;
        try{
            st	= "SELECT * FROM elections WHERE active LIKE 'N'";
            res = connection.prepareStatement(st).executeQuery();
            while(res.next()){
                String election = res.getString("election_name") + " | " + res.getString("block_count") +
                        "\n" + res.getString("public_key");
                list.add(election);
            }
            Collections.sort(list);
            return list;
        }
        catch(SQLException e)
        {
            e.printStackTrace();
            return list;
        }
    }
    
    /**
     * retrieves the list of all registered public keys
     * @return sorted list of registered 2048-bit RSA keys (base64url encoded)
     */
    public static List<String> getVoterPublicKeys()
    {
        String st; ResultSet res;
        List<String> list = new LinkedList<String>();
        if (connection == null) return list;
        try
        {
            st  = "SELECT * FROM voters;";
            res = connection.prepareStatement(st).executeQuery();
            while(res.next())
            {
                list.add(res.getString("key"));
            }
            Collections.sort(list); // sort list so as to hide ownership
            return list;
        }
        catch (SQLException e)
        {
            e.printStackTrace();
            return list;
        }
    }

    /**
     * creates a new table to hold an elections blockchain
     * adds an entry for that election blockchain to the elections table (for easier referencing)
     * inserts the genesis block (contains blockchain encryption key)
     * @param publicKey the encryption key (RSA-4096) for ballots urlbase64 encoded
     * @return true is election creation was successful
     */
    public static Boolean initializeElectionBlockchain(String publicKey)
    {
        if (connection == null) return false;
        String rst; PreparedStatement pst;
        long time;
        try
        {
            // create the new election block chain
            rst = "CREATE TABLE e"+publicKey.substring(0, 32)+" (" +
                    "_id BIGSERIAL PRIMARY KEY, " +            // arbitrary, unique ID
                    "block_no BIGINT NOT NULL, " +             // block number
                    "block_content VARCHAR(8192) NOT NULL, " +  // contents of the block OR election key -> urlbase64 encoded
                    "timestamp BIGINT NOT NULL," +                 // epoch time
                    "current_hash VARCHAR(512) NOT NULL" +      // hash(content||prev_hash||time) -> urlbase64 encoded
                    ");";
            connection.prepareStatement(rst).executeUpdate();

            // retrieve private key for signing
            String privateKey = retrievePrivateKey(publicKey);
            
            // insert the genesis block into the table
            rst = "INSERT INTO e"+publicKey.substring(0, 32)+" VALUES(?, ?, ?, ?, ?);";
            pst = connection.prepareStatement(rst);
            pst.setInt(1, (int)(Math.random()*10000));
            pst.setInt(2, 0);
            pst.setString(3, publicKey);
            time = System.currentTimeMillis();
            pst.setLong(4, time);
            pst.setString(5, "signed PK + time");   // TODO create function to generate hash and sign with public key
            pst.executeUpdate();

            // update block number in the elections table
            rst = "UPDATE elections SET block_count=? WHERE public_key=?;";
            pst = connection.prepareStatement(rst);
            pst.setInt(1, 1);
            pst.setString(2, publicKey);

            return 1 == pst.executeUpdate(); // return true if the entry was created
        }
        catch (SQLException e)
        {
            e.printStackTrace();
            return false;
        }
    }
    
    /**
     * Adds records to the Elections and PrivateKeys tables to create a new election.
     * Handles creating the key pair for the election.
     * @param electionName Identifier for the election
     * @param startDate Date on which the genesis block for the election should be created
     * @param duration Time between genesis and terminus for the election
     * @param electionKeys (optional) RSA-4096 key pair to utilize for the election 
     * @return True if the election is successfully created
     */
    public static Boolean createElection(String electionName, KeyPair electionKeys)
    {
    	if(connection == null) return false;
    	String rst; PreparedStatement pst;
    	KeyPair keys; String pk, sk;
    	try{
    		// create an elections table to hold meta-information
            // on existing election block chains
            rst = "CREATE TABLE IF NOT EXISTS elections (" +
                    "public_key VARCHAR(8192) PRIMARY KEY," + // public key -> urlbase64 encoded
                    "block_count BIGINT NOT NULL," +     	// next block number
                    "election_name VARCHAR(128)," +			// readable identifier, TODO unique?
                    "active CHAR(1)" +                      // Active Flag to identify if election is active.
                                                            // other useful information to keep handy?
                    ");";
            connection.prepareStatement(rst).executeUpdate();
            
            // create a separate table to store private keys
            rst = "CREATE TABLE IF NOT EXISTS private_keys (" +
            		"public_key VARCHAR(8192) PRIMARY KEY," +	// public key -> urlbase64 encoded -> [0:32]
            		"private_key VARCHAR(8192)" +			// private key -> urlbase64 encoded
            		");";	//TODO base64 encoding probably increases string length
            connection.prepareStatement(rst).executeUpdate();
            
            // generate or read a key pair for the election
            if (electionKeys == null)
            	keys = CryptoUtils.generateKeys();
            else
            	keys = electionKeys;
            pk = CryptoUtils.exportKey(keys.getPublic());
            sk = CryptoUtils.exportKey(keys.getPrivate());
            
            // debug output
            System.out.println("Election: " + electionName + "\nPK: " + pk);
            
            // store record for the Elections table
            rst = "INSERT INTO elections VALUES (?, ?, ?, 'U')";
            pst = connection.prepareStatement(rst);
            pst.setString(1, pk);
            pst.setInt(2, 0);
            pst.setString(3, electionName);
            pst.executeUpdate();
            
            // store record for the PrivateKeys table
            rst = "INSERT INTO private_keys VALUES (?, ?)";
            pst = connection.prepareStatement(rst);
            pst.setString(1, pk);
            pst.setString(2, sk);
            pst.executeUpdate();
            
            return true;
    	}
    	catch(SQLException e)
    	{
    		e.printStackTrace();
    		return false;
    	}
    }
    
    /**
     * Retrieves an election's private key from the private_keys table.
     * TODO This method is a security concern. Monitor closely. 
     * @param publicKey The public key corresponding to the election
     * @return The private key for the election, base 64 encoded
     */
    public static String retrievePrivateKey(String publicKey)
    {
    	if (connection == null) return null;
    	String rst; 
    	PreparedStatement pst;
    	ResultSet res;
    	try{
    		rst = "SELECT private_key FROM private_keys WHERE public_key=?";
    		pst = connection.prepareStatement(rst);
    		pst.setString(1, publicKey.substring(0,  32));
    		res = pst.executeQuery();
    		
    		if(res.next())
    		{
    			return res.getString("private_key");
    		}
    		else
    		{
    			return null;
    		}
    	}
    	catch(SQLException e){
    		System.err.println("Unable to retrieve private key.");
    		return null;
    	}
    }
    
    /**
     * Processes a valid ballot and adds it to an election's blockchain
     * @param ballot Base 64 encoded encrypted ballot
     * @param electionKey	Primary key to identify the election
     * @return True if the block was successfully added
     */
    public static boolean addToBlockchain(String ballot, String electionKey)
    {
    	if (connection == null) return false;
    	String rst; PreparedStatement pst; ResultSet res;
    	int blockCount; String prevHash; long time;
    	try
    	{
    		//TODO verify that the blockchain relation exists?
    		
    		// determine next block number
    		rst = "SELECT COUNT(*) AS count FROM e"+electionKey.substring(0, 32);
    		pst = connection.prepareStatement(rst);
    		res = pst.executeQuery();
    		if(res.next())
    			blockCount = res.getInt("count");
    		else
    			return false;
    		
    		//TEST
    		System.out.println(viewBlockchain(electionKey));
    		
    		// query last block's hash
    		rst = "SELECT current_hash FROM e"+electionKey.substring(0, 32)+" WHERE block_no=?";
    		pst = connection.prepareStatement(rst);
    		pst.setInt(1, blockCount - 1);
    		res = pst.executeQuery();
    		if(res.next())
    			prevHash = res.getString("current_hash");
    		else
    			return false;
    		
    		// insert new block
    		rst = "INSERT INTO e"+electionKey.substring(0, 32)+" VALUES (?,?,?,?,?)";
    		pst = connection.prepareStatement(rst);
    		pst.setInt(1, (int)(Math.random() * 10000));
    		pst.setInt(2, blockCount);
    		pst.setString(3, ballot);
    		time = System.currentTimeMillis();
    		pst.setLong(4, time);
    		pst.setString(5, CryptoUtils.calculateBlockHash(ballot, prevHash, time));
    		if (pst.executeUpdate() != 1)
    			return false;
    		
    		// update block count for election
    		rst = "UPDATE elections SET block_count = ? WHERE public_key = ?";
    		pst = connection.prepareStatement(rst);
    		pst.setInt(1, blockCount + 1);
    		pst.setString(2, electionKey);
    		if (pst.executeUpdate() != 1)
    			return false;    		
    		
    		return true;
    	}
    	catch(Exception e){
    		e.printStackTrace();
    		return false;
    	}
    }
    
    /**
     * Reads through an election blockchain and returns a formatted list of blocks.
     * @param electionKey Public key to identify an election
     * @return List of blocks, each represented as a String
     */
    public static List<String> viewBlockchain(String electionKey)
    {
    	if (connection == null) return null;
    	String rst; ResultSet res;
    	List<String> list = new LinkedList<String>();
    	try
    	{
    		rst = "SELECT * FROM e"+electionKey.substring(0, 32);
    		res = connection.prepareStatement(rst).executeQuery();
    		while(res.next()){
    			String block = res.getInt("_id") + " | " +
    					res.getInt("block_no") + " | " + 
    					res.getString("block_content") + " | " + 
    					res.getLong("timestamp") + " | " +
    					res.getString("current_hash");
    			list.add(block);
    		}
    		return list;
    	}
    	catch(Exception e){
    		e.printStackTrace();
    		return null;
    	}
    }
    
    /**
     * Creates the terminus block for an election, releasing the private key.
     * @param publicKey Public key to identify an election
     * @return True if terminus block is successfully added
     */
    public static boolean terminateBlockChain(String publicKey)
    {
    	if (connection == null) return false;
    	
    	try
    	{
    		//TODO
    		return true;
    	}
    	catch(Exception e){
    		e.printStackTrace();
    		return false;
    	}
    }
    
    /**
     * Reads through a terminated election to determine its results.
     * @param electionKey Public key to identify an election
     * @return Map of each candidate to their tallied vote count.
     */
    public static Map<String, Integer> evaluateBlockchain(String electionKey)
    {
    	if (connection == null) return null;
    	
    	try
    	{
    		//TODO
    		return null;
    	}
    	catch(Exception e){
    		e.printStackTrace();
    		return null;
    	}
    }
}
