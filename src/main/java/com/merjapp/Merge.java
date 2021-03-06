package com.merjapp;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.text.SimpleDateFormat;
import java.util.AbstractCollection;
import java.util.Date;
import java.util.Iterator;
import java.util.Vector;

public class Merge {
	private static final SimpleDateFormat LOG_DATE_FORMAT			= new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
	
	private static final String[] CHAT_LIST_INSERT_FIELDS_GROUP		= new String[] {"key_remote_jid", "message_table_id", "subject", "creation",
			"last_read_message_table_id", "last_read_receipt_sent_message_table_id", "archived", "sort_timestamp" };
	
	private static final String[] CHAT_LIST_INSERT_FIELDS_CONTACT	= new String[] {"key_remote_jid", "message_table_id", "subject",
		"last_read_message_table_id", "last_read_receipt_sent_message_table_id", "archived", "sort_timestamp" };
		
	private static final String[] CHAT_LIST_UPDATE_FIELDS			= new String[] {"message_table_id",	"last_read_mesage_table_id",
		"last_read_receipt_sent_message_table_id", "archived", "sort_timestamp"};

	private static final String[] OTHER_TABLES = {"message_thumbnails", "messages_quotes", "messages_links", "message_forwarded",
			"messages_vcards", "messages_vcards_jids", "missed_call_logs"};
		
	private static boolean DEBUG = false;
	private static boolean SIMULATION = false;
	private static boolean OT = false;

	private static final String OUTPUT_DATABASE		= "output.db";
	private static final String TEMP_DATABASE		= "temp.db";
	
	BufferedWriter logFile = null;

	private SQLiteJDBC oldSQL;
	private SQLiteJDBC newSQL;

	public <E> String joinStr(AbstractCollection<E> list, String delim) {
		StringBuffer buf = new StringBuffer();

		Iterator<E> iterator = list.iterator();
		while (iterator.hasNext()) {
			if (buf.length() != 0)
				buf.append(delim);
			buf.append(iterator.next().toString());
		}

		return buf.toString();
	}

	public void printToLog(String message, String... type) {
		String msgType = "INFO";
		if (type.length != 0)
			msgType = type[0];

		try {
			logFile.write("[" + LOG_DATE_FORMAT.format(new Date()) + "] <" + String.format("%-10s", msgType) + "> " + message + "\n");
		} catch (IOException e) {
			System.err.println("-E- Couldn't write to log file!");
		}
	}

	public void executeUpdate(SQLiteJDBC conn, String query)
			throws SQLException {
		if (DEBUG)
			printToLog(query, "DEBUG/SQL");
		if (!SIMULATION)
			conn.executeUpdate(query);
	}

	/**
	 * Formulates an insert/update query based on the fields which exist in the
	 * target table.
	 * 
	 * @param insert
	 * @param conn
	 * @param tableName
	 * @param fieldNames
	 * @param fieldValues
	 * @return
	 * @throws Exception
	 */
	private String formulateQuery(boolean insert, SQLiteJDBC conn, String tableName, String[] fieldNames, Object[] fieldValues, String... suffix) throws Exception {
		Vector<String> tableColumns = new Vector<String>();
		Vector<String> fieldList = new Vector<String>();
		Vector<String> valueList = new Vector<String>();

		ResultSet rs = conn.executeQuery("SELECT * FROM " + tableName + " LIMIT 1");
		ResultSetMetaData rsmd = rs.getMetaData();

		for (int i = 2; i < rsmd.getColumnCount() + 1; i++)
			tableColumns.add(rsmd.getColumnName(i));

		for (int i = 0; i < fieldValues.length; i++) {
			if (!tableColumns.contains(fieldNames[i]) || fieldValues[i] == null)
				continue;

			fieldList.add(fieldNames[i]);
			if (fieldValues[i] instanceof String)
				valueList.add("'" + fieldValues[i] + "'");
			else
				valueList.add(fieldValues[i].toString());
		}

		if (insert)
			return "INSERT INTO " + tableName + " (" + joinStr(fieldList, ", ") + ") VALUES (" + joinStr(valueList, ", ") + ")";
		else {
			Vector<String> updateList = new Vector<String>();
			for (int i = 0; i < fieldList.size(); i++)
				updateList.add(fieldList.get(i) + " = " + valueList.get(i));
			return "UPDATE " + tableName + " SET " + joinStr(updateList, ", ") + " " + suffix[0];
		}
	}

