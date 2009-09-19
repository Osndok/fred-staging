/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node;

import java.util.Arrays;
import java.util.HashSet;

import freenet.crypt.DSAPublicKey;
import freenet.crypt.SHA256;
import freenet.io.comm.ByteCounter;
import freenet.io.comm.DMT;
import freenet.io.comm.DisconnectedException;
import freenet.io.comm.Message;
import freenet.io.comm.MessageFilter;
import freenet.io.comm.NotConnectedException;
import freenet.io.comm.PeerRestartedException;
import freenet.io.xfer.WaitedTooLongException;
import freenet.keys.NodeSSK;
import freenet.keys.SSKBlock;
import freenet.keys.SSKVerifyException;
import freenet.support.Logger;
import freenet.support.OOMHandler;
import freenet.support.ShortBuffer;
import freenet.support.io.NativeThread;

/**
 * SSKs require separate logic for inserts and requests, for various reasons:
 * - SSKs can collide.
 * - SSKs have only 1kB of data, so we can pack it into the DataReply, and we don't need to
 *   wait for a long data-transfer timeout.
 * - SSKs have pubkeys, which don't always need to be sent.
 */
public class SSKInsertSender implements PrioRunnable, AnyInsertSender, ByteCounter {

    // Constants
    static final int ACCEPTED_TIMEOUT = 10000;
    static final int SEARCH_TIMEOUT = 60000;

    // Basics
    final NodeSSK myKey;
    final double target;
    final long uid;
    short htl;
    final PeerNode source;
    final Node node;
    /** SSK's pubkey */
    final DSAPublicKey pubKey;
    /** SSK's pubkey's hash */
    final byte[] pubKeyHash;
    /** Data (we know at start of insert) - can change if we get a collision */
    byte[] data;
    /** Headers (we know at start of insert) - can change if we get a collision */
    byte[] headers;
    final boolean fromStore;
    final long startTime;
    private boolean sentRequest;
    private boolean hasCollided;
    private boolean hasRecentlyCollided;
    private SSKBlock block;
    private static boolean logMINOR;
    private final boolean canWriteClientCache;
    private final boolean canWriteDatastore;
    
    private int status = -1;
    /** Still running */
    static final int NOT_FINISHED = -1;
    /** Successful insert */
    static final int SUCCESS = 0;
    /** Route not found */
    static final int ROUTE_NOT_FOUND = 1;
    /** Internal error */
    static final int INTERNAL_ERROR = 3;
    /** Timed out waiting for response */
    static final int TIMED_OUT = 4;
    /** Locally Generated a RejectedOverload */
    static final int GENERATED_REJECTED_OVERLOAD = 5;
    /** Could not get off the node at all! */
    static final int ROUTE_REALLY_NOT_FOUND = 6;
    
    SSKInsertSender(SSKBlock block, long uid, short htl, PeerNode source, Node node, boolean fromStore, boolean canWriteClientCache, boolean canWriteDatastore) {
    	logMINOR = Logger.shouldLog(Logger.MINOR, this);
    	this.fromStore = fromStore;
    	this.node = node;
    	this.source = source;
    	this.htl = htl;
    	this.uid = uid;
    	myKey = block.getKey();
    	data = block.getRawData();
    	headers = block.getRawHeaders();
    	target = myKey.toNormalizedDouble();
    	pubKey = myKey.getPubKey();
    	if(pubKey == null)
    		throw new IllegalArgumentException("Must have pubkey to insert data!!");
    	// pubKey.fingerprint() is not the same as hash(pubKey.asBytes())). FIXME it should be!
    	byte[] pubKeyAsBytes = pubKey.asBytes();
    	pubKeyHash = SHA256.digest(pubKeyAsBytes);
    	this.block = block;
    	startTime = System.currentTimeMillis();
    	this.canWriteClientCache = canWriteClientCache;
    	this.canWriteDatastore = canWriteDatastore;
    }

    void start() {
    	node.executor.execute(this, "SSKInsertSender for UID "+uid+" on "+node.getDarknetPortNumber()+" at "+System.currentTimeMillis());
    }
    
