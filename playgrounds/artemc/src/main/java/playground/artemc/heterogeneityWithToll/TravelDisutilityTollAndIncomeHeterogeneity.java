/* *********************************************************************** *
 * project: org.matsim.*
 * TollTravelCostCalculator.java
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

package playground.artemc.heterogeneityWithToll;

import org.apache.log4j.Logger;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.population.Person;
import org.matsim.core.config.Config;
import org.matsim.core.config.groups.PlanCalcScoreConfigGroup;
import org.matsim.core.gbl.MatsimRandom;
import org.matsim.core.router.costcalculators.TravelDisutilityFactory;
import org.matsim.core.router.util.TravelDisutility;
import org.matsim.core.router.util.TravelTime;
import org.matsim.roadpricing.RoadPricingScheme;
import org.matsim.roadpricing.RoadPricingSchemeImpl.Cost;
import org.matsim.vehicles.Vehicle;
import playground.artemc.heterogeneity.IncomeHeterogeneity;

import java.util.HashMap;
import java.util.Random;

/**
 * Calculates the travel disutility for links, including tolls. Currently supports distance, cordon and area tolls.
 *
 * @author mrieser
 */
public class TravelDisutilityTollAndIncomeHeterogeneity implements TravelDisutility {
	// needs to be public. kai, sep'14

	private static final Logger log = Logger.getLogger( TravelDisutilityTollAndIncomeHeterogeneity.class ) ;
	private static int wrnCnt = 0 ;

	private final RoadPricingScheme scheme;
	private final TollRouterBehaviour tollCostHandler;
	private final TravelDisutility normalTravelDisutility;
	private final double marginalUtilityOfMoney;
	private Random random = null ;
	private final double normalization ;
	private final double sigma ;

	private final IncomeHeterogeneity incomeHeterogeneity;
	protected final TravelTime timeCalculator;
	private final double marginalCostOfTime;
	private final double marginalCostOfDistance;

	private HashMap<Id<Person>, Double> incomeFactors;
	private Double factorMean;



	private Person prevPerson;

	private double logNormalRnd;
	private static int utlOfMoneyWrnCnt = 0 ;
	private static int normalisationWrnCnt = 0 ;

	// === start Builder ===
	public static class Builder implements TravelDisutilityFactory{

		private final RoadPricingScheme scheme;
		private final IncomeHeterogeneity incomeHeterogeneity;


		private final double marginalUtilityOfMoney ;
		private TravelDisutilityFactory previousTravelDisutilityFactory;
		private double sigma = 3. ;
		public Builder( TravelDisutilityFactory previousTravelDisutilityFactory, RoadPricingScheme scheme, double marginalUtilityOfMoney, IncomeHeterogeneity incomeHeterogeneity ) {
			this.scheme = scheme ;
			this.incomeHeterogeneity = incomeHeterogeneity;
			this.marginalUtilityOfMoney = marginalUtilityOfMoney ;
			this.previousTravelDisutilityFactory = previousTravelDisutilityFactory ;
		}
		public Builder( TravelDisutilityFactory previousTravelDisutilityFactory, RoadPricingScheme scheme, Config config, IncomeHeterogeneity incomeHeterogeneity) {
			this( previousTravelDisutilityFactory, scheme, config.planCalcScore().getMarginalUtilityOfMoney(),incomeHeterogeneity) ;
		}

		@Override
		public TravelDisutility createTravelDisutility(TravelTime timeCalculator, PlanCalcScoreConfigGroup cnScoringGroup) {
            if (RoadPricingScheme.TOLL_TYPE_DISTANCE.equals(this.scheme.getType())
                    || RoadPricingScheme.TOLL_TYPE_CORDON.equals(this.scheme.getType())
                    || RoadPricingScheme.TOLL_TYPE_LINK.equals(this.scheme.getType()) ) {
            // yy this is historically without area toll but it might be better to do it also with area toll
            // when the randomizing router is used.  I do think, however, that the current specification
            // of the area toll disutility will not work in that way.  kai, sep'14
                return new TravelDisutilityTollAndIncomeHeterogeneity(
                        previousTravelDisutilityFactory.createTravelDisutility(timeCalculator, cnScoringGroup),
                        this.scheme,
                        this.marginalUtilityOfMoney,
                        this.sigma,
                        this.incomeHeterogeneity, timeCalculator, cnScoringGroup
                );
            } else {
                return previousTravelDisutilityFactory.createTravelDisutility(timeCalculator, cnScoringGroup);
            }
		}
		public void setSigma( double val ) {
			this.sigma = val ;
		}
	}
	// === end Builder ===

