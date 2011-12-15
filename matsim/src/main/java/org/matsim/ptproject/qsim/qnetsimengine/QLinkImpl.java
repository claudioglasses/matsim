/* *********************************************************************** *
 * project: org.matsim.*
 * QueueLink.java
 *                                                                         *
 * *********************************************************************** *
 *                                                                         *
 * copyright       : (C) 2007 by the members listed in the COPYING,        *
 *                   LICENSE and WARRANTY file.                            *
 * email           : info at matsim dot org                                *
 *                                                                         *
 * *********************************************************************** *
 *                                                                         *
 *   This program is free software; you can redistribute it and/or modify  *
 *   it under the terms of the GNU General Public License as published by  *
 *   the Free Software Foundation; either version 2 of the License, or     *
 *   (at your option) any later version.                                   *
 *   See also COPYING, LICENSE and WARRANTY file                           *
 *                                                                         *
 * *********************************************************************** */

package org.matsim.ptproject.qsim.qnetsimengine;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Queue;

import org.apache.log4j.Logger;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.network.Link;
import org.matsim.core.events.AgentStuckEventImpl;
import org.matsim.core.events.AgentWait2LinkEventImpl;
import org.matsim.core.events.LinkEnterEventImpl;
import org.matsim.core.events.LinkLeaveEventImpl;
import org.matsim.core.gbl.Gbl;
import org.matsim.core.gbl.MatsimRandom;
import org.matsim.core.mobsim.framework.DriverAgent;
import org.matsim.core.mobsim.framework.MobsimAgent;
import org.matsim.core.mobsim.framework.MobsimDriverAgent;
import org.matsim.core.network.LinkImpl;
import org.matsim.core.network.NetworkImpl;
import org.matsim.core.utils.misc.Time;
import org.matsim.pt.qsim.TransitDriverAgent;
import org.matsim.pt.transitSchedule.api.TransitStopFacility;
import org.matsim.ptproject.qsim.comparators.QVehicleEarliestLinkExitTimeComparator;
import org.matsim.ptproject.qsim.interfaces.MobsimVehicle;
import org.matsim.signalsystems.mobsim.DefaultSignalizeableItem;
import org.matsim.signalsystems.mobsim.SignalizeableItem;
import org.matsim.signalsystems.model.SignalGroupState;
import org.matsim.vis.snapshotwriters.AgentSnapshotInfo;
import org.matsim.vis.snapshotwriters.VisData;

/**
 * Please read the docu of QBufferItem, QLane, QLinkInternalI (arguably to be renamed
 * into something like AbstractQLink) and QLinkImpl jointly. kai, nov'11
 * 
 * @author dstrippgen
 * @author dgrether
 * @author mrieser
 */
public class QLinkImpl extends AbstractQLink implements SignalizeableItem {

	private static final Comparator<QVehicle> VEHICLE_EXIT_COMPARATOR = new QVehicleEarliestLinkExitTimeComparator();

	// static variables (no problem with memory)
	final private static Logger log = Logger.getLogger(QLinkImpl.class);
	private static int spaceCapWarningCount = 0;
	static boolean HOLES = false ; // can be set from elsewhere in package, but not from outside.  kai, nov'10
	private static int congDensWarnCnt = 0;
	private static int congDensWarnCnt2 = 0;

	// instance variables (problem with memory)
	private final Queue<QItem> holes = new LinkedList<QItem>() ;

	/**
	 * All vehicles from parkingList move to the waitingList as soon as their time
	 * has come. They are then filled into the vehQueue, depending on free space
	 * in the vehQueue
	 */
	/*package*/ final Queue<QVehicle> waitingList = new LinkedList<QVehicle>();

	/**
	 * Reference to the QueueNode which is at the end of each QueueLink instance
	 */
	private final QNode toQueueNode;

	private boolean active = false;

	private final Map<Id, QVehicle> parkedVehicles = new LinkedHashMap<Id, QVehicle>(10);

	private final Map<Id, MobsimAgent> additionalAgentsOnLink = new LinkedHashMap<Id, MobsimAgent>();

	/*package*/ VisData visdata = null ;

	private double length = Double.NaN;

	private double freespeedTravelTime = Double.NaN;

	/** the last timestep the front-most vehicle in the buffer was moved. Used for detecting dead-locks. */
	private double bufferLastMovedTime = Time.UNDEFINED_TIME;

	/**
	 * The list of vehicles that have not yet reached the end of the link
	 * according to the free travel speed of the link
	 */
	private final LinkedList<QVehicle> vehQueue = new LinkedList<QVehicle>();

