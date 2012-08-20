package au.gov.nla.kinetica.events;


import java.sql.SQLException;
import java.sql.Timestamp;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Properties;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.*;

import org.apache.log4j.BasicConfigurator;
import org.apache.log4j.Logger;
import org.apache.commons.io.FileUtils;

import au.gov.nla.kinetica.events.Event;

/**
 * EventSender sends events to the EventLogger queue.
 * 
 * At construction time an EventSender requires access to a properties file.
 * This file can be specified in the constructor or will default to a file
 * called rdc.properties in the classes directory. The properties file must
 * contain the following properties:
 * 
 * <ul>
 * <li>LDAPcontextFactory - class name of context factory
 * <li>LDAPhost - url of ldap host
 * <li>LDAPuser - dn of ldap user
 * <li>LDAPpass - password of ldap user
 * <li>JMSconnectionFactory - dn of jms connection factory
 * <li>EventLoggerQueue - dn of event logger queue
 * </ul>
 * 
 * Example properties:
 * 
 * <ul>
 * <li>LDAPcontextFactory=com.sun.jndi.ldap.LdapCtxFactory
 * <li>LDAPhost=www-devel.nla.gov.au:7389
 * <li>LDAPuser=cn=root,dc=nla,dc=gov,dc=au
 * <li>LDAPpass=xxxxxx
 * <li>JMSconnectionFactory=cn=mytamboonQCF,ou=imq,dc=nla,dc=gov,dc=au
 * <li>EventLoggerQueue=cn=logger,ou=EventQueues,ou=imq,dc=nla,dc=gov,dc=au
 * </ul>
 * 
 * <p>
 * The properties allow the EventSender to connect to an LDAP directory to
 * recover the JMS objects it needs to send events to the event logger queue.
 * This is done during initialisation, after which the connection to the LDAP
 * server is closed.
 * 
 * <p>
 * The EventSender holds an open connection to the EventLogger queue. This
 * allows for low overhead during operation. The various send methods will
 * return in under 10ms. But it does require that the connection be closed when
 * the EventSender is no longer required. This is done by calling the stop()
 * method.
 * 
 * <p>
 * Static methods are provided for clients who don't wish to manage the
 * connection themselves. Currently, these methods make a fresh connection per
 * call and then close it. This incurs overhead so calls to these methods will
 * typically take 100ms. We should consider implementing a pool for these static
 * methods.
 * 
 * =============================================================================
 * 11/07/12 - Modified EventSender to insert into:
 *              -  table accumulator if there's no row found in accumulator
 *                 according to the specified interval, username and service.
 *              -  table accumulator_shd (accumulator shawdow table) if there's
 *                 a row found in accumulator according to the specfied interval,
 *                 username and service.  The new row will then trigger an update
 *                 (through TRG_INS_ACCUMSHD) to the corresponding row containing 
 *                 the tote in the accumulator table.
 *          
 *          - The reason for eliminate db update stmt from RDC Java code is to avoid ora-06000 deadlocks
 *            on resources which has been seen occurring (during production 
 *            deployment of the new RDCEvent environment) as the high frequency 
 *            concurrent updates happens.  (I guess the same issue and this
 *            solution also applys to deletes)
 *          
 *          - There is a trigger on accumulator_shd called TRIGGER TRG_INS_ACCUMSHD 
 *            to update the aggregated tote in the accumulator table instead.  This
 *            should be the only process that updates the accumulator table, has
 *            ensure sequential update order, and avoids the ora-06000 deadlocks.
 *           
 *          - The tote in accumulator_shd is not aggregated according to the 
 *            interval, username and service, and instead stored as seperate rows.   
 *           
 *          - Maintenance of the accumulator_shd table: Separate from EventSender, 
 *            rotaccum cron job will delete the entries from the accumulator_shd 
 *            that's more than a month old, i.e. no longer needed.
 * =============================================================================
 */
public class EventSender {

	/**
	 * Default properties file name. The class loader will attempt to load this
	 * as a resource if a properties file is not specified in the constructor.
	 */
	public static String DEFAULT_PROPERTIES = "/RDC.properties";
	public static String ALTERNATE_PROPERTIES="RDC.properties";
	public static String PROPERTIES_PROPERTIES="properties/RDC.properties";

	private static Properties _props;
	private static EventSender es;
	private static EventSenderThread est;
	private static StringBuffer data = new StringBuffer();
	private static Logger debugLog;

	static boolean verbose = false;

