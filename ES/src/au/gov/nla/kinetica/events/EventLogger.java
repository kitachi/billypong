package au.gov.nla.kinetica.events;

/**

 EventLogger

 Updated on: 1/8/12
 Insert Events, and accumulated nums to the database.
 It is a singleton called by the EventSender.
 
 The accumulated nums are inserted into accumulator_shd table
 if there's already a row for the corresponding accumulated
 tote in the accumulator table, this is done to avoid update
 locks when multiple RDC processes try to update the same
 rows in the accumulator table.
 
 The rows in the accumulator_shd is then used to update the
 totes in the corresponding rows in the accumulator table 
 through a single process in synchronized manner.
 
 Constraint: should only have one instance of the EventLogger
             per JVM.

 java au.gov.nla.kinetica.events.EventLogger RDC.properties

 */

import java.io.ByteArrayInputStream;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

import java.text.SimpleDateFormat;
import java.util.Properties;
import java.util.Date;

import org.apache.commons.io.IOUtils;
import org.apache.log4j.BasicConfigurator;
import org.apache.log4j.Logger;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.sql.SQLException;

public class EventLogger {
	public static String DEFAULT_PROPERTIES = "/RDC.properties";
	public static String ALTERNATE_PROPERTIES = "RDC.properties";
	public static String PROPERTIES_PROPERTIES = "properties/RDC.properties";

	private static Properties _props;

	public static final String DISCARD_EVENT_PROPERTY = "discardEvent";
	public static final String ACCUMULATE_NUMBER_PROPERTY = "accumulateNumber";

	private static java.sql.Connection _dbconn;

	private static PreparedStatement _intervals_chk_ps;
	private static PreparedStatement _interval_ps;
	private static PreparedStatement _accum_select_ps;
	// private static PreparedStatement _accum_update_ps;
	private static PreparedStatement _accum_ext_insert_ps;
	private static PreparedStatement _accum_insert_ps;
	private static PreparedStatement _event_insert_ps;

	private static final String INTERVALS_CHECK = "select p_check_missing_intervals(?) from dual";
	public static final String INTERVAL_SELECT = "select id from interval i,"
			+ "(select distinct type, max(istart) over (partition by type) as istart "
			+ "from interval where istart <= ? and "
			+ "(iend is null or iend > ?)) i1 "
			+ "where i.type = i1.type and i.istart = i1.istart";
	private static final int IS_START = 1;
	private static final int IS_END = 2;

	private static final String ACCUM_SELECT = "select tote from accumulator "
			+ "where interval = ? and username = ? and service = ?";
	private static final int AS_INTERVAL = 1;
	private static final int AS_USERNAME = 2;
	private static final int AS_SERVICE = 3;

	private static final String ACCUM_INSERT = "insert into accumulator values (?, ?, ?, ?)";
	public static final String ACCUM_EXT_INSERT = "insert into accumulator_shd (username, service, interval, accumnum, last_status_change) values (?, ?, ?, ?, ?)";
	private static final int AI_USERNAME = 1;
	private static final int AI_SERVICE = 2;
	private static final int AI_INTERVAL = 3;
	private static final int AI_TOTE = 4;
	private static final int AI_STATUS_CHANGE = 5;

	private static final String EVENT_INSERT = "insert into event values (?, ?, ?, ?, ?, ?, ?, ?)";
	private static final int EV_USER = 1;
	private static final int EV_SERVICE = 2;
	private static final int EV_TIME = 3;
	private static final int EV_ADDRESS = 4;
	private static final int EV_INPUT = 5;
	private static final int EV_OUTPUT = 6;
	private static final int EV_STATUS = 7;
	private static final int EV_STATUS_CHANGE = 8;

	static String qName;
	static boolean verbose = false;
	
	private static FileOutputStream eventCache;
	private static StringBuffer data = new StringBuffer();

	static Logger debugLog;
	
	static {
		try {
			// For debugging LinkedQueue
			eventCache = new FileOutputStream("/var/tmp/qeventcache", true);
		} catch (FileNotFoundException e) {
			e.printStackTrace();
		}
	}
	