	/**
	 * Holds all vehicles that are ready to cross the outgoing intersection
	 */
	/*package*/ final Queue<QVehicle> buffer = new LinkedList<QVehicle>();

	private double storageCapacity;

	private double usedStorageCapacity;

	/**
	 * The number of vehicles able to leave the buffer in one time step (usually 1s).
	 */
	private double flowCapacityPerTimeStep; // previously called timeCap

	/*package*/ double inverseSimulatedFlowCapacityCache; // optimization, cache 1.0 / simulatedFlowCapacity

	private int bufferStorageCapacity; // optimization, cache Math.ceil(simulatedFlowCap)

	private double flowCapFractionCache; // optimization, cache simulatedFlowCap - (int)simulatedFlowCap

	/**
	 * The remaining integer part of the flow capacity available in one time step to move vehicles into the
	 * buffer. This value is updated each time step by a call to
	 * {@link #updateBufferCapacity(double)}.
	 */
	private double remainingBufferCap = 0.0;

	/**
	 * Stores the accumulated fractional parts of the flow capacity. See also
	 * flowCapFraction.
	 */
	private double buffercap_accumulate = 1.0;

	/**
	 * null if the link is not signalized
	 */
	private DefaultSignalizeableItem qSignalizedItem = null;
	/**
	 * true, i.e. green, if the link is not signalized
	 */
	private boolean thisTimeStepGreen = true;
	private double congestedDensity_veh_m;
	private int nHolesMax;

	/**
	 * A list containing all transit vehicles that are at a stop but not
	 * blocking other traffic on the lane.
	 */
	private final Queue<QVehicle> transitVehicleStopQueue = new PriorityQueue<QVehicle>(5, VEHICLE_EXIT_COMPARATOR);

	

	/**
	 * Initializes a QueueLink with one QueueLane.
	 * @param link2
	 * @param queueNetwork
	 * @param toNode
	 */
	 QLinkImpl(final Link link2, QNetwork network, final QNode toNode) {
		 super(link2, network) ;
		this.toQueueNode = toNode;
		this.length = this.getLink().getLength();
		this.freespeedTravelTime = this.length / this.getLink().getFreespeed();
		this.calculateCapacities();
		this.visdata = this.new VisDataImpl() ; // instantiating this here so we can cache some things
	}

	/* 
	 * yyyyyy There are two "active" functionalities (see isActive()).  It probably still works, but it does not look like
	 * it is intended this way.  kai, nov'11
	 */
	@Override
	 void activateLink() {
		if (!this.active) {
			netElementActivator.activateLink(this);
			this.active = true;
		}
	}

	/**
	 * Adds a vehicle to the link (i.e. the "queue"), called by
	 * {@link QNode#moveVehicleOverNode(QVehicle, QueueLane, double)}.
	 *
	 * @param veh
	 *          the vehicle
	 */
	@Override
	final void addFromIntersection(final QVehicle veh) {
		double now = network.simEngine.getMobsim().getSimTimer().getTimeOfDay();
		activateLink();
		this.add(veh, now);
		veh.setCurrentLink(this.getLink());
		this.network.simEngine.getMobsim().getEventsManager().processEvent(
				new LinkEnterEventImpl(now, veh.getDriver().getId(),
						this.getLink().getId(), veh.getId()));
		if ( HOLES ) {
			holes.poll();
		}
	}

	/**
	 * Adds a vehicle to the lane.
	 *
	 * @param veh
	 * @param now the current time
	 */
	private void add(final QVehicle veh, final double now) {
		// yyyy only called by "add(veh)", i.e. they can be consolidated. kai, jan'10
		this.vehQueue.add(veh);
		this.usedStorageCapacity += veh.getSizeInEquivalents();
		double departureTime;

		/* It's not the original lane, so there is a fractional rest we add to this link's freeSpeedTravelTime */
		departureTime = now + this.freespeedTravelTime + ( veh.getEarliestLinkExitTime() - Math.floor(veh.getEarliestLinkExitTime()) );
		// yyyy freespeedTravelTime may be Inf, in which case the vehicle never leaves, even if the time-variant link
		// is reset to a non-zero speed.  kai, nov'10

		/* It's a QueueLane that is directly connected to a QueueNode,
		 * so we have to floor the freeLinkTravelTime in order the get the same
		 * results compared to the old mobSim */
		departureTime = Math.floor(departureTime);
		veh.setLinkEnterTime(now);
		veh.setEarliestLinkExitTime(departureTime);
	}

