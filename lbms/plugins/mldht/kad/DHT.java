/*
 *    This file is part of mlDHT. 
 * 
 *    mlDHT is free software: you can redistribute it and/or modify 
 *    it under the terms of the GNU General Public License as published by 
 *    the Free Software Foundation, either version 2 of the License, or 
 *    (at your option) any later version. 
 * 
 *    mlDHT is distributed in the hope that it will be useful, 
 *    but WITHOUT ANY WARRANTY; without even the implied warranty of 
 *    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the 
 *    GNU General Public License for more details. 
 * 
 *    You should have received a copy of the GNU General Public License 
 *    along with mlDHT.  If not, see <http://www.gnu.org/licenses/>. 
 */
package lbms.plugins.mldht.kad;

import java.io.File;
import java.io.IOException;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import lbms.plugins.mldht.DHTConfiguration;
import lbms.plugins.mldht.kad.Node.RoutingTableEntry;
import lbms.plugins.mldht.kad.messages.*;
import lbms.plugins.mldht.kad.messages.ErrorMessage.ErrorCode;
import lbms.plugins.mldht.kad.tasks.*;
import lbms.plugins.mldht.kad.utils.AddressUtils;
import lbms.plugins.mldht.kad.utils.ByteWrapper;
import lbms.plugins.mldht.kad.utils.PopulationEstimator;
import lbms.plugins.mldht.utlis.NIOConnectionManager;

/**
 * @author Damokles
 * 
 */
public class DHT implements DHTBase {
	
	public static enum DHTtype {
		IPV4_DHT("IPv4",20+4+2, 4+2, Inet4Address.class,20+8),
		IPV6_DHT("IPv6",20+16+2, 16+2, Inet6Address.class,40+8);
		
		public final int							HEADER_LENGTH;
		public final int 							NODES_ENTRY_LENGTH;
		public final int							ADDRESS_ENTRY_LENGTH;
		public final Class<? extends InetAddress>	PREFERRED_ADDRESS_TYPE;
		public final String 						shortName;
		private DHTtype(String shortName, int nodeslength, int addresslength, Class<? extends InetAddress> addresstype, int header) {
			this.shortName = shortName;
			this.NODES_ENTRY_LENGTH = nodeslength;
			this.PREFERRED_ADDRESS_TYPE = addresstype;
			this.ADDRESS_ENTRY_LENGTH = addresslength;
			this.HEADER_LENGTH = header;
		}

	}


	private static DHTLogger				logger;
	private static LogLevel					logLevel	= LogLevel.Info;

	private static ScheduledThreadPoolExecutor	scheduler;
	private static ThreadGroup					executorGroup;
	
	static {
		executorGroup = new ThreadGroup("mlDHT");
		int threads = Math.max(Runtime.getRuntime().availableProcessors(),2);
		scheduler = new ScheduledThreadPoolExecutor(threads, new ThreadFactory() {
			public Thread newThread (Runnable r) {
				Thread t = new Thread(executorGroup, r, "mlDHT Scheduler");

				t.setDaemon(true);
				return t;
			}
		});
		scheduler.setCorePoolSize(threads);
		scheduler.setMaximumPoolSize(threads*2);
		scheduler.setKeepAliveTime(20, TimeUnit.SECONDS);
		scheduler.allowCoreThreadTimeOut(true);


		logger = new DHTLogger() {
			public void log (String message) {
				System.out.println(message);
			};

			/*
			 * (non-Javadoc)
			 * 
			 * @see lbms.plugins.mldht.kad.DHTLogger#log(java.lang.Exception)
			 */
			public void log (Exception e) {
				e.printStackTrace();
			}
		};
	}

	private boolean							running;

	private boolean							bootstrapping;
	private long							lastBootstrap;

	DHTConfiguration						config;
	private Node							node;
	private RPCServerManager				serverManager;
	private Database						db;
	private TaskManager						tman;
	private File							table_file;
	private boolean							useRouterBootstrapping;

	private List<DHTStatsListener>			statsListeners;
	private List<DHTStatusListener>			statusListeners;
	private List<DHTIndexingListener>		indexingListeners;
	private DHTStats						stats;
	private DHTStatus						status;
	private PopulationEstimator				estimator;
	private AnnounceNodeCache				cache;
	private NIOConnectionManager			connectionManager;
	
	RPCStats								serverStats;