	/**
	 * Constructor using default properties.
	 * 
	 * Constructor called from RIS webapps and RISScheduler
	 */
	public EventSender() throws Exception {
		initEventSender(DEFAULT_PROPERTIES);
	}

	private void initEventSender(String propFile) throws IOException,
			SQLException {
		if (debugLog == null) {
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
			
			// initialize the EventSenderThread
			if (useLinkedQ(_props)) {
				int maxQSize = new Integer(_props.getProperty("MaxQSize"));
				est = EventSenderThread.getHelper();
			}

			debugLog = Logger.getLogger(EventSender.class);
			BasicConfigurator.configure();
		}
	}
		
	private boolean useLinkedQ(Properties props) {
		String useLinkedQ = System.getProperty("UseLinkedQ");
		if ((useLinkedQ == null) || (useLinkedQ.equalsIgnoreCase(""))) {
			useLinkedQ = props.getProperty("UseLinkedQ");
		}
		
		return ((useLinkedQ != null) && (useLinkedQ.equalsIgnoreCase("true")));
	}

	/**
	 * Constructor using specified properties file.
	 * 
	 * 
	 */
	public EventSender(String propfile) throws Exception {
		initEventSender(propfile);
	}



	/**
	 * Constructor Creation called by CBSEventLogger, ZGatewayEventLogger
	 * @param propName
	 * @return
	 * @throws Exception
	 */
	protected static EventSender getInstance(String propName) throws Exception {
		if (es == null) {
			es = new EventSender(propName);
			debugLog = Logger.getLogger(EventSender.class);
		}
		return es;
	}
	
	protected static EventSender getInstance() throws Exception {
		if (es == null) {
			es = new EventSender();
			debugLog = Logger.getLogger(EventSender.class);
		}
		return es;
	}
	

	public synchronized void finalize() throws Throwable {
		destroy();
	}

	/**
	 * Calls stop()
	 */
	public synchronized void destroy() throws SQLException {
		es = null;
	}



	/*
	 * send: This method is called by CBSEventLogger and ZGatewayEventLogger
	 * (non-Javadoc)
	 * 
	 * @see
	 * au.gov.nla.kinetica.rdc.events.IEventSender#send(au.gov.nla.kinetica.
	 * events.Event)
	 */
	public synchronized void send(Event ev) throws Exception {
		send(ev, 1);
	}

	/*
	 * send: called by the send method which is called by CBSEventLogger and
	 *       ZGatewayEventLogger
	 * 
	 * (non-Javadoc)
	 * 
	 * @see
	 * au.gov.nla.kinetica.rdc.events.IEventSender#send(au.gov.nla.kinetica.
	 * events.Event, java.lang.Integer)
	 */
	public synchronized void send(Event ev, Integer accumNum) throws Exception {
		if (debugLog == null) {
			initEventSender(DEFAULT_PROPERTIES);
		}
		
		 try {
			if (ev.validate()) {
				debugLog.debug("up to logEvent(ev, accumNum)");
				queueEvent(ev, accumNum);
				debugLog.debug("done");
			} else {
				debugLog.warn("ignoring invalid event: " + ev);
			}
		 } catch (Exception ex) {
			debugLog.error("problem logging event: " + ex);
			throw ex;
		 }
	}


	/*
	 * (non-Javadoc)
	 * 
	 * @see au.gov.nla.kinetica.rdc.events.IEventSender#send(java.lang.String,
	 * java.lang.String)
	 */
	public synchronized void send(String user, String serv) throws Exception {
		send(user, serv, null, null, null, null);
	}


	/*
	 * send: method called by RIS web app
	 * (non-Javadoc)
	 * 
	 * @see au.gov.nla.kinetica.rdc.events.IEventSender#send(java.lang.String,
	 * java.lang.String, java.lang.Integer)
	 */
	public synchronized void send(String user, String serv, Integer accumNum)
			throws Exception {
		send(user, serv, null, null, null, null, accumNum);
	}


	/*
	 * 
	 * (non-Javadoc)
	 * 
	 * @see au.gov.nla.kinetica.rdc.events.IEventSender#send(java.lang.String,
	 * java.lang.String, java.util.Date)
	 */
	public synchronized void send(String user, String serv, Date time) throws Exception {
		send(user, serv, time, null, null, null);
	}


	/*
	 * (non-Javadoc)
	 * 
	 * @see au.gov.nla.kinetica.rdc.events.IEventSender#send(java.lang.String,
	 * java.lang.String, java.util.Date, java.lang.Integer)
	 */
	public synchronized void send(String user, String serv, Date time, Integer accumNum)
			throws Exception {
		send(user, serv, time, null, null, null, accumNum);
	}