	@Override
	 void clearVehicles() {
		this.parkedVehicles.clear();
		double now = this.network.simEngine.getMobsim().getSimTimer().getTimeOfDay();

		for (QVehicle veh : this.waitingList) {
			this.network.simEngine.getMobsim().getEventsManager().processEvent(
					new AgentStuckEventImpl(now, veh.getDriver().getId(), veh.getCurrentLink().getId(), veh.getDriver().getMode()));
		}
		this.network.simEngine.getMobsim().getAgentCounter().decLiving(this.waitingList.size());
		this.network.simEngine.getMobsim().getAgentCounter().incLost(this.waitingList.size());
		this.waitingList.clear();

		for (QVehicle veh : this.vehQueue) {
			this.network.simEngine.getMobsim().getEventsManager().processEvent(
					new AgentStuckEventImpl(now, veh.getDriver().getId(), veh.getCurrentLink().getId(), veh.getDriver().getMode()));
		}
		this.network.simEngine.getMobsim().getAgentCounter().decLiving(this.vehQueue.size());
		this.network.simEngine.getMobsim().getAgentCounter().incLost(this.vehQueue.size());
		this.vehQueue.clear();

		for (QVehicle veh : this.buffer) {
			this.network.simEngine.getMobsim().getEventsManager().processEvent(
					new AgentStuckEventImpl(now, veh.getDriver().getId(), veh.getCurrentLink().getId(), veh.getDriver().getMode()));
		}
		this.network.simEngine.getMobsim().getAgentCounter().decLiving(this.buffer.size());
		this.network.simEngine.getMobsim().getAgentCounter().incLost(this.buffer.size());
		this.buffer.clear();
	}

	@Override
	public void addParkedVehicle(MobsimVehicle vehicle) {
		QVehicle qveh = (QVehicle) vehicle ; // cast ok: when it gets here, it needs to be a qvehicle to work.
		this.parkedVehicles.put(qveh.getId(), qveh);
		qveh.setCurrentLink(this.link);
	}

	@Override
	final QVehicle removeParkedVehicle(Id vehicleId) {
		return this.parkedVehicles.remove(vehicleId);
	}

	public void addDepartingVehicle(MobsimVehicle mvehicle) {
		QVehicle vehicle = (QVehicle) mvehicle ;
		this.waitingList.add(vehicle);
		vehicle.setCurrentLink(this.getLink());
		this.activateLink();
	}

	@Override
	 boolean moveLink(double now) {
		// yyyy needs to be final
		boolean ret = false;
		ret = this.moveLane(now);
		this.active = ret;
		return ret;
	}

	/** called from framework, do everything related to link movement here
	 *
	 * @param now current time step
	 * @return
	 */
	 @Override
	boolean moveLane(final double now) {
		updateBufferCapacity();

		// move vehicles from lane to buffer.  Includes possible vehicle arrival.  Which, I think, would only be triggered
		// if this is the original lane.
		moveLaneToBuffer(now);
		// move vehicles from waitingQueue into buffer if possible
		moveWaitToBuffer(now);
		return this.isActive();
	}

	private void updateBufferCapacity() {
		this.remainingBufferCap = this.flowCapacityPerTimeStep;
		if (this.thisTimeStepGreen && this.buffercap_accumulate < 1.0) {
			this.buffercap_accumulate += this.flowCapFractionCache;
		}
	}