	public void run() {
	    freenet.support.Logger.OSThread.logPID(this);
        short origHTL = htl;
        node.addInsertSender(myKey, htl, this);
        try {
        	realRun();
		} catch (OutOfMemoryError e) {
			OOMHandler.handleOOM(e);
            if(status == NOT_FINISHED)
            	finish(INTERNAL_ERROR, null);
        } catch (Throwable t) {
            Logger.error(this, "Caught "+t, t);
            if(status == NOT_FINISHED)
            	finish(INTERNAL_ERROR, null);
        } finally {
        	if(logMINOR) Logger.minor(this, "Finishing "+this);
            if(status == NOT_FINISHED)
            	finish(INTERNAL_ERROR, null);
        	node.removeInsertSender(myKey, origHTL, this);
        }
	}

    private void realRun() {
        HashSet<PeerNode> nodesRoutedTo = new HashSet<PeerNode>();
        
        PeerNode next = null;
        while(true) {
            /*
             * If we haven't routed to any node yet, decrement according to the source.
             * If we have, decrement according to the node which just failed.
             * Because:
             * 1) If we always decrement according to source then we can be at max or min HTL
             * for a long time while we visit *every* peer node. This is BAD!
             * 2) The node which just failed can be seen as the requestor for our purposes.
             */
            // Decrement at this point so we can DNF immediately on reaching HTL 0.
            htl = node.decrementHTL(sentRequest ? next : source, htl);
            if(htl == 0) {
                // Send an InsertReply back
                finish(SUCCESS, null);
                return;
            }
            
            // Route it
            next = node.peers.closerPeer(source, nodesRoutedTo, target, true, node.isAdvancedModeEnabled(), -1, null,
			        null, htl);
            
            if(next == null) {
                // Backtrack
                finish(ROUTE_NOT_FOUND, null);
                return;
            }
            if(logMINOR) Logger.minor(this, "Routing insert to "+next);
            nodesRoutedTo.add(next);
            
            Message request = DMT.createFNPSSKInsertRequestNew(uid, htl, myKey);
            
            // Wait for ack or reject... will come before even a locally generated DataReply
            
            MessageFilter mfAccepted = MessageFilter.create().setSource(next).setField(DMT.UID, uid).setTimeout(ACCEPTED_TIMEOUT).setType(DMT.FNPSSKAccepted);
            MessageFilter mfRejectedLoop = MessageFilter.create().setSource(next).setField(DMT.UID, uid).setTimeout(ACCEPTED_TIMEOUT).setType(DMT.FNPRejectedLoop);
            MessageFilter mfRejectedOverload = MessageFilter.create().setSource(next).setField(DMT.UID, uid).setTimeout(ACCEPTED_TIMEOUT).setType(DMT.FNPRejectedOverload);
            // mfRejectedOverload must be the last thing in the or
            // So its or pointer remains null
            // Otherwise we need to recreate it below
            mfRejectedOverload.clearOr();
            MessageFilter mf = mfAccepted.or(mfRejectedLoop.or(mfRejectedOverload));

            // Send to next node
            
            try {
				next.sendAsync(request, null, this);
			} catch (NotConnectedException e1) {
				if(logMINOR) Logger.minor(this, "Not connected to "+next);
				continue;
			}
            sentRequest = true;
            
            Message msg = null;
            
            /*
             * Because messages may be re-ordered, it is
             * entirely possible that we get a non-local RejectedOverload,
             * followed by an Accepted. So we must loop here.
             */
            
            while (true) {
            	
				try {
					msg = node.usm.waitFor(mf, this);
				} catch (DisconnectedException e) {
					Logger.normal(this, "Disconnected from " + next
							+ " while waiting for Accepted");
					break;
				}
				
				if (msg == null) {
					// Terminal overload
					// Try to propagate back to source
					if(logMINOR) Logger.minor(this, "Timeout");
					next.localRejectedOverload("Timeout");
					forwardRejectedOverload();
					break;
				}
				
				if (msg.getSpec() == DMT.FNPRejectedOverload) {
					// Non-fatal - probably still have time left
					if (msg.getBoolean(DMT.IS_LOCAL)) {
						next.localRejectedOverload("ForwardRejectedOverload3");
						if(logMINOR) Logger.minor(this, "Local RejectedOverload, moving on to next peer");
						// Give up on this one, try another
						break;
					} else {
						forwardRejectedOverload();
					}
					continue;
				}
				
				if (msg.getSpec() == DMT.FNPRejectedLoop) {
					next.successNotOverload();
					// Loop - we don't want to send the data to this one
					break;
				}
				
				if (msg.getSpec() != DMT.FNPSSKAccepted) {
					Logger.error(this,
							"Unexpected message waiting for SSKAccepted: "
									+ msg);
					break;
				}
				// Otherwise is an FNPSSKAccepted
				break;
            }
            
            if((msg == null) || (msg.getSpec() != DMT.FNPSSKAccepted)) continue;
            
            if(logMINOR) Logger.minor(this, "Got Accepted on "+this);
            
            // Send the headers and data
            
            Message headersMsg = DMT.createFNPSSKInsertRequestHeaders(uid, headers);
            Message dataMsg = DMT.createFNPSSKInsertRequestData(uid, data);
            
            try {
				next.sendAsync(headersMsg, null, this);
				next.sendThrottledMessage(dataMsg, data.length, this, SSKInsertHandler.DATA_INSERT_TIMEOUT, false, null);
			} catch (NotConnectedException e1) {
				if(logMINOR) Logger.minor(this, "Not connected to "+next);
				continue;
			} catch (WaitedTooLongException e) {
				Logger.error(this, "Waited too long to send "+dataMsg+" to "+next+" on "+this);
				continue;
			} catch (SyncSendWaitedTooLongException e) {
				// Impossible
			} catch (PeerRestartedException e) {
				if(logMINOR) Logger.minor(this, "Peer restarted: "+next);
				continue;
			}
            
            // Do we need to send them the pubkey?
            
            if(msg.getBoolean(DMT.NEED_PUB_KEY)) {
            	Message pkMsg = DMT.createFNPSSKPubKey(uid, pubKey);
            	try {
            		next.sendAsync(pkMsg, null, this);
            	} catch (NotConnectedException e) {
            		if(logMINOR) Logger.minor(this, "Node disconnected while sending pubkey: "+next);
            		continue;
            	}
            	
            	// Wait for the SSKPubKeyAccepted
            	
            	MessageFilter mf1 = MessageFilter.create().setSource(next).setField(DMT.UID, uid).setTimeout(ACCEPTED_TIMEOUT).setType(DMT.FNPSSKPubKeyAccepted);
            	
            	Message newAck;
				try {
					newAck = node.usm.waitFor(mf1, this);
				} catch (DisconnectedException e) {
					if(logMINOR) Logger.minor(this, "Disconnected from "+next);
					continue;
				}
            	
            	if(newAck == null) {
					// Try to propagate back to source
            		if(logMINOR) Logger.minor(this, "Timeout");
					next.localRejectedOverload("Timeout2");
					forwardRejectedOverload();
					// Try another peer
					continue;
            	}
            }
            
            // We have sent them the pubkey, and the data.
            // Wait for the response.
            
            /** What are we waiting for now??:
             * - FNPRouteNotFound - couldn't exhaust HTL, but send us the 
             *   data anyway please
             * - FNPInsertReply - used up all HTL, yay
             * - FNPRejectOverload - propagating an overload error :(
             * - FNPDataFound - target already has the data, and the data is
             *   an SVK/SSK/KSK, therefore could be different to what we are
             *   inserting.
             * - FNPDataInsertRejected - the insert was invalid
             */
            
            MessageFilter mfInsertReply = MessageFilter.create().setSource(next).setField(DMT.UID, uid).setTimeout(SEARCH_TIMEOUT).setType(DMT.FNPInsertReply);
            mfRejectedOverload.setTimeout(SEARCH_TIMEOUT);
            mfRejectedOverload.clearOr();
            MessageFilter mfRouteNotFound = MessageFilter.create().setSource(next).setField(DMT.UID, uid).setTimeout(SEARCH_TIMEOUT).setType(DMT.FNPRouteNotFound);
            MessageFilter mfDataInsertRejected = MessageFilter.create().setSource(next).setField(DMT.UID, uid).setTimeout(SEARCH_TIMEOUT).setType(DMT.FNPDataInsertRejected);
            
            mf = mfRouteNotFound.or(mfInsertReply.or(mfRejectedOverload.or(mfDataInsertRejected)));
            
            while (true) {
				try {
					msg = node.usm.waitFor(mf, this);
				} catch (DisconnectedException e) {
					Logger.normal(this, "Disconnected from " + next
							+ " while waiting for InsertReply on " + this);
					break;
				}

				if (msg == null) {
					// Timeout :(
					// Fairly serious problem
					Logger.error(this, "Timeout (" + msg + ") after Accepted in insert; to ("+next+")");
					// Terminal overload
					// Try to propagate back to source
					next.localRejectedOverload("AfterInsertAcceptedTimeout");
					finish(TIMED_OUT, next);
					return;
				}
				
				if (msg.getSpec() == DMT.FNPRejectedOverload) {
					// Probably non-fatal, if so, we have time left, can try next one
					if (msg.getBoolean(DMT.IS_LOCAL)) {
						next.localRejectedOverload("ForwardRejectedOverload4");
						if(logMINOR) Logger.minor(this,
								"Local RejectedOverload, moving on to next peer");
						// Give up on this one, try another
						break;
					} else {
						forwardRejectedOverload();
					}
					continue; // Wait for any further response
				}

				if (msg.getSpec() == DMT.FNPRouteNotFound) {
					if(logMINOR) Logger.minor(this, "Rejected: RNF");
					short newHtl = msg.getShort(DMT.HTL);
					if (htl > newHtl)
						htl = newHtl;
					// Finished as far as this node is concerned
					next.successNotOverload();
					break;
				}

				if (msg.getSpec() == DMT.FNPDataInsertRejected) {
					next.successNotOverload();
					short reason = msg.getShort(DMT.DATA_INSERT_REJECTED_REASON);
					if(logMINOR) Logger.minor(this, "DataInsertRejected: " + reason);
					if (reason == DMT.DATA_INSERT_REJECTED_VERIFY_FAILED) {
						if (fromStore) {
							// That's odd...
							Logger.error(this,"Verify failed on next node "
									+ next + " for DataInsert but we were sending from the store!");
						}
					}
					Logger.error(this, "SSK insert rejected! Reason="
							+ DMT.getDataInsertRejectedReason(reason));
					break; // What else can we do?
				}
				
				if(msg.getSpec() == DMT.FNPSSKDataFoundHeaders) {
					/**
					 * Data was already on node, and was NOT equal to what we sent. COLLISION!
					 * 
					 * We can either accept the old data or the new data.
					 * OLD DATA:
					 * - KSK-based stuff is usable. Well, somewhat; a node could spoof KSKs on
					 * receiving an insert, (if it knows them in advance), but it cannot just 
					 * start inserts to overwrite old SSKs.
					 * - You cannot "update" an SSK.
					 * NEW DATA:
					 * - KSK-based stuff not usable. (Some people think this is a good idea!).
					 * - Illusion of updatability. (VERY BAD IMHO, because it's not really
					 * updatable... FIXME implement TUKs; would determine latest version based
					 * on version number, and propagate on request with a certain probability or
					 * according to time. However there are good arguments to do updating at a
					 * higher level (e.g. key bottleneck argument), and TUKs should probably be 
					 * distinct from SSKs.
					 * 
					 * For now, accept the "old" i.e. preexisting data.
					 */
					Logger.normal(this, "Got collision on "+myKey+" ("+uid+") sending to "+next.getPeer());
					
        			headers = ((ShortBuffer) msg.getObject(DMT.BLOCK_HEADERS)).getData();
        			// Wait for the data
        			MessageFilter mfData = MessageFilter.create().setSource(next).setField(DMT.UID, uid).setTimeout(RequestSender.FETCH_TIMEOUT).setType(DMT.FNPSSKDataFoundData);
        			Message dataMessage;
        			try {
						dataMessage = node.usm.waitFor(mfData, this);
					} catch (DisconnectedException e) {
						if(logMINOR)
							Logger.minor(this, "Disconnected: "+next+" getting datareply for "+this);
						break;
					}
					if(dataMessage == null) {
    					Logger.error(this, "Got headers but not data for datareply for insert from "+this);
    					break;
					}
					// collided, overwrite data with remote data
					try {
						data = ((ShortBuffer) dataMessage.getObject(DMT.DATA)).getData();
						block = new SSKBlock(data, block.getRawHeaders(), block.getKey(), false);
						
						synchronized(this) {
							hasRecentlyCollided = true;
							hasCollided = true;
							notifyAll();
						}
					} catch (SSKVerifyException e) {
    					Logger.error(this, "Invalid SSK from remote on collusion: " + this + ":" +block);
						finish(INTERNAL_ERROR, next);
					}
					continue; // The node will now propagate the new data. There is no need to move to the next node yet.
        		}
				
				if (msg.getSpec() != DMT.FNPInsertReply) {
					Logger.error(this, "Unknown reply: " + msg);
					finish(INTERNAL_ERROR, next);
				}
						
				// Our task is complete
				next.successNotOverload();
				finish(SUCCESS, next);
				return;
            }
        }
    }