	private TravelDisutilityTollAndIncomeHeterogeneity(final TravelDisutility normalTravelDisutility, final RoadPricingScheme scheme, double marginalUtilityOfMoney, double sigma, final IncomeHeterogeneity incomeHeterogeneity, final TravelTime timeCalculator, PlanCalcScoreConfigGroup cnScoringGroup)
	// this should remain private; try using the Builder or ask. kai, sep'14
	{
		this.scheme = scheme;
		this.normalTravelDisutility = normalTravelDisutility;
		this.incomeHeterogeneity = incomeHeterogeneity;
		this.timeCalculator = timeCalculator;
		this.incomeFactors = incomeHeterogeneity.getIncomeFactors();

		/*Calculate the mean in order to adjust the utility parameters*/
		Double factorSum=0.0;

		for(Double incomeFactor:this.incomeFactors.values()){
			factorSum = factorSum + incomeFactor;
		}
		this.factorMean = factorSum / (double) incomeFactors.size();

		/* Usually, the travel-utility should be negative (it's a disutility)
		 * but the cost should be positive. Thus negate the utility.
		 */
		this.marginalCostOfTime = (- cnScoringGroup.getTraveling_utils_hr() / 3600.0)  * factorMean + (cnScoringGroup.getPerforming_utils_hr() / 3600.0) * factorMean;
		this.marginalUtilityOfMoney = marginalUtilityOfMoney ;
		this.marginalCostOfDistance = - cnScoringGroup.getMonetaryDistanceCostRateCar() * cnScoringGroup.getMarginalUtilityOfMoney() ;

		if (RoadPricingScheme.TOLL_TYPE_DISTANCE.equals(scheme.getType())) {
			this.tollCostHandler = new DistanceTollCostBehaviour();
		} else if (scheme.getType() == RoadPricingScheme.TOLL_TYPE_AREA) {
			this.tollCostHandler = new AreaTollCostBehaviour();
			Logger.getLogger(this.getClass()).warn("area pricing is more brittle than the other toll schemes; " +
					"make sure you know what you are doing.  kai, apr'13 & sep'14") ;
		} else if (scheme.getType() == RoadPricingScheme.TOLL_TYPE_CORDON) {
			this.tollCostHandler = new CordonTollCostBehaviour();
		} else if (scheme.getType() == RoadPricingScheme.TOLL_TYPE_LINK) {
			this.tollCostHandler = new LinkTollCostBehaviour();
		} else {
			throw new IllegalArgumentException("RoadPricingScheme of type \"" + scheme.getType() + "\" is not supported.");
		}

		if ( utlOfMoneyWrnCnt < 1 && this.marginalUtilityOfMoney != 1. ) {
			utlOfMoneyWrnCnt ++ ;
			Logger.getLogger(this.getClass()).warn("There are no test cases for marginalUtilityOfMoney != 1.  Please write one " +
					"and delete this message.  kai, apr'13 ") ;
		}

		if ( wrnCnt < 1 ) {
			wrnCnt++;
			if (cnScoringGroup.getMonetaryDistanceCostRateCar() > 0.) {
				Logger.getLogger(this.getClass()).warn("Monetary distance cost rate needs to be NEGATIVE to produce the normal" + "behavior; just found positive.  Continuing anyway.  This behavior may be changed in the future.");
			}
		}

		this.sigma = sigma ;
		if ( sigma != 0. ) {
			this.random = MatsimRandom.getLocalInstance() ;
			this.normalization = 1./Math.exp( this.sigma*this.sigma/2 );
			if ( normalisationWrnCnt < 10 ) {
				normalisationWrnCnt++ ;
				log.info(" sigma: " + this.sigma + "; resulting normalization: " + normalization ) ;
			}
		} else {
			this.normalization = 1. ;
		}

	}