	/**
	 * Move vehicles from link to buffer, according to buffer capacity and
	 * departure time of vehicle. Also removes vehicles from lane if the vehicle
	 * arrived at its destination.
	 *
	 * @param now
	 *          The current time.
	 */
	 private void moveLaneToBuffer(final double now) {
		QVehicle veh;

		this.moveTransitToQueue(now);

		// handle regular traffic
		while ((veh = ((QVehicle) this.vehQueue.peek())) != null) {
			if (veh.getEarliestLinkExitTime() > now){
				return;
			}
			MobsimDriverAgent driver = veh.getDriver();

			boolean handled = this.handleTransitStop(now, veh, driver);

			if (!handled) {
				// Check if veh has reached destination:
				if ((this.getLink().getId().equals(driver.getDestinationLinkId())) && (driver.chooseNextLinkId() == null)) {
					this.addParkedVehicle(veh);
					network.simEngine.letAgentArrive(veh);
					this.makeVehicleAvailableToNextDriver(veh, now);
					// remove _after_ processing the arrival to keep link active
					this.vehQueue.poll();
					this.usedStorageCapacity -= veh.getSizeInEquivalents();
					if ( HOLES ) {
						Hole hole = new Hole() ;
						hole.setEarliestLinkExitTime( now + this.link.getLength()*3600./15./1000. ) ;
						holes.add( hole ) ;
					}
					continue;
				}

				/* is there still room left in the buffer, or is it overcrowded from the
				 * last time steps? */
				if (!hasBufferSpace()) {
					return;
				}

				if (driver instanceof TransitDriverAgent) {
					TransitDriverAgent trDriver = (TransitDriverAgent) driver;
					Id nextLinkId = trDriver.chooseNextLinkId();
					if (nextLinkId == null || nextLinkId.equals(trDriver.getCurrentLinkId())) {
						// special case: transit drivers can specify the next link being the current link
						// this can happen when a transit-lines route leads over exactly one link
						// normally, vehicles would not even drive on that link, but transit vehicles must
						// "drive" on that link in order to handle the stops on that link
						// so allow them to return some non-null link id in chooseNextLink() in order to be
						// placed on the link, and here we'll remove them again if needed...
						// ugly hack, but I didn't find a nicer solution sadly... mrieser, 5mar2011
						network.simEngine.letAgentArrive(veh);
						this.addParkedVehicle(veh);
						this.makeVehicleAvailableToNextDriver(veh, now);
						// remove _after_ processing the arrival to keep link active
						this.vehQueue.poll();
						this.usedStorageCapacity -= veh.getSizeInEquivalents();
						if ( HOLES ) {
							Hole hole = new Hole() ;
							hole.setEarliestLinkExitTime( now + this.link.getLength()*3600./15./1000. ) ;
							holes.add( hole ) ;
						}
						continue;
					}
				}
				addToBuffer(veh, now);
				this.vehQueue.poll();
				this.usedStorageCapacity -= veh.getSizeInEquivalents();
				if ( HOLES ) {
					Hole hole = new Hole() ;
					double offset = this.link.getLength()*3600./15./1000. ;
					hole.setEarliestLinkExitTime( now + 0.9*offset + 0.2*MatsimRandom.getRandom().nextDouble()*offset ) ;
					holes.add( hole ) ;
				}
			}
		} // end while
	}

	private void makeVehicleAvailableToNextDriver(QVehicle veh, double now) {
		Iterator<MobsimAgent> i = additionalAgentsOnLink.values().iterator();
		while (i.hasNext()) {
			MobsimAgent agent = i.next();
			//			Leg currentLeg = agent.getCurrentLeg();
			String mode = agent.getMode() ;
			//			if (currentLeg != null && currentLeg.getMode().equals(TransportMode.car)) {
			if (mode != null && mode.equals(TransportMode.car)) {
				// We are not in an activity, but in a car leg, and we are an "additional agent".
				// This currently means that we are waiting for our car to become available.
				// So our current route must be a NetworkRoute.
				//				NetworkRoute route = (NetworkRoute) currentLeg.getRoute();
				//				Id requiredVehicleId = route.getVehicleId();

				// new: so we are a driver:
				DriverAgent drAgent = (DriverAgent) agent ;
				Id requiredVehicleId = drAgent.getPlannedVehicleId() ;
				if (requiredVehicleId == null) {
					requiredVehicleId = agent.getId();
				}
				if (veh.getId().equals(requiredVehicleId)) {
					i.remove();
					this.letAgentDepartWithVehicle((MobsimDriverAgent) agent, veh, now);
					return;
				}
			}
		}
	}

	/**
	 * Move as many waiting cars to the link as it is possible
	 *
	 * @param now
	 *          the current time
	 */
	private void moveWaitToBuffer(final double now) {
		while (hasBufferSpace()) {
			QVehicle veh = this.waitingList.poll();
			if (veh == null) {
				return;
			}

			this.network.simEngine.getMobsim().getEventsManager().processEvent(
					new AgentWait2LinkEventImpl(now, veh.getDriver().getId(), this.getLink().getId(), veh.getId()));
			boolean handled = this.addTransitToBuffer(now, veh);

			if (!handled) {


				if (veh.getDriver() instanceof TransitDriverAgent) {
					TransitDriverAgent trDriver = (TransitDriverAgent) veh.getDriver();
					Id nextLinkId = trDriver.chooseNextLinkId();
					if (nextLinkId == null || nextLinkId.equals(trDriver.getCurrentLinkId())) {
						// special case: transit drivers can specify the next link being the current link
						// this can happen when a transit-lines route leads over exactly one link
						// normally, vehicles would not even drive on that link, but transit vehicles must
						// "drive" on that link in order to handle the stops on that link
						// so allow them to return some non-null link id in chooseNextLink() in order to be
						// placed on the link, and here we'll remove them again if needed...
						// ugly hack, but I didn't find a nicer solution sadly... mrieser, 5mar2011
						trDriver.endLegAndAssumeControl(now);
						this.addParkedVehicle(veh);
						this.makeVehicleAvailableToNextDriver(veh, now);
						// remove _after_ processing the arrival to keep link active
						this.vehQueue.poll();
						this.usedStorageCapacity -= veh.getSizeInEquivalents();
						if ( HOLES ) {
							Hole hole = new Hole() ;
							hole.setEarliestLinkExitTime( now + this.link.getLength()*3600./15./1000. ) ;
							holes.add( hole ) ;
						}
						continue;
					}
				}



				addToBuffer(veh, now);
			}
		}
	}