	/*
	 * (non-Javadoc)
	 * 
	 * @see au.gov.nla.kinetica.rdc.events.IEventSender#send(java.lang.String,
	 * java.lang.String, java.lang.String, java.lang.String, java.lang.String)
	 */
	public synchronized void send(String user, String serv, String addr, String input,
			String output) throws Exception {
		send(user, serv, null, addr, input, output);
	}


	/*
	 * (non-Javadoc)
	 * 
	 * @see au.gov.nla.kinetica.rdc.events.IEventSender#send(java.lang.String,
	 * java.lang.String, java.util.Date, java.lang.String, java.lang.String,
	 * java.lang.String)
	 */
	public synchronized void send(String user, String serv, Date time, String addr,
			String input, String output) throws Exception {
		send(user, serv, time, addr, input, output, null);
	}


	/*
	 * send: method called by the send method called by the RIS web app
	 * (non-Javadoc)
	 * 
	 * @see au.gov.nla.kinetica.rdc.events.IEventSender#send(java.lang.String,
	 * java.lang.String, java.util.Date, java.lang.String, java.lang.String,
	 * java.lang.String, java.lang.Integer)
	 */
	public synchronized void send(String user, String serv, Date time, String addr,
			String input, String output, Integer accumNum) throws Exception {

		if (debugLog == null) {
			initEventSender(DEFAULT_PROPERTIES);
		}
		
		debugLog.debug("rdcEventLogger: sending user: " + user + " serv: " + serv + " time: " + time +
				        " addr: " + addr + " input: " + input + " output: " + output + " accumNum " + accumNum);
		
		if (accumNum == null) accumNum = 1;
		
		Event ev = new Event(user, serv, time);
		if (addr != null) {
			ev.address(addr);
		}
		if (input != null) {
			ev.input(input);
		}
		if (output != null) {
			ev.output(output);
		}
		send(ev, accumNum);
		debugLog.debug("rdcEventLogger: sent user: " + user + " serv: " + serv + " time: " + time +
		        " addr: " + addr + " input: " + input + " output: " + output + " accumNum ");

	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see
	 * au.gov.nla.kinetica.rdc.events.IEventSender#sendEvent(java.lang.String,
	 * java.lang.String)
	 */
	public synchronized static void sendEvent(String user, String serv) throws Exception {

		sendEvent(null, user, serv, null, null, null, null);
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see
	 * au.gov.nla.kinetica.rdc.events.IEventSender#sendEvent(java.lang.String,
	 * java.lang.String, java.lang.Integer)
	 */

	public synchronized static void sendEvent(String user, String serv, Integer accumNum)
			throws Exception {

		sendEvent(null, user, serv, null, null, null, null, accumNum);
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see
	 * au.gov.nla.kinetica.rdc.events.IEventSender#sendEvent(java.lang.String,
	 * java.lang.String, java.util.Date)
	 */

	public synchronized static void sendEvent(String user, String serv, Date time)
			throws Exception {

		sendEvent(null, user, serv, time, null, null, null);
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see
	 * au.gov.nla.kinetica.rdc.events.IEventSender#sendEvent(java.lang.String,
	 * java.lang.String, java.util.Date, boolean)
	 */

	public synchronized static void sendEvent(String user, String serv, Date time,
			boolean discard) throws Exception {

		sendEvent(null, user, serv, time, null, null, null, discard);
	}

	/*
	 * sendEvent: called by the LASearch webapp and LAAdmin webapp
	 * (non-Javadoc)
	 * 
	 * @see
	 * au.gov.nla.kinetica.rdc.events.IEventSender#sendEvent(java.lang.String,
	 * java.lang.String, java.lang.String, java.lang.String, java.lang.String)
	 */

	public synchronized static void sendEvent(String user, String serv, String addr,
			String input, String output) throws Exception {

		sendEvent(null, user, serv, null, addr, input, output);
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see
	 * au.gov.nla.kinetica.rdc.events.IEventSender#sendEvent(java.lang.String,
	 * java.lang.String, java.util.Date, java.lang.String, java.lang.String,
	 * java.lang.String)
	 */

