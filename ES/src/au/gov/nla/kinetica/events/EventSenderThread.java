package au.gov.nla.kinetica.events;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.URL;
import java.net.URLClassLoader;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import org.apache.commons.io.FileUtils;
import org.apache.log4j.BasicConfigurator;
import org.apache.log4j.Logger;


public class EventSenderThread extends Thread {
	private static final String QSTATUS_FILE_NAME = "/var/tmp/qStatus";
	private static final String CONSUMER_CACHE_NAME = "/var/tmp/qconsumedItems";
	private static final String PRODUCER_CACHE_NAME = "/var/tmp/qproducedItems";
	private static final String DEFAULT_PROPERTIES = "RDC.properties";
	private static final int    DEFAULT_Q_SIZE = 240000;
	
	// private BlockingQueue<EventExt> itemsToQ = new
	// LinkedBlockingQueue<EventExt>(4000);
	private static BlockingQueue<EventExt> itemsToQ;
	private static String bufferFileName;
	private volatile boolean shuttingDown, threadTerminated;
	private static final EventExt SHUTDOWN_REQ = new EventExt(new Event(), 1);
	private static EventSenderThread _thread;
	private static FileOutputStream consumerCache;
	private static FileOutputStream producerCache;
	private static File qStatusFile = new File(QSTATUS_FILE_NAME);
	private static StringBuffer cdata = new StringBuffer();
	private static StringBuffer pdata = new StringBuffer();
	private static int qcount = 0;
	private static int dqcount = 0;
	
	private static final String DELIMITER = "::";
	private static int USR = 0;
	private static int SRV = 1;
	private static int TIM = 2;
	private static int STA = 3;
	private static int IN = 4;
	private static int OUT = 5;
	private static int ADR = 6;
	private static int TOT = 7;
	private static int QC = 1;
	private static int DQC = 3;
	
	private static boolean verbose = false;  // Configurable through RDC.properties
	private static boolean track = false;     // Configurable through RDC.properties, by specify the app path of the app to track
											 // currently can only track one app.
											 // to track more than one app, need to 
											 // seperate the qStatusFile, consumerCache and producerCache.
	private static Logger debugLog;
	private static Properties props;
	
	static {
		debugLog = Logger.getLogger(EventSenderThread.class);
		BasicConfigurator.configure();
		
		getHelper();
	}

	public static EventSenderThread getHelper() {
		if (_thread == null) {
			_thread = new EventSenderThread();
		}
		return _thread;
	}
	
	private EventSenderThread() {
		initEventSenderThread();
		start();
	}
	
