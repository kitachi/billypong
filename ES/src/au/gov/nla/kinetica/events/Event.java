package au.gov.nla.kinetica.events;

import java.util.Date;
import java.io.Serializable;

/**
 * An Event object represents an event on a Kinetica system where a user
 * uses a service from an address with certain parameters and receives a
 * response.
 *
 * <p>
 * The class implements Serializable because Events are sent on a JMS
 * queue.
 */
public class Event implements Serializable {

    private static final long serialVersionUID = 1L;
    
    public static final String STATUS_CREATED =   "created";
    public static final String STATUS_VALIDATED = "validated";
    public static final String STATUS_LOGGED =    "logged";
    public static final String STATUS_ARCHIVED =  "archived";

    private String _user;
    private String _address;
    private String _service;
    private Date   _time;
    private String _input;
    private String _output;
    private String _status;
    private Date   _lastStatusChange;

    /**
     * Constructor for creating an empty Event. Required because some
     * Interpreters might need to build up an Event base on many log entries.
     */
    public Event () {
	_input = "";
	_output = "";
	_address = "";
	_status = STATUS_CREATED;
	_lastStatusChange = new Date();
    }

    /**
     * Convenience constructor for Event(user, service, time), passing null
     * for time.
     */
    public Event (String user, String service) {
	this(user, service, null);
    }

    /**
     * Constructor that sets user, service and time
     *
     * @param user The username of the user initiating the event.
     * @param service The name of the service the user used.
     * @param time The time of the event. If null then defaults to now.
     */
    public Event (String user, String service, Date time) {
	_user = user;
	_service = service;
	_time = time == null ? new Date() : time;
	_input = "";
	_output = "";
	_address = "";
	_status = STATUS_CREATED;
	_lastStatusChange = new Date();
    }

    /**
     * Get the username. This will usually be the UserID of the
     * authenticated user who initiated the event. There should be a
     * corresponding UserAccount object in the Customer Directory.
     */
    public String user () {
	return _user;
    }

    /**
     * Set the username
     */
    public String user (String u) {
	_user = u;
	return user();
    }

    /**
     * Get the address. The IP address of the host that the user request
     * came from, if it is available.
     */
    public String address () {
	return _address;
    }

    /**
     * Set the address
     */
    public String address (String a) {
	_address = a;
	return address();
    }

    /**
     * Get the service. The name of the Service defined in the Customer
     * Directory that represents this function provided by the KS.
     * Services are defined hierarchically. A fully qualified service
     * name is constructed by appending ancestor names with colons (:),
     * for example:
     * <p>
     * kinetica:search:advancedSearch
     * <p>
     * This service name represents the advanced search screen in
     * Libraries Australia as it is defined in the Customer Directory.
     * <p>
     * Services should be defined to the level of granularity required
     * to support the reporting requirements, in addition to any access
     * control and preference requirements.
     */
    public String service () {
	return _service;
    }

    /**
     * Set the service.
     */
    public String service (String s) {
	_service = s;
	return service();
    }

    /**
     * Get the time. The time, to the second, when the event occurred.
     * It will usually be the time that the system received the request
     * from the user to initiate the action. If time is not specified
     * then it defaults to now.
     */
    public Date time () {
	return _time;
    }

    /**
     * Set the time.
     */
    public Date time (Date t) {
	_time = t;
	return time();
    }

    /**
     * Get the input. Any parameters that the user provided when
     * initiating the action. Each KS will populate this attribute and
     * format it according to its particular processing model.
     * For example, a search action might populate it with a query string.
     */
    public String input () {
	return _input;
    }

    /**
     * Set the input.
     */
    public String input (String i) {
    	if (i.length() > 4000) {
    		_input = i.substring(0, 4000);
    	} else {
    		_input = i;
    	}
    	return input();
    }

    /**
     * Get the output. Any status codes, processing summary data or
     * response data that the KS provided to the user after processing
     * the action. As with Input, this attribute is populated by the KS
     * according to its processing model. For example, a search action
     * might populate it with the number of records returned.
     */
    public String output () {
	return _output;
    }

    /**
     * Set the output.
     */
    public String output (String o) {
    	if (o.length() > 4000) {
    		_output = o.substring(0, 4000);
    	} else {
    		_output = o;
    	}
    	return output();
    }

    /**
     * Get the status. An event's status represents its processing state.
     */
    public String status () {
	return _status;
    }

    /**
     * Get the time of the last change to the event's status.
     */
    public Date lastStatusChange () {
	return _lastStatusChange;
    }

    /**
     * Determine whether the event is valid and if so then set its status
     * to validated. Only valid events will be logged by the EventLogger.
     */
    public boolean validate () {

	if (!_status.equals(STATUS_CREATED)) { return true; }

	boolean valid = true;

	if (_user == null || _user.trim().equals("")) { 
		valid = false; 
	}
	
	if (_service == null || _service.trim().equals("")) { 
		valid = false; 
	}
	
	if (_time == null) { valid = false; }
	
	if (_input != null && _input.length() > 4000) { valid = false; }
	if (_output != null && _output.length() > 4000) { valid = false; }

	//if (user or service don't exist) { valid = false; } ???

	if (valid) {
	    _status = STATUS_VALIDATED;
	    _lastStatusChange = new Date();
	}

	return valid;
    }

    /**
     * Change the event's status to show that it has been logged.
     */
    public boolean log () {
	if (!_status.equals(STATUS_VALIDATED)) {
	    return false;
	}
	_status = STATUS_LOGGED;
	_lastStatusChange = new Date();
	return true;
    }

    /**
     * Change the event's status to show that it is ready for archiving.
     */
    public boolean archive () {
	if (!_status.equals(STATUS_LOGGED)) {
	    return false;
	}
	_status = STATUS_ARCHIVED;
	_lastStatusChange = new Date();
	return true;
    }

    /**
     * Get a String representation of the event.
     */
    public String toString () {
	return "Event: " + user() + " (" + address() + ") " +
	    service() + " " + time() + " [" + status() + "]\n" +
	    "in: " + input() + "\n" +
	    "out: " + output() + "\n";
    }
}