	/**
	 * This method
	 * moves transit vehicles from the stop queue directly to the front of the
	 * "queue" of the QLink. An advantage is that this will observe flow
	 * capacity restrictions. 
	 */
	private void moveTransitToQueue(final double now) {
		QVehicle veh;
		// handle transit traffic in stop queue
		List<QVehicle> departingTransitVehicles = null;
		while ((veh = transitVehicleStopQueue.peek()) != null) {
			// there is a transit vehicle.
			if (veh.getEarliestLinkExitTime() > now) {
				break;
			}
			if (departingTransitVehicles == null) {
				departingTransitVehicles = new LinkedList<QVehicle>();
			}
			departingTransitVehicles.add(transitVehicleStopQueue.poll());
		}
		if (departingTransitVehicles != null) {
			// add all departing transit vehicles at the front of the vehQueue
			ListIterator<QVehicle> iter = departingTransitVehicles.listIterator(departingTransitVehicles.size());
			while (iter.hasPrevious()) {
				this.vehQueue.addFirst(iter.previous());
			}
		}
	}

	private boolean addTransitToBuffer(final double now, final QVehicle veh) {
		if (veh.getDriver() instanceof TransitDriverAgent) {
			TransitDriverAgent driver = (TransitDriverAgent) veh.getDriver();
			while (true) {
				TransitStopFacility stop = driver.getNextTransitStop();
				if ((stop != null) && (stop.getLinkId().equals(getLink().getId()))) {
					double delay = driver.handleTransitStop(stop, now);
					if (delay > 0.0) {
						veh.setEarliestLinkExitTime(now + delay);
						// add it to the stop queue, can do this as the waitQueue is also non-blocking anyway
						transitVehicleStopQueue.add(veh);
						return true;
					}
				} else {
					return false;
				}
			}
		}
		return false;
	}

	private boolean handleTransitStop(final double now, final QVehicle veh,
			final MobsimDriverAgent driver) {
		boolean handled = false;
		// handle transit driver if necessary
		if (driver instanceof TransitDriverAgent) {
			TransitDriverAgent transitDriver = (TransitDriverAgent) veh.getDriver();
			TransitStopFacility stop = transitDriver.getNextTransitStop();
			if ((stop != null) && (stop.getLinkId().equals(getLink().getId()))) {
				double delay = transitDriver.handleTransitStop(stop, now);
				if (delay > 0.0) {

					veh.setEarliestLinkExitTime(now + delay);
					// (if the vehicle is not removed from the queue in the following lines, then this will effectively block the lane

					if (!stop.getIsBlockingLane()) {
						this.vehQueue.poll(); // remove the bus from the queue
						transitVehicleStopQueue.add(veh); // and add it to the stop queue
					}
				}
				/* start over: either this veh is still first in line,
				 * but has another stop on this link, or on another link, then it is moved on
				 */
				handled = true;
			}
		}
		return handled;
	}

	@Override
	final boolean bufferIsEmpty() {
		return this.buffer.isEmpty();
	}

	@Override
	final boolean hasSpace() {
		double now = network.simEngine.getMobsim().getSimTimer().getTimeOfDay() ;

		boolean storageOk = this.usedStorageCapacity < getStorageCapacity();
		if ( !HOLES ) {
			return storageOk ;
		}
		// continue only if HOLES
		if ( !storageOk ) {
			return false ;
		}
		// at this point, storage is ok, so start checking holes:
		QItem hole = holes.peek();
		if ( hole==null ) { // no holes available at all; in theory, this should not happen since covered by !storageOk
			//			log.warn( " !hasSpace since no holes available ") ;
			return false ;
		}
		if ( hole.getEarliestLinkExitTime() > now ) {
			//			log.warn( " !hasSpace since all hole arrival times lie in future ") ;
			return false ;
		}
		return true ;
	}


	@Override
	public void recalcTimeVariantAttributes(double now) {
		this.freespeedTravelTime = this.length / this.getLink().getFreespeed(now);
		calculateFlowCapacity(now);
		calculateStorageCapacity(now);
	}

