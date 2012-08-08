package au.gov.nla.kinetica.events;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;


/**
 * EventSenderThread process the events on the EventLogger queue.
 * 
 * - during startup, it checks whether there's a cached file storing
 *   previously unprocessed events due to last app shutdown.
 *   
 * - during shutdown, it writes unprocessed events to a cached file
 *   
 * - during running, it takes each event from the queue, and call 
 *   EventLogger to update the usage stats in db for that event.  
 */

public class EventSenderThread extends Thread {
	private BlockingQueue<EventExt> itemsToQ;
	private static String bufferFileName;
	private volatile boolean shuttingDown, threadTerminated;
	private static EventSenderThread _thread;
	private static int acscount = 0;
		
	public static EventSenderThread getHelper() {
		return getHelper(120000, null);
	}
	
	public static EventSenderThread getHelper(int maxQSize, String bufferName) {
		if (_thread == null) {
			_thread = new EventSenderThread(maxQSize, bufferName);
		}
		return _thread;
	}

	private EventSenderThread(int maxQSize, String bufferFileName) {
		setName("EventSenderHelper");
		setDaemon(true);

		// read buffered items from the cached file
		ArrayList<EventExt> buffer = null;
		if (bufferFileName != null) {
			try {
				this.bufferFileName = bufferFileName;
				// Loading previous buffered items from the file system
				ObjectInputStream ois = new ObjectInputStream(
						new FileInputStream(bufferFileName));

				buffer = new ArrayList<EventExt>();
				buffer = (ArrayList<EventExt>) ois.readObject();
				ois.close();
				FileUtils.deleteQuietly(new File(bufferFileName));
			} catch (FileNotFoundException e) {
				// Ignore this, if no file found under the bufferFileName,
				// then there's no buffer to be read.
			} catch (IOException e) {
				e.printStackTrace();
			} catch (ClassNotFoundException e) {
				e.printStackTrace();
			}
		}

		// init the in-mem queue
		if (this.itemsToQ == null) {
			this.itemsToQ = new LinkedBlockingQueue<EventExt>(maxQSize);
			// If there's cached items, load the items onto the in-mem
			// queue
			if (buffer != null) {
				try {
					for (int i = 0; i < buffer.size(); i++) {
						this.itemsToQ.put((EventExt) buffer.get(i));

					}
				} catch (InterruptedException e) {
				}
			}
		}
		start();
	}
	
	public void run() {
		EventExt item;

		int qsize = 0;
		
		while (!(shuttingDown || threadTerminated)) {
			try {
				item = itemsToQ.take();	
				qsize = getItemCount();
				logEvent(item);
			} catch (Exception ex) {

			} finally {
				EventLogger.closeDBConn();				
				threadTerminated = true;
			}
		}
		
		if (bufferFileName != null) {
		// try write unprocessed buffered items to the cached file
		try {
			FileUtils.deleteQuietly(new File(bufferFileName));
		} catch (Exception e) {
		}

		try {
			ObjectOutputStream oos = new ObjectOutputStream(
					new FileOutputStream(bufferFileName));
			
			ArrayList buffer = new ArrayList(qsize);
			try {
				for (int i = 0; i < qsize; i++) {
					item = (EventExt) itemsToQ.take();
					buffer.add(item);
				}
				oos.writeObject(buffer);
				oos.close();
			} catch (InterruptedException e) {

			}

			oos.close();
		} catch (FileNotFoundException e) {
		} catch (IOException e) {
			e.printStackTrace();
		}
		}
	}
	
	public synchronized int log(EventExt ev) {
		if (shuttingDown || threadTerminated) return acscount;
		
		acscount = acscount + 1;
		
		try {
			itemsToQ.put(ev);
		} catch (InterruptedException iex) {
			Thread.currentThread().interrupt();
			throw new RuntimeException("Unexpected interruption.");
		}
		
		return acscount;
	}
	
	public synchronized int getItemCount() {
		return ((itemsToQ == null)? 0: itemsToQ.size());
	}
	
	/*
	 *  For verifying in the case where new EventSender instances
	 *  are created, still shares the same thread
	 * 
	 */
	public synchronized int getAcsCount() {
		return acscount;
	}
	
	public synchronized void shutdown() throws InterruptedException {
		shuttingDown = true;
	}
	
	private void logEvent(EventExt item) throws Exception {
		EventLogger.logEvent(item.getEvent(), item.getAccumNum());
	}
}