	private synchronized void initEventSenderThread() {
		int maxQSize = DEFAULT_Q_SIZE;
		props = loadProperties();
		if (props != null) {
			if (props.getProperty("MaxQSize") != null)
				maxQSize = new Integer(props.getProperty("MaxQSize"));
			
			if (props.getProperty("verbose") != null)
				verbose = props.getProperty("verbose").trim().equalsIgnoreCase("true")?true:verbose;
			track = toTrack(props.getProperty("track"));
			
			if (verbose) debugLog.debug("Starting the EventSenderThread...");
		}
		itemsToQ = new LinkedBlockingQueue<EventExt>(maxQSize);

		if (track) {
			try {
				// Check left over items from the last run of the
				// EventSenderThread
				readStatusFromFile();
				// clear the qStatusFile
				FileUtils.writeStringToFile(qStatusFile, "");
				
				// Load unprocessed items from the last run back
				// onto the queue
				queueSpillOverFromLastRun();
			} catch (FileNotFoundException e) {
				e.printStackTrace();
			} catch (IOException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		}
	}
	
	private boolean toTrack(String path) {
		ClassLoader sysCL = EventSenderThread.class.getClassLoader();
		URL[] urls = ((URLClassLoader) sysCL).getURLs();

		for (URL url : urls) {
			if (url.getFile().startsWith(path)) {
				return true;
			}
		}

		return false;
	}

	public void run() {
		int qsize = 0;

		while (!(shuttingDown || threadTerminated)) {
			try {
				EventExt item;
//
//				sleep(10000);

				while ((item = itemsToQ.take()) != SHUTDOWN_REQ) {
					// logEventTest(item);
					logEvent(item);
				}
			} catch (Exception ex) {

			} finally {
				EventLogger.closeDBConn();
				threadTerminated = true;
			}
		}

		try {
			if (track) {
				consumerCache.close();
				producerCache.close();
			}
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	public synchronized static int log(EventExt ev) throws IOException {
		try {
			qcount = qcount + 1;
			itemsToQ.put(ev);

			if (track) {
				pdata = serializeEvent(ev, pdata);
				producerCache.write(pdata.toString().getBytes());
				FileUtils.writeStringToFile(qStatusFile, "qcount" + DELIMITER
						+ qcount + DELIMITER + "dqcount" + DELIMITER + dqcount
						+ "\n");
			}
		} catch (InterruptedException iex) {
			Thread.currentThread().interrupt();
			throw new RuntimeException("Unexpected interruption.");
		}

		return qcount;
	}

	public synchronized int getItemCount() {
		return ((itemsToQ == null) ? 0 : itemsToQ.size());
	}

	// For verifying in the case where new EventSender instances
	// are created, still shares the same thread
	public synchronized int getAcsCount() {
		return qcount;
	}

	public synchronized void shutdown() throws InterruptedException {
		shuttingDown = true;
		itemsToQ.put(SHUTDOWN_REQ);
	}
	
	private static void readStatusFromFile() {
		String status;
		try {
			status = FileUtils.readFileToString(qStatusFile).trim();
			if ((status != null) || (!status.isEmpty())) {
				String[] tokens = status.split(DELIMITER);
				if (tokens.length == 4) {
					qcount = new Integer(tokens[QC]);
					dqcount = new Integer(tokens[DQC]);
				}
			}
		} catch (java.io.FileNotFoundException fnfe) {
		} catch (IOException e) {
			e.printStackTrace();
		}
	}
	
	private synchronized static void queueSpillOverFromLastRun() {
		// count the spill over
		int qdiff = qcount - dqcount;
		
		// reinit after get count of previous unprocessed items
		qcount = 0;
		dqcount = 0;
		if (qdiff > 0) {
			Map<Long, String> qmap = tailLines(PRODUCER_CACHE_NAME, qdiff);
			// delete old consumerCache and producerCache
			FileUtils.deleteQuietly(new File(CONSUMER_CACHE_NAME));
			FileUtils.deleteQuietly(new File(PRODUCER_CACHE_NAME));

			// Prepare consumerCache and prodcerCache for the next run
			// of the EventSenderThread
			try {
				consumerCache = new FileOutputStream(CONSUMER_CACHE_NAME, true);
				producerCache = new FileOutputStream(PRODUCER_CACHE_NAME, true);

				for (Long idx : qmap.keySet()) {
					String eventStr = (String) qmap.get(idx);
					try {
						log(parseEvent(eventStr));
					} catch (IOException e) {
						// TODO Auto-generated catch block
						e.printStackTrace();
					}
				}
			} catch (FileNotFoundException e1) {
				// TODO Auto-generated catch block
				e1.printStackTrace();
			}
		} else {
			// just try to initialize caches
			try {
				consumerCache = new FileOutputStream(CONSUMER_CACHE_NAME, true);
				producerCache = new FileOutputStream(PRODUCER_CACHE_NAME, true);
			} catch (FileNotFoundException e1) {
				// TODO Auto-generated catch block
				e1.printStackTrace();
			}			
		}
	}
	
	private synchronized static Map<Long, String> tailLines(String fileName,
			long numberOfLines) {
		Map<Long, String> strmap = new HashMap<Long, String>();
		try {
			/*
			 * Receive file name and no of lines to tail as command line
			 * argument
			 */
			RandomAccessFile randomFile = new RandomAccessFile(fileName, "r");

			long filelength = randomFile.length();
			long filepos = filelength - 1;
			long linescovered = 1;
			for (linescovered = 1; linescovered <= numberOfLines; filepos--) {
				if (filepos < 0) filepos = 0;
				
				randomFile.seek(filepos);
				if ((randomFile.readByte() == 0xA) || (filepos == 0))
					if (filepos == filelength - 1)
						continue;
					else {
						strmap.put(linescovered, randomFile.readLine());
						linescovered++;
					}

			}
			
			randomFile.close();
			randomFile = null;
		} catch (Exception e) {
			e.printStackTrace();
		}

		if (verbose) {
			long startPosition = numberOfLines;
			while (startPosition != 0) {
				if (strmap.containsKey(startPosition)) {
					String outstr = (String) strmap.get(startPosition);
					debugLog.debug(outstr);
					startPosition--;

				}
			}
		}
		return strmap;
	}

	private void logEvent(EventExt item) throws Exception {
		EventLogger.logEvent(item.getEvent(), item.getAccumNum());
	}

	private synchronized void logEventTest(EventExt item) throws IOException {
		if (track) {
			cdata = serializeEvent(item, cdata);
			consumerCache.write(cdata.toString().getBytes());
			dqcount = dqcount + 1;
			FileUtils.writeStringToFile(qStatusFile, "qcount" + DELIMITER + qcount
					+ DELIMITER + "dqcount" + DELIMITER + dqcount + "\n");
		}
	}

	private synchronized static EventExt parseEvent(String data) {
		if (data != null) {
			String[] tokens = data.split(DELIMITER);
			if (tokens.length == 8) {
				Event e = new Event();
				e.user(tokens[USR]);
				e.service(tokens[SRV]);
				SimpleDateFormat sdf = new SimpleDateFormat(
						"yyyy-MM-dd HH:mm:ss");
				Date etime = null;
				try {
					etime = sdf.parse(tokens[TIM]);
				} catch (ParseException e1) {
				}
				e.time(etime);
				e.address(tokens[ADR]);
				e.input(tokens[IN]);
				e.output(tokens[OUT]);
				EventExt ext = new EventExt(e, new Integer(tokens[TOT]));

				return ext;
			}
		}
		return null;
	}
	
	private synchronized static StringBuffer serializeEvent(EventExt item, StringBuffer data) {
		Event e = item.getEvent();
		String user = e.user();
		String service = e.service();
		String address = e.address();
		String status = e.status();
		String input = e.input();
		String output = e.output();
		Date time = e.time();
		SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
		String _etime = sdf.format(time);

		data.setLength(0);

		data.append(user).append(DELIMITER).append(service).append(DELIMITER)
				.append(_etime).append(DELIMITER).append(status).append(DELIMITER)
				.append(input).append(DELIMITER).append(output).append(DELIMITER)
				.append(address).append(DELIMITER).append(item.getAccumNum())
				.append("\n");
		
		return data;
	}
	
	private static Properties loadProperties() {
		Properties p = new Properties();

		try {
			p.load(EventSenderThread.class.getClassLoader()
					.getResourceAsStream(DEFAULT_PROPERTIES));
		} catch (Exception e) {
			try {
				p.load(new FileInputStream(DEFAULT_PROPERTIES));
			} catch (Exception ex) {
			}
		}
		return p;
	}
}