	@Override
	void calculateCapacities() {
		calculateFlowCapacity(Time.UNDEFINED_TIME);
		calculateStorageCapacity(Time.UNDEFINED_TIME);
		this.buffercap_accumulate = (this.flowCapFractionCache == 0.0 ? 0.0 : 1.0);
	}

	private void calculateFlowCapacity(final double time) {
		this.flowCapacityPerTimeStep = ((LinkImpl)this.getLink()).getFlowCapacity(time);
		// we need the flow capacity per sim-tick and multiplied with flowCapFactor
		this.flowCapacityPerTimeStep = this.flowCapacityPerTimeStep
		* network.simEngine.getMobsim().getSimTimer().getSimTimestepSize()
		* network.simEngine.getMobsim().getScenario().getConfig().getQSimConfigGroup().getFlowCapFactor();
		this.inverseSimulatedFlowCapacityCache = 1.0 / this.flowCapacityPerTimeStep;
		this.flowCapFractionCache = this.flowCapacityPerTimeStep - (int) this.flowCapacityPerTimeStep;
	}

	private void calculateStorageCapacity(final double time) {
		double storageCapFactor = network.simEngine.getMobsim().getScenario().getConfig().getQSimConfigGroup().getStorageCapFactor();
		this.bufferStorageCapacity = (int) Math.ceil(this.flowCapacityPerTimeStep);

		double numberOfLanes = this.getLink().getNumberOfLanes(time);
		// first guess at storageCapacity:
		this.storageCapacity = (this.length * numberOfLanes)
		/ ((NetworkImpl) network.simEngine.getMobsim().getScenario().getNetwork()).getEffectiveCellSize() * storageCapFactor;

		// storage capacity needs to be at least enough to handle the cap_per_time_step:
		this.storageCapacity = Math.max(this.storageCapacity, this.bufferStorageCapacity);

		/*
		 * If speed on link is relatively slow, then we need MORE cells than the
		 * above spaceCap to handle the flowCap. Example: Assume freeSpeedTravelTime
		 * (aka freeTravelDuration) is 2 seconds. Than I need the spaceCap = TWO times
		 * the flowCap to handle the flowCap.
		 */
		double tempStorageCapacity = this.freespeedTravelTime * this.flowCapacityPerTimeStep;
		// yy note: freespeedTravelTime may be Inf.  In this case, storageCapacity will also be set to Inf.  This can still be
		// interpreted, but it means that the link will act as an infinite sink.  kai, nov'10

		if (this.storageCapacity < tempStorageCapacity) {
			if (spaceCapWarningCount <= 10) {
				log.warn("Link " + this.getLink().getId() + " too small: enlarge storage capacity from: " + this.storageCapacity
						+ " Vehicles to: " + tempStorageCapacity + " Vehicles.  This is not fatal, but modifies the traffic flow dynamics.");
				if (spaceCapWarningCount == 10) {
					log.warn("Additional warnings of this type are suppressed.");
				}
				spaceCapWarningCount++;
			}
			this.storageCapacity = tempStorageCapacity;
		}

		if ( HOLES ) {
			// yyyy number of initial holes (= max number of vehicles on link given bottleneck spillback) is, in fact, dicated
			// by the bottleneck flow capacity, together with the fundamental diagram. :-(  kai, ???'10
			//
			// Alternative would be to have link entry capacity constraint.  This, however, does not work so well with the
			// current "parallel" logic, where capacity constraints are modeled only on the link.  kai, nov'10
			double bnFlowCap_s = ((LinkImpl)this.link).getFlowCapacity() ;

			// ( c * n_cells - cap * L ) / (L * c) = (n_cells/L - cap/c) ;
			congestedDensity_veh_m = this.storageCapacity/this.link.getLength() - (bnFlowCap_s*3600.)/(15.*1000) ;

			if ( congestedDensity_veh_m > 10. ) {
				if ( congDensWarnCnt2 < 1 ) {
					congDensWarnCnt2++ ;
					log.warn("congestedDensity_veh_m very large: " + congestedDensity_veh_m
							+ "; does this make sense?  Setting to 10 veh/m (which is still a lot but who knows). "
							+ "Definitely can't have it at Inf." ) ;
				}
			}

			// congestedDensity is in veh/m.  If this is less than something reasonable (e.g. 1veh/50m) or even negative,
			// then this means that the link has not enough storageCapacity (essentially not enough lanes) to transport the given
			// flow capacity.  Will increase the storageCapacity accordingly:
			if ( congestedDensity_veh_m < 1./50 ) {
				if ( congDensWarnCnt < 1 ) {
					congDensWarnCnt++ ;
					log.warn( "link not ``wide'' enough to process flow capacity with holes.  increasing storage capacity ...") ;
					log.warn( Gbl.ONLYONCE ) ;
				}
				this.storageCapacity = (1./50 + bnFlowCap_s*3600./(15.*1000)) * this.link.getLength() ;
				congestedDensity_veh_m = this.storageCapacity/this.link.getLength() - (bnFlowCap_s*3600.)/(15.*1000) ;
			}

			nHolesMax = (int) Math.ceil( congestedDensity_veh_m * this.link.getLength() ) ;
			log.warn(
					" nHoles: " + nHolesMax
					+ " storCap: " + this.storageCapacity
					+ " len: " + this.link.getLength()
					+ " bnFlowCap: " + bnFlowCap_s
					+ " congDens: " + congestedDensity_veh_m
			) ;
			for ( int ii=0 ; ii<nHolesMax ; ii++ ) {
				Hole hole = new Hole() ;
				hole.setEarliestLinkExitTime( 0. ) ;
				holes.add( hole ) ;
			}
			//			System.exit(-1);
		}
	}

