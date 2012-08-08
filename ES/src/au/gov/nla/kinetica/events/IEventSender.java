package au.gov.nla.kinetica.events;

import java.sql.SQLException;
import java.util.Date;

import org.apache.log4j.Logger;

import au.gov.nla.kinetica.events.Event;

public interface IEventSender {
	
	/**
	 * wakeup: to notify EventSender to perform certain actions during state wakeup
	 *         e.g. to establish a database connection
	 * @throws Exception
	 */
	public void wakeup() throws Exception;

	/**
	 * sleep: to notify EventSender to perform certain actions durint state sleep
	 *        e.g. to close a database connection
	 */
	public void sleep();

	/* 
	 * send is used by CBSEventLogger and ZGatewayEventLogger as well as by
	 *      the sendEvent methods
	 */
	public void send(Event ev) throws Exception;

	/*
	 * check for the given point in time, are there any time interval missing
	 * and if so, create all of these missing intervals.
	 */
	public void fixMissingIntervals(String ptInTime) throws SQLException;
	
	/**
	 * Static Method used by other RDC components including RIS, LAAdmin, etc 
	 */
	// public static void sendEvent(String user, String serv) throws Exception;

	/**
	 * Static Method used by other RDC components including RIS, LAAdmin, etc 
	 */
	// public void static sendEvent(String user, String serv, Integer accumNum)
	//		throws Exception;

	/**
	 * Static Method used by other RDC components including RIS, LAAdmin, etc 
	 */
	// public static void sendEvent(String user, String serv, Date time)
	//		throws Exception;

	/**
	 * Static Method used by other RDC components including RIS, LAAdmin, etc , permits discard
	 */
	// public static void sendEvent(String user, String serv, Date time,
	//		boolean discard) throws Exception;

	/**
	 * Static Method used by other RDC components including RIS, LAAdmin, etc 
	 */
	// public static void sendEvent(String user, String serv, String addr,
	//		String input, String output) throws Exception;

	/**
	 * Static Method used by other RDC components including RIS, LAAdmin, etc 
	 */
	// public static void sendEvent(String user, String serv, Date time,
	//		String addr, String input, String output) throws Exception;

	/**
	 * Static Method used by other RDC components including RIS, LAAdmin, etc 
	 */
	// public static void sendEvent(String propfile, String user, String serv,
	//		Date time, String addr, String input, String output)
	//		throws Exception;

	/**
	 * Static Method used by other RDC components including RIS, LAAdmin, etc 
	 */
	// public static void sendEvent(String propfile, String user, String serv,
	//		Date time, String addr, String input, String output, boolean discard)
	//		throws Exception;

	/**
	 * Static Method used by other RDC components including RIS, LAAdmin, etc 
	 */
	// public static void sendEvent(String propfile, String user, String serv,
	//		Date time, String addr, String input, String output,
	//		Integer accumNum) throws Exception;

	/**
	 * wrapper method for one-off sending.
	 * Creates and EventSender, calls send(), and stop()
	 */
	// public static void sendEvent(String propfile, String user, String serv,
	//		Date time, String addr, String input, String output,
	//		boolean discard, Integer accumNum) throws Exception;


	public void setLogger(Logger debugLog);
}