	/**
	 * Call the private functions to do the merge
	 * 
	 * @throws Exception
	 */
	public void doMerge() throws Exception {

		// Unite the messages in the new database and those in the old database
		prepareOldDB();

		// Attach new database to old
		oldSQL.prepareStatement("ATTACH DATABASE \"" + TEMP_DATABASE + "\" AS NEW").execute();

		// Copy all messages from old database to new
		copyContents("messages");
		if (OT) {
			for (int iTable = 0; iTable < OTHER_TABLES.length; iTable++) {
				copyContents(OTHER_TABLES[iTable]);
			}
		}

		// Get rid of all entries in chat_list table that refer to groups in
		// which there was no activity since the new database started to be used
		mergeChatListTable();
		
		System.out.println("Done!");
	}

	/**
	 * Copy content from table named 'tableName' of the 'new' database to the merged one
	 * 
	 * @param tableName
	 * @return
	 * @throws Exception
	 */
	private void copyContents(String tableName) throws Exception {
		// Extract names columns to see on which both old and new database agree
		Vector<String> oldColumns = new Vector<String>();
		Vector<String> newColumns = new Vector<String>();
		Vector<String> agreedColumns = new Vector<String>();
		Vector<Integer> agreedOrder = new Vector<Integer>();
		ResultSet rs;

		int start;
		if (tableName == "messages")
			start = 2;
		else
			start = 1;
		
		// Analyze old database messages table column names
		rs = oldSQL.executeQuery("SELECT * FROM " + tableName + " LIMIT 1");
		ResultSetMetaData oldMetaData = rs.getMetaData();

		for (int i = start; i < oldMetaData.getColumnCount() + 1; i++)
			oldColumns.add(oldMetaData.getColumnName(i));
		rs.close();

		// Analyze new database messages table column names
		rs = newSQL.executeQuery("SELECT * FROM " + tableName + " LIMIT 1");
		ResultSetMetaData newMetaData = rs.getMetaData();
		for (int i = start; i < newMetaData.getColumnCount() + 1; i++)
			newColumns.add(newMetaData.getColumnName(i));
		rs.close();
		

		for (int i = 0; i < oldColumns.size(); i++) {
			// Check if this column exists in old, and where
			int indexInNew = newColumns.indexOf(oldColumns.get(i));
			if (indexInNew != -1)
				agreedOrder.add(indexInNew);
			else if (DEBUG)
				printToLog("Column " + oldColumns.get(i) + " doesn't exist in new database, omitting...", "DEBUG/WRN");
		}

		for (int i = 0; i < agreedOrder.size(); i++)
			agreedColumns.add(newColumns.get(agreedOrder.get(i)));

		if (DEBUG) {
			for (int i = 0; i < newColumns.size(); i++)
				if (!agreedColumns.contains(newColumns.get(i)))
					printToLog("Column " + newColumns.get(i) + " doesn't exist in old database, omitting...", "DEBUG/WRN");
		}

		if (DEBUG) {
			printToLog(oldColumns.size() + " columns in old database '" + tableName + "' table: " + joinStr(oldColumns, ", "), "DEBUG");
			printToLog(newColumns.size() + " columns in new database '" + tableName + "' table: " + joinStr(newColumns, ", "), "DEBUG");
			printToLog(agreedColumns.size() + " columns in agreed database '" + tableName + "' table: " + joinStr(agreedColumns, ", "), "DEBUG");
		}
		
		// Copy the goddamn content :)
		printToLog("", "");
		printToLog("Moving content of " + tableName + "'s table from new database into old database!", "STAGE");
		String agreedFieldList = joinStr(agreedColumns, ", ");
		executeUpdate(oldSQL, "INSERT INTO " + tableName + " (" + agreedFieldList + ") SELECT " + agreedFieldList + " FROM NEW." + tableName);
	}
	