	private boolean hasForwardedRejectedOverload;
    
    synchronized boolean receivedRejectedOverload() {
    	return hasForwardedRejectedOverload;
    }
    
    /** Forward RejectedOverload to the request originator.
     * DO NOT CALL if have a *local* RejectedOverload.
     */
    private synchronized void forwardRejectedOverload() {
    	if(hasForwardedRejectedOverload) return;
    	hasForwardedRejectedOverload = true;
   		notifyAll();
	}
    
    private void finish(int code, PeerNode next) {
    	if(logMINOR) Logger.minor(this, "Finished: "+code+" on "+this, new Exception("debug"));
    	synchronized(this) {
    		if(status != NOT_FINISHED)
    			throw new IllegalStateException("finish() called with "+code+" when was already "+status);
    		
    		if((code == ROUTE_NOT_FOUND) && !sentRequest)
    			code = ROUTE_REALLY_NOT_FOUND;
    		
    		status = code;
    		
    		notifyAll();
        }

        if(code == SUCCESS && next != null)
        	next.onSuccess(true, true);
        
        if(logMINOR) Logger.minor(this, "Set status code: "+getStatusString());
        // Nothing to wait for, no downstream transfers, just exit.
    }

    public synchronized int getStatus() {
        return status;
    }
    
    public synchronized short getHTL() {
        return htl;
    }