	private final DHTtype					type;
	private List<ScheduledFuture<?>>		scheduledActions = new ArrayList<ScheduledFuture<?>>();
	
	
	static Map<DHTtype,DHT> dhts; 


	public synchronized static Map<DHTtype, DHT> createDHTs() {
		if(dhts == null)
		{
			dhts = new EnumMap<DHTtype,DHT>(DHTtype.class);
			
			dhts.put(DHTtype.IPV4_DHT, new DHT(DHTtype.IPV4_DHT));
			dhts.put(DHTtype.IPV6_DHT, new DHT(DHTtype.IPV6_DHT));
		}
		
		return dhts;
	}
	
	public static DHT getDHT(DHTtype type)
	{
		return dhts.get(type);
	}

	private DHT(DHTtype type) {
		this.type = type;
		
		stats = new DHTStats();
		status = DHTStatus.Stopped;
		statsListeners = new ArrayList<DHTStatsListener>(2);
		statusListeners = new ArrayList<DHTStatusListener>(2);
		indexingListeners = new ArrayList<DHTIndexingListener>();
		estimator = new PopulationEstimator();
	}
	
	public void ping (PingRequest r) {
		if (!isRunning()) {
			return;
		}

		// ignore requests we get from ourself
		if (node.allLocalIDs().contains(r.getID())) {
			return;
		}

		PingResponse rsp = new PingResponse(r.getMTID());
		rsp.setDestination(r.getOrigin());
		r.getServer().sendMessage(rsp);
		node.recieved(this, r);
	}

	public void findNode (FindNodeRequest r) {
		if (!isRunning()) {
			return;
		}

		// ignore requests we get from ourself
		if (node.allLocalIDs().contains(r.getID())) {
			return;
		}

		node.recieved(this, r);
		// find the K closest nodes and pack them

		KClosestNodesSearch kns4 = null; 
		KClosestNodesSearch kns6 = null;
		
		// add our local address of the respective DHT for cross-seeding, but not for local requests
		if(r.doesWant4()) {
			kns4 = new KClosestNodesSearch(r.getTarget(), DHTConstants.MAX_ENTRIES_PER_BUCKET, getDHT(DHTtype.IPV4_DHT));
			kns4.fill(DHTtype.IPV4_DHT != type);
		}
		if(r.doesWant6()) {
			kns6 = new KClosestNodesSearch(r.getTarget(), DHTConstants.MAX_ENTRIES_PER_BUCKET, getDHT(DHTtype.IPV6_DHT));
			kns6.fill(DHTtype.IPV6_DHT != type);
		}


		FindNodeResponse fnr = new FindNodeResponse(r.getMTID(), kns4 != null ? kns4.pack() : null,kns6 != null ? kns6.pack() : null);
		fnr.setOrigin(r.getOrigin());
		r.getServer().sendMessage(fnr);
	}

	public void response (MessageBase r) {
		if (!isRunning()) {
			return;
		}

		node.recieved(this, r);
	}

	public void getPeers (GetPeersRequest r) {
		if (!isRunning()) {
			return;
		}

		// ignore requests we get from ourself
		if (node.allLocalIDs().contains(r.getID())) {
			return;
		}

		node.recieved(this, r);
		
		List<DBItem> dbl = db.sample(r.getInfoHash(), 50,type, r.isNoSeeds());

		for(DHTIndexingListener listener : indexingListeners)
		{
			List<PeerAddressDBItem> toAdd = listener.incomingPeersRequest(r.getInfoHash(), r.getOrigin().getAddress(), r.getID());
			if(dbl == null && !toAdd.isEmpty())
				dbl = new ArrayList<DBItem>();
			if(dbl != null && !toAdd.isEmpty())
				dbl.addAll(toAdd);
		}
		
		
			

		// generate a token
		ByteWrapper token = db.genToken(r.getOrigin().getAddress(), r
				.getOrigin().getPort(), r.getInfoHash());

		KClosestNodesSearch kns4 = null; 
		KClosestNodesSearch kns6 = null;
		
		// add our local address of the respective DHT for cross-seeding, but not for local requests
		if(r.doesWant4()) {
			kns4 = new KClosestNodesSearch(r.getTarget(), DHTConstants.MAX_ENTRIES_PER_BUCKET, getDHT(DHTtype.IPV4_DHT));
			kns4.fill(DHTtype.IPV4_DHT != type);
		}
		if(r.doesWant6()) {
			kns6 = new KClosestNodesSearch(r.getTarget(), DHTConstants.MAX_ENTRIES_PER_BUCKET, getDHT(DHTtype.IPV6_DHT));
			kns6.fill(DHTtype.IPV6_DHT != type);
		}

		
		GetPeersResponse resp = new GetPeersResponse(r.getMTID(), 
			kns4 != null ? kns4.pack() : null,
			kns6 != null ? kns6.pack() : null,
			db.insertForKeyAllowed(r.getInfoHash()) ? token.arr : null);
		
		if(r.isScrape())
		{
			resp.setScrapePeers(db.createScrapeFilter(r.getInfoHash(), false));
			resp.setScrapeSeeds(db.createScrapeFilter(r.getInfoHash(), true));			
		}

		
		resp.setPeerItems(dbl);
		resp.setDestination(r.getOrigin());
		r.getServer().sendMessage(resp);
	}