	/**
	 * Prepare the 'new' database by adding an offset where required to avoid collision with 
	 * the 'old' database during merge operation.
	 * 
	 * @return
	 * @throws Exception
	 */
	private void prepareOldDB() throws Exception, SQLException {
		printToLog("Preparing messages table in new database to be merged into old database...", "STAGE");
		ResultSet rs;

		rs = oldSQL.executeQuery("SELECT MAX(_id) FROM messages");
		int oldMaxID = rs.getInt("MAX(_id)");
		rs.close();

		// Remove the first message line (-1) in the new database
		executeUpdate(newSQL, "DELETE FROM messages" + " WHERE _id = 1");

		// Find the ID of the first message in the new database
		rs = newSQL.executeQuery("SELECT MIN(_id), MAX(_id) FROM messages");
		int newMinID = rs.getInt("MIN(_id)");
		int newMaxID = rs.getInt("MAX(_id)"); // never used except for debug
		rs.close();

		int msgOffset = Math.max(oldMaxID, newMaxID) + 1 - newMinID;

		if (DEBUG) {
			printToLog("Last message ID in old database is " + oldMaxID, "DEBUG");
			printToLog("First, Last message ID in new database is " + newMinID + ", " + newMaxID, "DEBUG");
			printToLog("Message offset is " + msgOffset, "DEBUG");
		}

		// Increase the _id in messages by offset, and also the message_table_id
		// field in chat_list, which refers to message numbers.
		printToLog("Updating message IDs in new database with offset from old database");
		executeUpdate(newSQL, "UPDATE messages SET _id = _id + " + msgOffset);
		executeUpdate(newSQL, "UPDATE chat_list SET message_table_id = message_table_id + " + msgOffset);

		// These two fields are only there in versions from around August 20th, 2014
		try {
			executeUpdate(newSQL, "UPDATE chat_list SET last_read_message_table_id = last_read_message_table_id + " + msgOffset);
			executeUpdate(newSQL, "UPDATE chat_list SET last_read_receipt_sent_message_table_id = last_read_receipt_sent_message_table_id + " + msgOffset);
		} catch (Exception e) {}

		// Remove all group creation and member join messages
		printToLog("Removing all unnecessary group chat creation/join messages");
		rs = newSQL.executeQuery("SELECT _id, status FROM messages ORDER by _id ASC");
		int lastToRemove = 0, currID;
		while (rs.next()) {
			currID = rs.getInt("_id");
			if (rs.getInt("status") != 6) {
				break;
			}
			lastToRemove = currID;
		}

		executeUpdate(newSQL, "DELETE FROM messages WHERE _id <= " + lastToRemove);
		
		/* TODO: update quoted_row_id in messages (references _id in messages_quotes)
		 *       to account for the added offset
		 *       
		 *       Are there any others that need updating?
		 */
		
		// Adjust offset for other tables' column(s)
		if (OT) {
			// For each table
			for (int iTable = 0; iTable < OTHER_TABLES.length; iTable++) {
				
				rs = oldSQL.executeQuery("SELECT * FROM " + OTHER_TABLES[iTable] + " LIMIT 1");
				ResultSetMetaData newMetaData = rs.getMetaData();
				// For each column required
				for (int iCol = 1; iCol <= newMetaData.getColumnCount(); iCol++) {
					if (newMetaData.getColumnName(iCol).equals("_id")) {
						rs = oldSQL.executeQuery("SELECT MAX(_id) FROM " + OTHER_TABLES[iTable]);
						int tableOffset = rs.getInt("MAX(_id)");
						printToLog("Table " + OTHER_TABLES[iTable] + " ==> Column _id ==> Offset = " + tableOffset);
						rs.close();
						printToLog("Updating " + OTHER_TABLES[iTable] + " IDs in new database with offset from old database");
						executeUpdate(newSQL, "UPDATE " + OTHER_TABLES[iTable] + " SET _id = _id + " + tableOffset);
					}
					if (newMetaData.getColumnName(iCol).equals("message_row_id")) {
						printToLog("Table " + OTHER_TABLES[iTable] + " ==> Column message_row_id ==> Offset = " + msgOffset);
						executeUpdate(newSQL, "UPDATE " + OTHER_TABLES[iTable] + " SET message_row_id = message_row_id + " + msgOffset);
					}
					if (newMetaData.getColumnName(iCol).equals("vcard_row_id")) {
						rs = oldSQL.executeQuery("SELECT MAX(vcard_row_id) FROM " + OTHER_TABLES[iTable]);
						int tableOffset = rs.getInt("MAX(vcard_row_id)");
						printToLog("Table " + OTHER_TABLES[iTable] + " ==> Column vcard_row_id ==> Offset = " + tableOffset);
						rs.close();
						executeUpdate(newSQL, "UPDATE " + OTHER_TABLES[iTable] + " SET vcard_row_id = vcard_row_id + " + tableOffset);
					}
				}
				rs.close();
			}
		}
	}