	static {
		try {
			initEventLogger(DEFAULT_PROPERTIES);
			initializeDBConn(_props);
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (SQLException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		
	}
	
	public static void main(String[] argv) {

		try {
			initEventLogger(DEFAULT_PROPERTIES);
			initializeDBConn(_props);	

			System.out.println("initialised, listening ...");

			while (testContinue()) {
				test();
			}

		} catch (Exception e) {
			System.out.println("yikes: " + e);
			e.printStackTrace();
		} finally {
			try {
				_dbconn.close();
			} catch (Exception e) {
				System.out.println("problem closing: " + e);
			}
		}

		if (verbose) {
			System.out.println("bye bye");
		}

	}

	static boolean testContinue() {
		return true;
	}

	static void logEvent(Event e) throws Exception {
		logEvent(e, 1);
	}

	static void logEvent(Event e, int accumNum) throws Exception {
		String user = e.user();
		String service = e.service();
		Date time = e.time();
		SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
		String _etime = sdf.format(time);
		
		data.setLength(0);
		// TODO: use stringbuffer here
		data.append(user).append("::").append(service).append("::").append(_etime).append("::").append(accumNum).append("\n");
		IOUtils.copy((InputStream) new ByteArrayInputStream(data.toString().getBytes()), eventCache);
	}

	static void test() throws Exception {
		Date d = new Date();
		System.out.println("date: " + d);
		Event e = new Event("elog_tester", "la:testService", d);
		logEvent(e);
	}

	static void accumulate(int iid, String user, String serv, int accumNum)
			throws Exception {

		/*
		 * if (verbose) { System.out.println("accumulating: " + iid + " " + user
		 * + " " + serv); }
		 */

		_accum_select_ps.setInt(AS_INTERVAL, iid);
		_accum_select_ps.setString(AS_USERNAME, user);
		_accum_select_ps.setString(AS_SERVICE, serv);
		ResultSet ars = _accum_select_ps.executeQuery();

		if (ars.next()) {
			int tote = ars.getInt(1);
			tote += accumNum;

			// debugLog.debug("bfr accum total is " + tote);
			// debugLog.debug("the update stmt is " + ACCUM_EXT_INSERT);
			// debugLog.debug("accum total is " + tote + ", accumnum is "
			//		+ accumNum);
			_accum_ext_insert_ps.setString(AI_USERNAME, user);
			_accum_ext_insert_ps.setString(AI_SERVICE, serv);
			_accum_ext_insert_ps.setInt(AI_INTERVAL, iid);
			_accum_ext_insert_ps.setInt(AI_TOTE, accumNum);
			_accum_ext_insert_ps.setTimestamp(AI_STATUS_CHANGE, new Timestamp(
					(new Date()).getTime()));
			_accum_ext_insert_ps.executeUpdate();
		} else {
			_accum_insert_ps.setString(AI_USERNAME, user);
			_accum_insert_ps.setString(AI_SERVICE, serv);
			_accum_insert_ps.setInt(AI_INTERVAL, iid);
			_accum_insert_ps.setInt(AI_TOTE, accumNum);
			_accum_insert_ps.executeUpdate();
		}
		ars.close();

	}

	public synchronized static void fixMissingIntervals(String ptInTime)
			throws SQLException {
		if (_dbconn != null) {
			_intervals_chk_ps = _dbconn.prepareStatement(INTERVALS_CHECK);
			_intervals_chk_ps.setString(IS_START, ptInTime);

			ResultSet rs = _intervals_chk_ps.executeQuery();

			if (rs.next()) {
				String msg = rs.getString(1);
				if (msg.trim().length() > 0) {
					debugLog.warn("[RDCEventLogger::WARN] " + msg);
				}
			}

			rs.close();
			rs = null;
			_intervals_chk_ps.close();
			_intervals_chk_ps = null;
		}
	}

	private static void initEventLogger(String propFile) throws IOException,
			SQLException {
		Properties p = new Properties();

		try {
			p.load(EventSender.class.getClassLoader().getResourceAsStream(
					propFile));
		} catch (Exception e) {
			try {
				p.load(new FileInputStream(propFile));
			} catch (Exception ex) {
				try {
					p.load(new FileInputStream(ALTERNATE_PROPERTIES));
				} catch (Exception ex1) {
					p.load(EventSender.class.getClassLoader()
							.getResourceAsStream(ALTERNATE_PROPERTIES));
				}
			}
		}
		_props = p;

		debugLog = Logger.getLogger(EventSender.class);
		BasicConfigurator.configure();
	}

	public synchronized static void initializeDBConn(Properties props) throws SQLException {


	}
	
	public synchronized static void closeDBConn() {


	}

}