	public void announce (AnnounceRequest r) {
		if (!isRunning()) {
			return;
		}

		// ignore requests we get from ourself
		if (node.allLocalIDs().contains(r.getID())) {
			return;
		}

		node.recieved(this, r);
		// first check if the token is OK
		ByteWrapper token = new ByteWrapper(r.getToken());
		if (!db.checkToken(token, r.getOrigin().getAddress(), r
				.getOrigin().getPort(), r.getInfoHash())) {
			logDebug("DHT Received Announce Request with invalid token.");
			sendError(r, ErrorCode.ProtocolError.code, "Invalid Token");
			return;
		}

		logDebug("DHT Received Announce Request, adding peer to db: "
				+ r.getOrigin().getAddress());

		// everything OK, so store the value
		PeerAddressDBItem item = PeerAddressDBItem.createFromAddress(r.getOrigin().getAddress(), r.getPort(), r.isSeed());
		if(!AddressUtils.isBogon(item))
			db.store(r.getInfoHash(), item);

		// send a proper response to indicate everything is OK
		AnnounceResponse rsp = new AnnounceResponse(r.getMTID());
		rsp.setOrigin(r.getOrigin());
		r.getServer().sendMessage(rsp);
	}

	public void error (ErrorMessage r) {
		DHT.logError("Error [" + r.getCode() + "] from: " + r.getOrigin()
				+ " Message: \"" + r.getMessage() + "\" version:"+r.getVersion());
	}