	public synchronized static void sendEvent(String user, String serv, Date time,
			String addr, String input, String output) throws Exception {

		sendEvent(null, user, serv, time, addr, input, output);
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see
	 * au.gov.nla.kinetica.rdc.events.IEventSender#sendEvent(java.lang.String,
	 * java.lang.String, java.lang.String, java.util.Date, java.lang.String,
	 * java.lang.String, java.lang.String)
	 */
	public synchronized static void sendEvent(String propfile, String user, String serv,
			Date time, String addr, String input, String output)
			throws Exception {

		sendEvent(propfile, user, serv, time, addr, input, output, false);
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see
	 * au.gov.nla.kinetica.rdc.events.IEventSender#sendEvent(java.lang.String,
	 * java.lang.String, java.lang.String, java.util.Date, java.lang.String,
	 * java.lang.String, java.lang.String, boolean)
	 */

	public synchronized static void sendEvent(String propfile, String user, String serv,
			Date time, String addr, String input, String output, boolean discard)
			throws Exception {

		sendEvent(propfile, user, serv, time, addr, input, output, discard,
				null);
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see
	 * au.gov.nla.kinetica.rdc.events.IEventSender#sendEvent(java.lang.String,
	 * java.lang.String, java.lang.String, java.util.Date, java.lang.String,
	 * java.lang.String, java.lang.String, java.lang.Integer)
	 */

	public synchronized static void sendEvent(String propfile, String user, String serv,
			Date time, String addr, String input, String output,
			Integer accumNum) throws Exception {

		sendEvent(propfile, user, serv, time, addr, input, output, false,
				accumNum);
	}

	/*
	 * (non-Javadoc)
	 * 
	 * sendEvent: This method processes the events from LASearch, LAAdmin 
	 *            web apps.  It is called from the sendEvent method which
	 *            is in turn called by LASearch and LAAdmin web apps.
	 * 
	 * @see
	 * au.gov.nla.kinetica.rdc.events.IEventSender#sendEvent(java.lang.String,
	 * java.lang.String, java.lang.String, java.util.Date, java.lang.String,
	 * java.lang.String, java.lang.String, boolean, java.lang.Integer)
	 */

	public synchronized static void sendEvent(String propfile, String user, String serv,
			Date time, String addr, String input, String output,
			boolean discard, Integer accumNum) throws Exception {
		try {
			if (es == null) {
				if (propfile == null) {
					es = EventSender.getInstance();
				} else {
					es = EventSender.getInstance(propfile);
				}

			}
			es.send(user, serv, time, addr, input, output, accumNum);
			// es.stop();
			// es = null;
		} catch (Exception e) {
			e.printStackTrace();
			throw e;
		}
	}
	
	private void setProperties(Properties props) {
		this._props = props;
	}

	private void queueEvent(Event e, int accumNum) throws IOException {
		String user = e.user();
		String service = e.service();
		Timestamp etime = new Timestamp(e.time().getTime());
		SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
		String _etime = sdf.format(etime);
		
		data.setLength(0);
		data.append(user).append("::").append(service).append("::").append(_etime).append("::").append(accumNum).append("\n");
		// debugLog.debug("queuing:: " + data.toString());
		
		EventExt et = new EventExt(e, accumNum);
		est.log(et);
		int qsize = est.getItemCount();
		debugLog.debug("queue size:: " + qsize);
		
	}
	
	protected Properties getProps() {
		return _props;
	}

	public void setLogger(Logger debugLog) {
		this.debugLog = debugLog;
	}

	/**
	 * printStack
	 * 
	 * @param e
	 */
	public synchronized void printStack(Exception e) {
		StackTraceElement[] stack = e.getStackTrace();
		for (int i = 0; i < stack.length; i++) {
			debugLog.error("[RDCEventLogger]\t" + stack[i]);
		}
	}
	
	
	// ----------------------------------------------------------------------------
	// Main testharness
	// ----------------------------------------------------------------------------

	/**
	 * For testing.
	 */
	public static void main(String[] args) {
		// if args[0] is teststatus, then check the following:
		// 1. the input log directory is accessible
		// 2. able to connect to the database
		try {
		String flag = "";
		String userName = "";
		if (args.length > 1) {
			flag = args[0];
			userName = args[1];
			
			if (flag.equalsIgnoreCase("ClassCall")) {
				testSenderClassCall();
			}
			
			if (flag.equalsIgnoreCase("InstanceCall")) {
				testSenderInstanceCall();
			}
		}
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	private static void testSenderClassCall() {
		while (true) {
			try {
				EventSender.sendEvent("es_test_class", "la:opensearch:dbld", 1);
			} catch (Exception e) {
				e.printStackTrace();
			}
		}
		
	}
	private static void testSenderInstanceCall() {
		while (true) {
			try {
				EventSender es = new EventSender();
				es.send("es_test_instance", "la:opensearch:dbld", 1);
			} catch (Exception e) {
				e.printStackTrace();
			}
		}
		
	}
}