	/**
	 * Black magic / Voodoo on ChatList
	 * 
	 * @return
	 * @throws Exception
	 */
	public void mergeChatListTable() throws Exception {
		printToLog("", "");
		printToLog("Merging chat list from new database to old...", "STAGE");
		
		Long creation, sortTimestamp = null;
		Integer messageID, lastReadMessage = null, lastReadReceipt = null, archived = null; 
		String key, subject;
		ResultSet rs1 = null, rs2 = null;

		rs1 = newSQL.executeQuery("SELECT * FROM chat_list");
		while (rs1.next()) {

			printToLog("", "");

			key = rs1.getString("key_remote_jid");
			messageID = rs1.getInt("message_table_id");
			subject = rs1.getString("subject");
			creation = rs1.getLong("creation");
			
			// These fields are in newer versions of Whatsapp, in a version released around August 20th, 2014.
			try {
				lastReadMessage = rs1.getInt("last_read_message_table_id");
			} catch (SQLException e) {}
			try {
				lastReadReceipt = rs1.getInt("last_read_receipt_sent_message_table_id");
			} catch (SQLException e) {}
			try {
				archived = rs1.getInt("archived");
			} catch (SQLException e) {}
			try {
				sortTimestamp = rs1.getLong("sort_timestamp");
			} catch (SQLException e) {}

			Object[] valuesInsertGroup  = new Object[] {key, messageID, subject, creation, lastReadMessage, lastReadReceipt, archived, sortTimestamp};
			Object[] valuesInsertContact = new Object[] {key, messageID, subject, lastReadMessage, lastReadReceipt, archived, sortTimestamp};
			Object[] valuesUpdate = new Object[] {messageID, lastReadMessage, lastReadReceipt, archived, sortTimestamp};

			boolean isGroup = false;
			boolean existsInOld;

			rs2 = oldSQL.executeQuery("SELECT COUNT(*) FROM chat_list WHERE key_remote_jid = '" + key + "'");
			existsInOld = (rs2.getInt("COUNT(*)") == 1);

			// A non-null subject means this is a group chat
			if (subject != null) {
				printToLog("Found a group chat entry called '" + subject + "'");
				isGroup = true;

				rs2 = newSQL.executeQuery("SELECT COUNT(*) FROM messages WHERE key_remote_jid = '" + key + "' AND status <> 6");
				boolean noActivity = (rs2.getInt("COUNT(*)") == 0);
				rs2.close();

				if (noActivity) {
					printToLog("Group has no messages in new database...");

					if (existsInOld) {
						printToLog("Group exists in old database, no update needed");

					} else {
						printToLog("Group was created after moving to new database (doesn't exist in old) - adding to old database.", "ACTION");
						executeUpdate(oldSQL, formulateQuery(true, oldSQL, "chat_list", CHAT_LIST_INSERT_FIELDS_GROUP, valuesInsertGroup));
					}

					continue;

				} else {
					printToLog("Group has messages in new DB");
				}

			} else {
				printToLog("It's a contact (" + key.split("@")[0] + ")");
			}

			// Now it doesn't matter if it's a group chat or a contact
			String[] chatListInsertFields = isGroup ? CHAT_LIST_INSERT_FIELDS_GROUP : CHAT_LIST_INSERT_FIELDS_CONTACT;
			
			if (existsInOld) {
				printToLog((isGroup ? "Group" : "Contact") + " exists in old DB, updating last message...", "ACTION");
				executeUpdate(oldSQL, formulateQuery(false, oldSQL, "chat_list", CHAT_LIST_UPDATE_FIELDS, valuesUpdate, "WHERE key_remote_jid = '" + key + "'"));
			} else {
				printToLog((isGroup ? "Group" : "Contact") + " doesn't exist in old DB, inserting new chat_list entry", "ACTION");
				executeUpdate( oldSQL, formulateQuery(true, oldSQL, "chat_list", chatListInsertFields, isGroup ? valuesInsertGroup : valuesInsertContact));
			}

		}

		rs1.close();

	}