	@Override
	public double getLinkTravelDisutility(final Link link, final double time, final Person person, final Vehicle vehicle) 
	{

		double travelTime = this.timeCalculator.getLinkTravelTime(link, time, person, vehicle);

		// randomize if applicable:
		if ( sigma != 0. ) {
			if ( person != prevPerson ) {
				prevPerson = person ;

				logNormalRnd = Math.exp( sigma * random.nextGaussian() ) ;
				logNormalRnd *= normalization ;
				// this should be a log-normal distribution with sigma as the "width" parameter.   Instead of figuring out the "location"
				// parameter mu, I rather just normalize (which should be the same, see next). kai, nov'13

				/* The argument is something like this:<ul> 
				 * <li> exp( mu + sigma * Z) with Z = Gaussian generates lognormal with mu and sigma.
				 * <li> The mean of this is exp( mu + sigma^2/2 ) .  
				 * <li> If we set mu=0, the expectation value is exp( sigma^2/2 ) .
				 * <li> So in order to set the expectation value to one (which is what we want), we need to divide by exp( sigma^2/2 ) .
				 * </ul>
				 * Should be tested. kai, jan'14 */
			}
		} else {
			logNormalRnd = 1. ;
		}
		// end randomize

		double normalTravelDisutilityForLink = this.normalTravelDisutility.getLinkTravelDisutility(link, time, person, vehicle);

		double timeAndDistanceBasedTravelDiutilityForLink =  this.marginalCostOfTime * (1.0/ this.incomeFactors.get(person.getId())) * travelTime + this.marginalCostOfDistance * link.getLength();

		double tollCost = this.tollCostHandler.getTypicalTollCost(link, time );

		return timeAndDistanceBasedTravelDiutilityForLink + tollCost*this.marginalUtilityOfMoney*logNormalRnd ;
		// sign convention: these are all costs (= disutilities), so they are all normally positive.  tollCost is positive, marginalUtilityOfMoney as well.
	}

	@Override
	public double getLinkMinimumTravelDisutility(Link link) {
		return (link.getLength() / link.getFreespeed()) * this.marginalCostOfTime
				       + this.marginalCostOfDistance * link.getLength();
	}

	private interface TollRouterBehaviour {
		public double getTypicalTollCost(Link link, double time);
	}

	/*package*/ class DistanceTollCostBehaviour implements TollRouterBehaviour {
		@Override
		public double getTypicalTollCost(final Link link, final double time) {
			Cost cost_per_m = scheme.getTypicalLinkCostInfo(link.getId(), time );
			if (cost_per_m == null) {
				return 0.0;
			}
			return cost_per_m.amount * link.getLength();
		}
	}

	private static int wrnCnt2 = 0 ;

	/*package*/ class AreaTollCostBehaviour implements TollRouterBehaviour {
		@Override
		public double getTypicalTollCost(final Link link, final double time) {
			Cost cost = scheme.getTypicalLinkCostInfo(link.getId(), time );
			if (cost == null) {
				return 0.0;
			}
			/* just return some really high costs for tolled links, so that still a
			 * route could be found if there is no other possibility. */
			if ( wrnCnt2 < 1 ) {
				wrnCnt2 ++ ;
				Logger.getLogger(this.getClass()).warn("at least here, the area toll does not use the true toll value. " +
						"This may work anyways, but without more explanation it is not obvious to me.  kai, mar'11") ;
			}
			return 1000;
		}
	}

	/*package*/ class CordonTollCostBehaviour implements TollRouterBehaviour {
		@Override
		public double getTypicalTollCost(final Link link, final double time) {
			Cost cost = scheme.getTypicalLinkCostInfo(link.getId(), time );
			if (cost == null) {
				return 0.0;
			}
			return cost.amount;
		}
	}

	/* package */ class LinkTollCostBehaviour implements TollRouterBehaviour {
		@Override
		public double getTypicalTollCost(final Link link, final double time) {
			Cost cost = scheme.getTypicalLinkCostInfo(link.getId(), time );
			if (cost == null) {
				return 0.0;
			}
			return cost.amount;
		}
	}

}