	QVehicle getVehicle(Id vehicleId) {
		QVehicle ret = this.parkedVehicles.get(vehicleId);
		if (ret != null) {
			return ret;
		}
		for (QVehicle veh : this.vehQueue) {
			if (veh.getId().equals(vehicleId))
				return veh;
		}
		for (QVehicle veh : this.buffer) {
			if (veh.getId().equals(vehicleId))
				return veh;
		}
		for (QVehicle veh : this.waitingList) {
			if (veh.getId().equals(vehicleId))
				return veh;
		}
		return null;
	}

	@Override
	public Collection<MobsimVehicle> getAllVehicles() {
		Collection<MobsimVehicle> vehicles = this.getAllNonParkedVehicles();
		vehicles.addAll(this.parkedVehicles.values());
		return vehicles;
	}

	@Override
	public Collection<MobsimVehicle> getAllNonParkedVehicles(){
		Collection<MobsimVehicle> vehicles = new ArrayList<MobsimVehicle>();
		vehicles.addAll(this.transitVehicleStopQueue);
		vehicles.addAll(this.waitingList);
		vehicles.addAll(this.vehQueue);
		vehicles.addAll(this.buffer);
		return vehicles;
	}

	/**
	 * @return Returns the maximum number of vehicles that can be placed on the
	 *         link at a time.
	 */
	@Override
	/*package*/ double getStorageCapacity() {
		return this.storageCapacity;
	}

	/**
	 * @return the total space capacity available on that link (includes the space on lanes if available)
	 */
	double getSpaceCap() {
		return this.storageCapacity;
	}

	@Override
	int vehOnLinkCount() {
		// called by one test case
		return this.vehQueue.size();
	}


	@Override
	public Link getLink() {
		return this.link;
	}

	@Override
	public QNode getToNode() {
		return this.toQueueNode;
	}

	/**
	 * This method returns the normalized capacity of the link, i.e. the capacity
	 * of vehicles per second. It is considering the capacity reduction factors
	 * set in the config and the simulation's tick time.
	 *
	 * @return the flow capacity of this link per second, scaled by the config
	 *         values and in relation to the SimulationTimer's simticktime.
	 */
	@Override
	double getSimulatedFlowCapacity() {
		return this.flowCapacityPerTimeStep;
	}

	@Override
	public VisData getVisData() {
		return this.visdata;
	}

	private boolean isActive() {
		/*
		 * Leave Lane active as long as there are vehicles on the link (ignore
		 * buffer because the buffer gets emptied by nodes and not links) and leave
		 * link active until buffercap has accumulated (so a newly arriving vehicle
		 * is not delayed).
		 */
		boolean active = (this.buffercap_accumulate < 1.0) || (!this.vehQueue.isEmpty()) || (!this.waitingList.isEmpty() || (!this.transitVehicleStopQueue.isEmpty()));
		return active;
	}

	/**
	 * @return <code>true</code> if there are less vehicles in buffer than the flowCapacity's ceil
	 */
	private boolean hasBufferSpace() {
		return ((this.buffer.size() < this.bufferStorageCapacity) && ((this.remainingBufferCap >= 1.0)
				|| (this.buffercap_accumulate >= 1.0)));
	}

	private void addToBuffer(final QVehicle veh, final double now) {
		if (this.remainingBufferCap >= 1.0) {
			this.remainingBufferCap--;
		}
		else if (this.buffercap_accumulate >= 1.0) {
			this.buffercap_accumulate--;
		}
		else {
			throw new IllegalStateException("Buffer of link " + this.getLink().getId() + " has no space left!");
		}
		this.buffer.add(veh);
		if (this.buffer.size() == 1) {
			this.bufferLastMovedTime = now;
		}
		this.getToNode().activateNode();
	}