	/**
	 * Open SQLite database file from path 
	 * 
	 * @return SQLiteJDBC object
	 */
	private SQLiteJDBC openDatabase(String dbPath) {
		SQLiteJDBC sql = null;
		try {
			sql = new SQLiteJDBC(dbPath);
		} catch (SQLException e) {
			System.out.println("-E- Could not open database " + dbPath);
			System.exit(1);
		}
		return sql;
	}

	/**
	 * Prepare global database objects (oldSQL and newSQL)
	 */
	private Merge(String oldDBPath, String newDBPath) {

		File oldDBFile = new File(oldDBPath);
		File newDBFile = new File(newDBPath);
		
		if (!oldDBFile.exists()) {
			System.err.println("-E- Database " + oldDBPath + " cannot be found!");
			System.exit(1);
		}

		if (!newDBFile.exists()) {
			System.err.println("-E- Database " + newDBPath + " cannot be found!");
			System.exit(1);
		}

		// Copy the files aside
		try {
			Files.copy(oldDBFile.toPath(), new File(OUTPUT_DATABASE).toPath());
			Files.copy(newDBFile.toPath(), new File(TEMP_DATABASE).toPath());
		} catch (IOException e1) {
			System.err.println("-E- Could not make copies of databases");
			System.exit(1);
		}
		
		String logFileName = "merjapp." + new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss").format(new Date()) + ".log";
		try {
			FileWriter fstream = new FileWriter(logFileName, true);
			logFile = new BufferedWriter(fstream);
		} catch (IOException e) {
			System.err.println("-E- Couldn't open log file (" + e.getMessage() + ")");
			System.exit(1);
		}

		oldSQL = openDatabase(OUTPUT_DATABASE);
		newSQL = openDatabase(TEMP_DATABASE);
	}

	/**
	 * Final clean flushing global objects and deleting temporary files
	 */
	private void cleanFiles() {
		// Close log file
		try {
			logFile.flush();
			logFile.close();
		} catch (IOException e) {
			System.err.println("-E- Couldn't close log file!");
		}
		
		// Close SQL connections
		oldSQL.disconnect();
		newSQL.disconnect();
		
		// Remove temporary database
		new File(TEMP_DATABASE).delete();
	}

	
	public static void main(String args[]) throws Exception {

		String oldDBPath = null, newDBPath = null;

		for (int i = 0; i < args.length; i++) {
			if (args[i].equalsIgnoreCase("-debug"))
				DEBUG = true;
			else if (args[i].equalsIgnoreCase("-simulation"))
				SIMULATION = true;
			else if (args[i].equalsIgnoreCase("-other-tables"))
				OT = true;
			else if (args[i].equalsIgnoreCase("-old"))
				oldDBPath = args[++i];
			else if (args[i].equalsIgnoreCase("-new"))
				newDBPath = args[++i];
		}

		if (oldDBPath == null || newDBPath == null) {
			System.err.println("-E- Usage: merjapp <-debug> <-simulation> <-other-tables> -old [oldDB] -new [newDB]");
			System.exit(1);
		}

		Merge merge = new Merge(oldDBPath, newDBPath);
		merge.doMerge();
		merge.cleanFiles();
	}
	
}
