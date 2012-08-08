package au.gov.nla.kinetica.events;

import java.io.Serializable;

public class EventExt implements Serializable {

	private Event _e;
	private int _accumNum;

	public EventExt(Event e, int accumNum) {
		_e = e;
		_accumNum = accumNum;
	}
	
	public int getAccumNum() {
		return _accumNum;
	}

	public Event getEvent() {
		return _e;
	}
	
}