    /**
     * @return The current status as a string
     */
    public synchronized String getStatusString() {
        if(status == SUCCESS)
            return "SUCCESS";
        if(status == ROUTE_NOT_FOUND)
            return "ROUTE NOT FOUND";
        if(status == NOT_FINISHED)
            return "NOT FINISHED";
        if(status == INTERNAL_ERROR)
        	return "INTERNAL ERROR";
        if(status == TIMED_OUT)
        	return "TIMED OUT";
        if(status == GENERATED_REJECTED_OVERLOAD)
        	return "GENERATED REJECTED OVERLOAD";
        if(status == ROUTE_REALLY_NOT_FOUND)
        	return "ROUTE REALLY NOT FOUND";
        return "UNKNOWN STATUS CODE: "+status;
    }

	public boolean sentRequest() {
		return sentRequest;
	}
	
	public synchronized boolean hasRecentlyCollided() {
		boolean status = hasRecentlyCollided;
		hasRecentlyCollided = false;
		return status;
	}
	
	public boolean hasCollided() {
		return hasCollided;
	}
	
	public byte[] getPubkeyHash() {
		return headers;
	}

	public byte[] getHeaders() {
		return headers;
	}
	
	public byte[] getData() {
		return data;
	}

	public SSKBlock getBlock() {
		return block;
	}

	public long getUID() {
		return uid;
	}

	private final Object totalBytesSync = new Object();
	private int totalBytesSent;
	
	public void sentBytes(int x) {
		synchronized(totalBytesSync) {
			totalBytesSent += x;
		}
		node.nodeStats.insertSentBytes(true, x);
	}
	
	public int getTotalSentBytes() {
		synchronized(totalBytesSync) {
			return totalBytesSent;
		}
	}
	
	private int totalBytesReceived;
	
	public void receivedBytes(int x) {
		synchronized(totalBytesSync) {
			totalBytesReceived += x;
		}
		node.nodeStats.insertReceivedBytes(true, x);
	}
	
	public int getTotalReceivedBytes() {
		synchronized(totalBytesSync) {
			return totalBytesReceived;
		}
	}

	public void sentPayload(int x) {
		node.sentPayload(x);
		node.nodeStats.insertSentBytes(true, -x);
	}

	public int getPriority() {
		return NativeThread.HIGH_PRIORITY;
	}

	@Override
	public String toString() {
		return "SSKInsertSender:" + myKey;
	}
}