	@Override
	QVehicle popFirstFromBuffer() {
		double now = this.network.simEngine.getMobsim().getSimTimer().getTimeOfDay();
		QVehicle veh = this.buffer.poll();
		this.bufferLastMovedTime = now; // just in case there is another vehicle in the buffer that is now the new front-most
		this.network.simEngine.getMobsim().getEventsManager().processEvent(new LinkLeaveEventImpl(now, veh.getDriver().getId(), this.getLink().getId(), veh.getId()));
		return veh;
	}

	@Override
	QVehicle getFirstFromBuffer() {
		return this.buffer.peek();
	}

	@Override
	public void registerAdditionalAgentOnLink(MobsimAgent planAgent) {
		this.additionalAgentsOnLink.put(planAgent.getId(), planAgent);
	}

	@Override
	public MobsimAgent unregisterAdditionalAgentOnLink(Id mobsimAgentId) {
		return this.additionalAgentsOnLink.remove(mobsimAgentId);
	}

	@Override
	double getBufferLastMovedTime() {
		return this.bufferLastMovedTime;
	}

	@Override
	public boolean hasGreenForToLink(Id toLinkId){
		if (this.qSignalizedItem != null){
			return this.qSignalizedItem.isLinkGreenForToLink(toLinkId);
		}
		return true; //the lane is not signalized and thus always green
	}

	@Override
	public void setSignalStateAllTurningMoves(SignalGroupState state) {
		this.qSignalizedItem.setSignalStateAllTurningMoves(state);
		this.thisTimeStepGreen  = this.qSignalizedItem.isLinkGreen();
	}

	@Override
	public void setSignalStateForTurningMove(SignalGroupState state, Id toLinkId) {
		if (!this.getToNode().getNode().getOutLinks().containsKey(toLinkId)){
			throw new IllegalArgumentException("ToLink " + toLinkId + " is not reachable from QLink Id " + this.getLink().getId());
		}
		this.qSignalizedItem.setSignalStateForTurningMove(state, toLinkId);
		this.thisTimeStepGreen = this.qSignalizedItem.isLinkGreen();
	}

	@Override
	public void setSignalized(boolean isSignalized) {
		this.qSignalizedItem  = new DefaultSignalizeableItem(this.getLink().getToNode().getOutLinks().keySet());
	}


	/**
	 * Inner class to encapsulate visualization methods
	 *
	 * @author dgrether
	 */
	class VisDataImpl implements VisData {

		private VisDataImpl() {
		}

		@Override
		public Collection<AgentSnapshotInfo> getVehiclePositions( final Collection<AgentSnapshotInfo> positions) {
			AgentSnapshotInfoBuilder snapshotInfoBuilder = QLinkImpl.this.network.simEngine.getAgentSnapshotInfoBuilder();

			snapshotInfoBuilder.addVehiclePositions(QLinkImpl.this, positions, QLinkImpl.this.buffer, QLinkImpl.this.vehQueue,
					QLinkImpl.this.holes, QLinkImpl.this.getLink().getLength(), 0.0, null);

			int cnt2 = 0 ; // a counter according to which non-moving items can be "spread out" in the visualization

			// treat vehicles from transit stops
			snapshotInfoBuilder.positionVehiclesFromTransitStop(positions, link, transitVehicleStopQueue, cnt2 );

			// treat vehicles from waiting list:
			snapshotInfoBuilder.positionVehiclesFromWaitingList(positions, QLinkImpl.this.link, cnt2,
					QLinkImpl.this.waitingList);

			snapshotInfoBuilder.positionAgentsInActivities(positions, QLinkImpl.this.link,
					QLinkImpl.this.additionalAgentsOnLink.values(), cnt2);

			// return:
			return positions;
		}
	}

	static class Hole extends QItem {
		private double earliestLinkEndTime ;

		@Override
		public double getEarliestLinkExitTime() {
			return earliestLinkEndTime;
		}

		@Override
		public void setEarliestLinkExitTime(double earliestLinkEndTime) {
			this.earliestLinkEndTime = earliestLinkEndTime;
		}
	}

	/**
	 * this method is here so that aspects of QLane and QLink can be addressed via the same syntax.
	 */
	@Override
	AbstractQLink getQLink() {
		return this;
	}

	@Override
	double getInverseSimulatedFlowCapacity() {
		return this.inverseSimulatedFlowCapacityCache ;
	}

	@Override
	int getBufferStorage() {
		return this.bufferStorageCapacity ;
	}

	@Override
	double getLength() {
		return this.length ;
	}

}