	public void timeout (RPCCall r) {
		if (isRunning()) {
			node.onTimeout(r);
		}
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see lbms.plugins.mldht.kad.DHTBase#addDHTNode(java.lang.String, int)
	 */
	public void addDHTNode (String host, int hport) {
		if (!isRunning()) {
			return;
		}
		InetSocketAddress addr = new InetSocketAddress(host, hport);

		if (!addr.isUnresolved() && !AddressUtils.isBogon(addr)) {
			if(!type.PREFERRED_ADDRESS_TYPE.isInstance(addr.getAddress()) || node.getNumEntriesInRoutingTable() > DHTConstants.BOOTSTRAP_IF_LESS_THAN_X_PEERS)
				return;
			serverManager.getRandomActiveServer(true).ping(addr);
		}

	}

	/**
	 * returns a non-enqueued task for further configuration. or zero if the request cannot be serviced.
	 * use the task-manager to actually start the task.
	 */
	public PeerLookupTask createPeerLookup (byte[] info_hash) {
		if (!isRunning()) {
			return null;
		}
		Key id = new Key(info_hash);
		
		RPCServer srv = serverManager.getRandomActiveServer(false);
		if(srv == null)
			return null;

		PeerLookupTask lookupTask = new PeerLookupTask(srv, node, id);

		return lookupTask;
	}
	
	public AnnounceTask announce(PeerLookupTask lookup, boolean isSeed, int btPort) {
		if (!isRunning()) {
			return null;
		}
		
		// reuse the same server to make sure our tokens are still valid
		AnnounceTask announce = new AnnounceTask(lookup.getRPC(), node, lookup.getInfoHash(), btPort);
		announce.setSeed(isSeed);
		for (KBucketEntryAndToken kbe : lookup.getAnnounceCanidates())
		{
			announce.addToTodo(kbe);
		}

		tman.addTask(announce);

		return announce;
	}
	
	
	@Override
	public PingRefreshTask refreshBuckets (List<RoutingTableEntry> buckets,
			boolean cleanOnTimeout) {
		PingRefreshTask prt = new PingRefreshTask(serverManager.getRandomActiveServer(true), node, buckets,cleanOnTimeout);

		tman.addTask(prt, true);
		return prt;
	}
	
	public DHTConfiguration getConfig() {
		return config;
	}
	
	public AnnounceNodeCache getCache() {
		return cache;
	}
	
	public RPCServerManager getServerManager() {
		return serverManager;
	}
	
	public NIOConnectionManager getConnectionManager() {
		return connectionManager;
	}
	
	public PopulationEstimator getEstimator() {
		return estimator;
	}

	public DHTtype getType() {
		return type;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see lbms.plugins.mldht.kad.DHTBase#getStats()
	 */
	public DHTStats getStats () {
		return stats;
	}

	/**
	 * @return the status
	 */
	public DHTStatus getStatus () {
		return status;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see lbms.plugins.mldht.kad.DHTBase#isRunning()
	 */
	public boolean isRunning () {
		return running;
	}

	private int getPort() {
		int port = config.getListeningPort();
		if(port < 1 || port > 65535)
			port = 49001;
		return port;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see lbms.plugins.mldht.kad.DHTBase#start(java.lang.String, int)
	 */
	public void start (DHTConfiguration config)
			throws SocketException {
		if (running) {
			return;
		}

		this.config = config;
		useRouterBootstrapping = !config.noRouterBootstrap();

		setStatus(DHTStatus.Initializing);
		stats.resetStartedTimestamp();

		table_file = config.getNodeCachePath();
		Node.initDataStore(config);

		logInfo("Starting DHT on port " + getPort());
		resolveBootstrapAddresses();
		
		serverStats = new RPCStats();

		
		cache = new AnnounceNodeCache();
		stats.setRpcStats(serverStats);
		connectionManager = new NIOConnectionManager("mlDHT "+type.shortName+" NIO Selector");
		serverManager = new RPCServerManager(this);
		node = new Node(this);
		db = new Database();
		stats.setDbStats(db.getStats());
		tman = new TaskManager(this);
		running = true;
		
		scheduledActions.add(scheduler.scheduleAtFixedRate(new Runnable() {
			public void run() {
				// maintenance that should run all the time, before the first queries
				tman.dequeue();

				if (running)
					onStatsUpdate();
			}	
		}, 5000, DHTConstants.DHT_UPDATE_INTERVAL, TimeUnit.MILLISECONDS));

		// initialize as many RPC servers as we need 
		serverManager.refresh(System.currentTimeMillis());
		
		
		bootstrapping = true;
		node.loadTable(new Runnable() {
			public void run () {
				started();				
			}
		});


		
//		// does 10k random lookups and prints them to a file for analysis
//		scheduler.schedule(new Runnable() {
//			//PrintWriter		pw;
//			TaskListener	li	= new TaskListener() {
//									public synchronized void finished(Task t) {
//										NodeLookup nl = ((NodeLookup) t);
//										if (nl.closestSet.size() < DHTConstants.MAX_ENTRIES_PER_BUCKET)
//											return;
//										/*
//										StringBuilder b = new StringBuilder();
//										b.append(nl.targetKey.toString(false));
//										b.append(",");
//										for (Key i : nl.closestSet)
//											b.append(i.toString(false).substring(0, 12) + ",");
//										b.deleteCharAt(b.length() - 1);
//										pw.println(b);
//										pw.flush();
//										*/
//									}
//								};
//
//			public void run() {
//				if(type == DHTtype.IPV6_DHT)
//					return;
//				/*
//				try
//				{
//					pw = new PrintWriter("H:\\mldht.log");
//				} catch (FileNotFoundException e)
//				{
//					e.printStackTrace();
//				}*/
//				for (int i = 0; i < 10000; i++)
//				{
//					NodeLookup l = new NodeLookup(Key.createRandomKey(), srv, node, false);
//					if (canStartTask())
//						l.start();
//					tman.addTask(l);
//					l.addListener(li);
//					if (i == (10000 - 1))
//						l.addListener(new TaskListener() {
//							public void finished(Task t) {
//								System.out.println("10k lookups done");
//							}
//						});
//				}
//			}
//		}, 1, TimeUnit.MINUTES);
		

	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see lbms.plugins.mldht.kad.DHTBase#started()
	 */
	public void started () {
		bootstrapping = false;
		bootstrap();
		
		/*
		if(type == DHTtype.IPV6_DHT)
		{
			Task t = new KeyspaceCrawler(srv, node);
			tman.addTask(t);
		}*/
			
		
		
		scheduledActions.add(scheduler.scheduleAtFixedRate(new Runnable() {
			public void run () {
				try {
					update();
				} catch (RuntimeException e) {
					log(e, LogLevel.Fatal);
				}
			}
		}, 5000, DHTConstants.DHT_UPDATE_INTERVAL, TimeUnit.MILLISECONDS));
		
		scheduledActions.add(scheduler.scheduleAtFixedRate(new Runnable() {
			public void run() {
				try
				{
					long now = System.currentTimeMillis();


					db.expire(now);
					cache.cleanup(now);					
				} catch (Exception e)
				{
					log(e, LogLevel.Fatal);
				}

			}
		}, 1000, DHTConstants.CHECK_FOR_EXPIRED_ENTRIES, TimeUnit.MILLISECONDS));
		
		scheduledActions.add(scheduler.scheduleAtFixedRate(new Runnable() {
			public void run () {
				try {
					for(RPCServer srv : serverManager.getAllServers())
						findNode(Key.createRandomKey(), false, false, srv).setInfo("Random Refresh Lookup");
				} catch (RuntimeException e) {
					log(e, LogLevel.Fatal);
				}
				
				try {
					if(!node.isInSurvivalMode())
						node.saveTable(table_file);
				} catch (IOException e) {
					e.printStackTrace();
				}
			}
		}, DHTConstants.RANDOM_LOOKUP_INTERVAL, DHTConstants.RANDOM_LOOKUP_INTERVAL, TimeUnit.MILLISECONDS));
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see lbms.plugins.mldht.kad.DHTBase#stop()
	 */
	public void stop () {
		if (!running) {
			return;
		}

		//scheduler.shutdown();
		logInfo("Stopping DHT");
		for (Task t : tman.getActiveTasks()) {
			t.kill();
		}
		
		for(ScheduledFuture<?> future : scheduledActions)
			future.cancel(false);
		scheduler.getQueue().removeAll(scheduledActions);
		scheduledActions.clear();

		serverManager.destroy();
		try {
			node.saveTable(table_file);
		} catch (IOException e) {
			e.printStackTrace();
		}
		running = false;
		stopped();
		tman = null;
		db = null;
		node = null;
		cache = null;
		serverManager = null;
		setStatus(DHTStatus.Stopped);
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see lbms.plugins.mldht.kad.DHTBase#getNode()
	 */
	public Node getNode () {
		return node;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see lbms.plugins.mldht.kad.DHTBase#getTaskManager()
	 */
	public TaskManager getTaskManager () {
		return tman;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see lbms.plugins.mldht.kad.DHTBase#stopped()
	 */
	public void stopped () {
		// TODO Auto-generated method stub

	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see lbms.plugins.mldht.kad.DHTBase#update()
	 */
	public void update () {
		
		long now = System.currentTimeMillis();
		
		serverManager.refresh(now);
		
		if (!isRunning()) {
			return;
		}

		node.doBucketChecks(now);

		if (!bootstrapping) {
			if (node.getNumEntriesInRoutingTable() < DHTConstants.BOOTSTRAP_IF_LESS_THAN_X_PEERS || now - lastBootstrap > DHTConstants.SELF_LOOKUP_INTERVAL) {
				//regualary search for our id to update routing table
				bootstrap();
			} else {
				setStatus(DHTStatus.Running);
			}
		}

		
	}
	
	
	private void resolveBootstrapAddresses() {
		List<InetSocketAddress> nodeAddresses =  new ArrayList<InetSocketAddress>();
		for(int i = 0;i<DHTConstants.BOOTSTRAP_NODES.length;i++)
		{
			try {
				String hostname = DHTConstants.BOOTSTRAP_NODES[i];
				int port = DHTConstants.BOOTSTRAP_PORTS[i];
			

				 for(InetAddress addr : InetAddress.getAllByName(hostname))
				 {
					 nodeAddresses.add(new InetSocketAddress(addr, port));
				 }
			} catch (Exception e) {
				// do nothing
			}
		}
		
		if(nodeAddresses.size() > 0)
			DHTConstants.BOOTSTRAP_NODE_ADDRESSES = nodeAddresses;
	}

	/**
	 * Initiates a Bootstrap.
	 * 
	 * This function bootstraps with router.bittorrent.com if there are less
	 * than 10 Peers in the routing table. If there are more then a lookup on
	 * our own ID is initiated. If the either Task is finished than it will try
	 * to fill the Buckets.
	 */
	public synchronized void bootstrap () {
		if (!isRunning() || bootstrapping || System.currentTimeMillis() - lastBootstrap < DHTConstants.BOOTSTRAP_MIN_INTERVAL) {
			return;
		}
		
		if (useRouterBootstrapping || node.getNumEntriesInRoutingTable() > 1) {
			
			final AtomicInteger finishCount = new AtomicInteger();
			bootstrapping = true;
			
			TaskListener bootstrapListener = new TaskListener() {
				public void finished (Task t) {
					int count = finishCount.decrementAndGet();
					if(count == 0)
						bootstrapping = false;
					// fill the remaining buckets once all bootstrap operations finished
					if (count == 0 && running && node.getNumEntriesInRoutingTable() > DHTConstants.USE_BT_ROUTER_IF_LESS_THAN_X_PEERS) {
						node.fillBuckets(DHT.this);
					}
				}
			};

			logInfo("Bootstrapping...");
			lastBootstrap = System.currentTimeMillis();

			for(RPCServer srv : serverManager.getAllServers())
			{
				finishCount.incrementAndGet();
				NodeLookup nl = findNode(srv.getDerivedID(), true, true, srv);
				if (nl == null) {
					bootstrapping = false;
					break;
				} else if (node.getNumEntriesInRoutingTable() < DHTConstants.USE_BT_ROUTER_IF_LESS_THAN_X_PEERS) {
					if (useRouterBootstrapping) {
						resolveBootstrapAddresses();
						List<InetSocketAddress> addrs = new ArrayList<InetSocketAddress>(DHTConstants.BOOTSTRAP_NODE_ADDRESSES);
						Collections.shuffle(addrs);
						
						for (InetSocketAddress addr : addrs)
						{
							if (!type.PREFERRED_ADDRESS_TYPE.isInstance(addr.getAddress()))
								continue;
							nl.addDHTNode(addr.getAddress(),addr.getPort());
							break;
						}
					}
					nl.addListener(bootstrapListener);
					nl.setInfo("Bootstrap: Find Peers.");

					tman.dequeue();

				} else {
					nl.setInfo("Bootstrap: search for ourself.");
					nl.addListener(bootstrapListener);
					tman.dequeue();
				}
				
			}
		}
	}

	private NodeLookup findNode (Key id, boolean isBootstrap,
			boolean isPriority, RPCServer server) {
		if (!running || server == null) {
			return null;
		}

		NodeLookup at = new NodeLookup(id, server, node, isBootstrap);
		tman.addTask(at, isPriority);
		return at;
	}

	/**
	 * Do a NodeLookup.
	 * 
	 * @param id The id of the key to search
	 */
	public NodeLookup findNode (Key id) {
		return findNode(id, false, false,serverManager.getRandomActiveServer(true));
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see lbms.plugins.mldht.kad.DHTBase#fillBucket(lbms.plugins.mldht.kad.KBucket)
	 */
	public NodeLookup fillBucket (Key id, KBucket bucket) {
		bucket.updateRefreshTimer();
		return findNode(id, false, true, serverManager.getRandomActiveServer(true));
	}

	public PingRefreshTask refreshBucket (KBucket bucket) {
		RPCServer srv = serverManager.getRandomActiveServer(true);
		if(srv == null)
			return null;

		PingRefreshTask prt = new PingRefreshTask(srv, node, bucket, false);
		tman.addTask(prt); // low priority, the bootstrap does a high prio one if necessary

		return prt;
	}

	public void sendError (MessageBase origMsg, int code, String msg) {
		sendError(origMsg.getOrigin(), origMsg.getMTID(), code, msg, origMsg.getServer());
	}

	public void sendError (InetSocketAddress target, byte[] mtid, int code,
			String msg, RPCServer srv) {
		ErrorMessage errMsg = new ErrorMessage(mtid, code, msg);
		errMsg.setDestination(target);
		srv.sendMessage(errMsg);
	}

	public boolean canStartTask (Task toCheck) {
		// we can start a task if we have less then  7 runnning and
		// there are at least 16 RPC slots available
		return tman.getNumTasks() < DHTConstants.MAX_ACTIVE_TASKS * Math.max(1, serverManager.getActiveServerCount()) && toCheck.getRPC().getNumActiveRPCCalls() + 16 < DHTConstants.MAX_ACTIVE_CALLS;
	}

	public Key getOurID () {
		if (running) {
			return node.getRootID();
		}
		return null;
	}

	private void onStatsUpdate () {
		stats.setNumTasks(tman.getNumTasks() + tman.getNumQueuedTasks());
		stats.setNumPeers(node.getNumEntriesInRoutingTable());
		int numSent = 0;int numReceived = 0;int activeCalls = 0;
		for(RPCServer s : serverManager.getAllServers())
		{
			numSent += s.getNumSent();
			numReceived += s.getNumReceived();
			activeCalls += s.getNumActiveRPCCalls();
		}
		stats.setNumSentPackets(numSent);
		stats.setNumReceivedPackets(numReceived);
		stats.setNumRpcCalls(activeCalls);

		for (int i = 0; i < statsListeners.size(); i++) {
			statsListeners.get(i).statsUpdated(stats);
		}
	}

	private void setStatus (DHTStatus status) {
		if (!this.status.equals(status)) {
			DHTStatus old = this.status;
			this.status = status;
			if (!statusListeners.isEmpty())
			{
				for (int i = 0; i < statusListeners.size(); i++)
				{
					statusListeners.get(i).statusChanged(status, old);
				}
			}
		}
	}

	public void addStatsListener (DHTStatsListener listener) {
		statsListeners.add(listener);
	}

	public void removeStatsListener (DHTStatsListener listener) {
		statsListeners.remove(listener);
	}

	public void addIndexingLinstener(DHTIndexingListener listener) {
		indexingListeners.add(listener);
	}

	public void addStatusListener (DHTStatusListener listener) {
		statusListeners.add(listener);
	}

	public void removeStatusListener (DHTStatusListener listener) {
		statusListeners.remove(listener);
	}
	
	public String getDiagnostics() {
		StringBuilder b = new StringBuilder();

		b.append("==========================\n");
		b.append("DHT Diagnostics. Type ").append(type).append('\n');
		b.append("# of active servers / all servers: ").append(serverManager.getActiveServerCount()).append('/').append(serverManager.getServerCount()).append('\n');
		
		if(!isRunning())
			return b.toString();
		
		b.append("-----------------------\n");
		b.append("Stats\n");
		b.append(stats.toString());
		b.append("-----------------------\n");
		b.append("Routing table\n");
		b.append(node.toString());
		b.append("-----------------------\n");
		b.append("RPC Servers\n");
		for(RPCServer srv : serverManager.getAllServers())
			b.append(srv.toString());
		b.append("-----------------------\n");
		b.append("Lookup Cache\n");
		b.append(cache.toString());
		b.append("-----------------------\n");
		b.append("Tasks\n");
		b.append(tman.toString());
		b.append("\n\n\n");
		
		
		
		
		return b.toString();
	}

	/**
	 * @return the logger
	 */
	//	public static DHTLogger getLogger () {
	//		return logger;
	//	}
	/**
	 * @param logger the logger to set
	 */
	public static void setLogger (DHTLogger logger) {
		DHT.logger = logger;
	}

	/**
	 * @return the logLevel
	 */
	public static LogLevel getLogLevel () {
		return logLevel;
	}

	/**
	 * @param logLevel the logLevel to set
	 */
	public static void setLogLevel (LogLevel logLevel) {
		DHT.logLevel = logLevel;
		logger.log("Change LogLevel to: " + logLevel);
	}

	/**
	 * @return the scheduler
	 */
	public static ScheduledExecutorService getScheduler () {
		return scheduler;
	}

	public static void log (String message, LogLevel level) {
		if (level.compareTo(logLevel) < 1) { // <=
			logger.log(message);
		}
	}

	public static void log (Exception e, LogLevel level) {
		if (level.compareTo(logLevel) < 1) { // <=
			logger.log(e);
		}
	}

	public static void logFatal (String message) {
		log(message, LogLevel.Fatal);
	}

	public static void logError (String message) {
		log(message, LogLevel.Error);
	}

	public static void logInfo (String message) {
		log(message, LogLevel.Info);
	}

	public static void logDebug (String message) {
		log(message, LogLevel.Debug);
	}

	public static void logVerbose (String message) {
		log(message, LogLevel.Verbose);
	}

	public static boolean isLogLevelEnabled (LogLevel level) {
		return level.compareTo(logLevel) < 1;
	}

	public static enum LogLevel {
		Fatal, Error, Info, Debug, Verbose
	}
}
