/*
 ** Copyright (C) 2013 Mellanox Technologies
 **
 ** Licensed under the Apache License, Version 2.0 (the "License");
 ** you may not use this file except in compliance with the License.
 ** You may obtain a copy of the License at:
 **
 ** http://www.apache.org/licenses/LICENSE-2.0
 **
 ** Unless required by applicable law or agreed to in writing, software
 ** distributed under the License is distributed on an "AS IS" BASIS,
 ** WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 ** either express or implied. See the License for the specific language
 ** governing permissions and  limitations under the License.
 **
 */
package org.accelio.jxio.jxioConnection;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.accelio.jxio.*;
import org.accelio.jxio.jxioConnection.impl.ServerWorker;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.HashMap;
import java.util.Iterator;
import java.util.concurrent.ConcurrentLinkedQueue;

public class JxioConnectionServer extends Thread implements WorkerCache.WorkerProvider {
	private static final Log LOG       = LogFactory.getLog(JxioConnectionServer.class
	                                                                     .getCanonicalName());
	private final String                               name;
	private final EventQueueHandler listen_eqh;
	private final ServerPortal listener;
	private final JxioConnectionServer.Callbacks       appCallbacks;
	private int                                        numOfWorkers;
	private boolean                                    close     = false;
	private static ConcurrentLinkedQueue<ServerWorker> SPWorkers = new ConcurrentLinkedQueue<ServerWorker>();
	private HashMap<String, Integer> jxioConfigs;

	public ServerPortal getListener() {
		return listener;
	}

	public static ConcurrentLinkedQueue<ServerWorker> getSPWorkers() {
		return SPWorkers;
	}

	/**
	 * Ctor that receives from user number of messages to use in the jxio msgpool
	 *  @param uri
	 * @param numWorkers
	 *            - number of worker threads to start with (this number will grow on demand
	 * @param appCallbacks
 *            - application callbacks - what to do on new session event
//	 * @param msgPoolCount
	 * @param jxioConfigs
	 */
	public JxioConnectionServer(URI uri, int numWorkers, Callbacks appCallbacks, HashMap<String, Integer> jxioConfigs) {
		this.appCallbacks = appCallbacks;
		numOfWorkers = numWorkers;
		listen_eqh = new EventQueueHandler(null);
		listener = new ServerPortal(listen_eqh, uri, new PortalServerCallbacks(), this);
		this.jxioConfigs = jxioConfigs;
		name = "[JxioConnectionServer " + listener.toString() + " ]";
		for (int i = 1; i <= numWorkers; i++) {
			SPWorkers.add(new ServerWorker(i, listener.getUriForServer(), appCallbacks, jxioConfigs));
		}
		LOG.info(this.toString() + " JxioConnectionServer started, host " + uri.getHost() + " listening on port "
		        + uri.getPort() + ", numWorkers " + numWorkers);
	}

	/**
	 * Thread entry point when running as a new thread
	 */
	public void run() {
		work();
		listen_eqh.close();
		LOG.info("server done");
	}

	/**
	 * Entry point when running on the user thread
	 */
	public void work() {
		for (ServerWorker worker : SPWorkers) {
			worker.start();
		}
		listen_eqh.run();
	}

	/**
	 * Callbacks for the listener server portal
	 */
	public class PortalServerCallbacks implements ServerPortal.Callbacks {

		public void onSessionEvent(EventName event, EventReason reason) {
			LOG.info(JxioConnectionServer.this.toString() + " GOT EVENT " + event.toString() + "because of "
			        + reason.toString());
		}

		public void onSessionNew(ServerSession.SessionKey sesKey, String srcIP, WorkerCache.Worker workerHint) {
			if (close) {
				LOG.info(JxioConnectionServer.this.toString() + "Rejecting session");
				listener.reject(sesKey, EventReason.CONNECT_ERROR, "Server is closed");
				return;
			}
			ServerWorker spw;
			if (workerHint == null) {
				listener.reject(sesKey, EventReason.CONNECT_ERROR, "No availabe worker was found in cache");
				return;
			} else {
				spw = (ServerWorker) workerHint;
			}
			// add last
			SPWorkers.remove(spw);
			SPWorkers.add(spw);
			LOG.info(JxioConnectionServer.this.toString() + " Server worker number " + spw.portalIndex
			        + " got new session");
			forwardnewSession(sesKey, spw);

			//set remote ip
		}
	}

	public String toString() {
		return this.name;
	}

	/**
	 * 
	 * The object that implements this interface needs to be a thread
	 */
	public static interface Callbacks {
		public void newSessionOS(URI uri, OutputStream out);

		public void newSessionIS(URI uri, InputStream in);
	}

	/**
	 * Disconnect the server and all worker threads, This can't be undone
	 */
	public void disconnect() {
		if (close)
			return;

		close = true;
		for (Iterator<ServerWorker> it = SPWorkers.iterator(); it.hasNext();) {
			it.next().disconnect();
		}
		listen_eqh.stop();
	}

	@Override
	public WorkerCache.Worker getWorker() {
		for (WorkerCache.Worker w : SPWorkers) {
			if (w.isFree()) {
				return w;
			}
		}
		return createNewWorker();
	}

	private void forwardnewSession(ServerSession.SessionKey ses, ServerWorker s) {
		ServerSession session = new ServerSession(ses, s.getSessionCallbacks());
		URI uri;
		try {
			uri = new URI(ses.getUri());
			s.prepareSession(session, uri);
			listener.forward(s.getPortal(), session);
		} catch (URISyntaxException e) {
			LOG.fatal("URI could not be parsed");
		}
	}

	private ServerWorker createNewWorker() {
		LOG.info(this.toString() + " Creating new worker");
		numOfWorkers++;
		ServerWorker spw = new ServerWorker(numOfWorkers, listener.getUriForServer(), appCallbacks, jxioConfigs);
		spw.start();
		return spw;
	}
}
