/* *********************************************************************** *
 * project: org.matsim.*
 * CadytsIntegrationTest.java
 *                                                                         *
 * *********************************************************************** *
 *                                                                         *
 * copyright       : (C) 2011 by the members listed in the COPYING,        *
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

package org.matsim.contrib.cadyts.car;

import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.contrib.cadyts.utils.CalibrationStatReader;
import org.matsim.core.api.experimental.events.EventsManager;
import org.matsim.core.basic.v01.IdImpl;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.config.groups.ControlerConfigGroup.MobsimType;
import org.matsim.core.config.groups.PlanCalcScoreConfigGroup.ActivityParams;
import org.matsim.core.config.groups.QSimConfigGroup;
import org.matsim.core.config.groups.StrategyConfigGroup.StrategySettings;
import org.matsim.core.controler.Controler;
import org.matsim.core.mobsim.framework.Mobsim;
import org.matsim.core.mobsim.framework.MobsimFactory;
import org.matsim.core.replanning.PlanStrategy;
import org.matsim.core.replanning.PlanStrategyFactory;
import org.matsim.core.replanning.PlanStrategyImpl;
import org.matsim.core.scoring.ScoringFunction;
import org.matsim.core.scoring.ScoringFunctionAccumulator;
import org.matsim.core.scoring.ScoringFunctionFactory;
import org.matsim.core.scoring.functions.CharyparNagelActivityScoring;
import org.matsim.core.scoring.functions.CharyparNagelAgentStuckScoring;
import org.matsim.core.scoring.functions.CharyparNagelLegScoring;
import org.matsim.core.scoring.functions.CharyparNagelScoringParameters;
import org.matsim.counts.Count;
import org.matsim.counts.Counts;
import org.matsim.counts.MatsimCountsReader;
import org.matsim.testcases.MatsimTestUtils;

import utilities.io.tabularfileparser.TabularFileParser;
import utilities.misc.DynamicData;
import cadyts.measurements.SingleLinkMeasurement;

/**
 * This is a modified copy of CadytsCarIntegrationTest (which is used for the cadyts pt integration)
 * in order to realize an according test for the cadyts car integration.
 * At this stage all original pt code is still included here, but outcommeted, to make the adaptations
 * from pt to car well traceable in case of any errors.
 */
public class CadytsCarIntegrationTest {
	
	@Rule
	public MatsimTestUtils utils = new MatsimTestUtils();

	@Test
	public final void testInitialization() {
		String inputDir = this.utils.getClassInputDirectory();
		String outputDir = this.utils.getOutputDirectory();
		
		Config config = createTestConfig(inputDir, outputDir);
		config.controler().setLastIteration(0);
		
		StrategySettings strategySettingss = new StrategySettings(new IdImpl(1));
		strategySettingss.setModuleName("ccc") ;
		strategySettingss.setProbability(1.0) ;
		config.strategy().addStrategySettings(strategySettingss);
		
		// Scenario scenario = ScenarioUtils.loadScenario(config);
		// final Controler controler = new Controler(scenario);
		final Controler controler = new Controler(config);
		controler.setOverwriteFiles(true);
		
		final CadytsContext context = new CadytsContext(config);
		// in pt these parameters are already set in method "createTestConfig", for car they can, however, only be
		// set here, because the CadytsCarConfigGroup is created with the CadytsCarContext, which is created just above
		config.getModule("cadytsCar").addParam("startTime", "04:00:00");
		config.getModule("cadytsCar").addParam("endTime", "20:00:00");
		config.getModule("cadytsCar").addParam("regressionInertia", "0.95");
		config.getModule("cadytsCar").addParam("useBruteForce", "true");
		config.getModule("cadytsCar").addParam("minFlowStddevVehH", "8");
		config.getModule("cadytsCar").addParam("preparatoryIterations", "1");
		config.getModule("cadytsCar").addParam("timeBinSize", "3600");
		//---				
		controler.addControlerListener(context) ;
				
		controler.addPlanStrategyFactory("ccc", new PlanStrategyFactory() {
			@Override
			public PlanStrategy createPlanStrategy(Scenario scenario2, EventsManager events2) {
				// return new PlanStrategyImpl(new CadytsPtPlanChanger(scenario2, context));
				return new PlanStrategyImpl(new CadytsPlanChanger(context));
			}} ) ;
		
		controler.setCreateGraphs(false);
		controler.getConfig().controler().setWriteEventsInterval(0);
		controler.setDumpDataAtEnd(true);
		controler.setMobsimFactory(new DummyMobsimFactory());
		controler.run();
		
		//test calibration settings
		Assert.assertEquals(true, context.getCalibrator().getBruteForce());
		Assert.assertEquals(false, context.getCalibrator().getCenterRegression());
		Assert.assertEquals(Integer.MAX_VALUE, context.getCalibrator().getFreezeIteration());
		Assert.assertEquals(8.0, context.getCalibrator().getMinStddev(SingleLinkMeasurement.TYPE.FLOW_VEH_H), MatsimTestUtils.EPSILON);
		Assert.assertEquals(1, context.getCalibrator().getPreparatoryIterations());
		Assert.assertEquals(0.95, context.getCalibrator().getRegressionInertia(), MatsimTestUtils.EPSILON);
		Assert.assertEquals(1.0, context.getCalibrator().getVarianceScale(), MatsimTestUtils.EPSILON);
		Assert.assertEquals(3600.0, context.getCalibrator().getTimeBinSize_s(), MatsimTestUtils.EPSILON);
	}
	
	
	//--------------------------------------------------------------
	@Test
	public final void testCalibrationAsScoring() throws IOException {
		final double beta=30. ;
		final int lastIteration = 20 ;
		
		String inputDir = this.utils.getClassInputDirectory();
		String outputDir = this.utils.getOutputDirectory();

		final Config config = createTestConfig(inputDir, outputDir);
		
		config.controler().setLastIteration(lastIteration);
		
		config.planCalcScore().setBrainExpBeta(beta);
		
		StrategySettings strategySettings = new StrategySettings(new IdImpl("1"));
		// strategySettings.setModuleName("ChangeExpBeta");
		strategySettings.setModuleName("ccc");
		strategySettings.setProbability(1.0);
		config.strategy().addStrategySettings(strategySettings);

		// ===

		final Controler controler = new Controler(config);
//		controler.setCreateGraphs(false);
//		controler.setDumpDataAtEnd(true);
		controler.setOverwriteFiles(true);
		
		final CadytsContext cContext = new CadytsContext(config);
		controler.addControlerListener(cContext);
		
		// original from pt... to be deleted later
//		controler.addPlanStrategyFactory("ccc", new PlanStrategyFactory() {
//			@Override
//			public PlanStrategy createPlanStrategy(Scenario scenario2, EventsManager events2) {
//				// final CadytsPtPlanChanger planSelector = new CadytsPtPlanChanger(scenario2, cContext);
//				final CadytsPlanChanger planSelector = new CadytsPlanChanger(cContext);
//				planSelector.setCadytsWeight(0.) ;
//				// weight 0 is correct: this is only in order to use getCalibrator().addToDemand.
//				// would certainly be cleaner (and less confusing) to write a separate method for this.  (But how?)
//				// kai, may'13
//				return new PlanStrategyImpl(planSelector);
//			}
//		} ) ;
		
		// for car
		// new PlanStrategy which does the same as above, but cleaner (getting rid of the weight which needs to be set to "0")
		controler.addPlanStrategyFactory("ccc", new PlanStrategyFactory() {
			@Override
			public PlanStrategy createPlanStrategy(Scenario scenario, EventsManager eventsManager) {
				return new PlanStrategyImpl(new CadytsExtendedExpBetaPlanChanger(
						scenario.getConfig().planCalcScore().getBrainExpBeta(), cContext));
			}
		} ) ;
		
		
		// original from pt... to be deleted later
//		controler.setScoringFunctionFactory(new ScoringFunctionFactory() {
//			@Override
//			public ScoringFunction createNewScoringFunction(Plan plan) {
//				CharyparNagelScoringParameters params = new CharyparNagelScoringParameters(config.planCalcScore()) ;
//
//				ScoringFunctionAccumulator scoringFunctionAccumulator = new ScoringFunctionAccumulator();
//				scoringFunctionAccumulator.addScoringFunction(new CharyparNagelLegScoring(params, controler.getScenario().getNetwork()));
//				scoringFunctionAccumulator.addScoringFunction(new CharyparNagelActivityScoring(params)) ;
//				scoringFunctionAccumulator.addScoringFunction(new CharyparNagelAgentStuckScoring(params));
//
//				final CadytsPtScoring scoringFunction = new CadytsPtScoring(plan,config, cContext);
//				scoringFunction.setWeightOfCadytsCorrection(beta*30.) ;
//				scoringFunctionAccumulator.addScoringFunction(scoringFunction );
//
//				return scoringFunctionAccumulator;
//			}
//		}) ;
		
		// for car
		controler.setScoringFunctionFactory(new ScoringFunctionFactory() {
			@Override
			public ScoringFunction createNewScoringFunction(Plan plan) {
				
				final CharyparNagelScoringParameters params = new CharyparNagelScoringParameters(config.planCalcScore());
				
				ScoringFunctionAccumulator scoringFunctionAccumulator = new ScoringFunctionAccumulator();
				scoringFunctionAccumulator.addScoringFunction(new CharyparNagelLegScoring(params, controler.getScenario().getNetwork()));
				scoringFunctionAccumulator.addScoringFunction(new CharyparNagelActivityScoring(params)) ;
				scoringFunctionAccumulator.addScoringFunction(new CharyparNagelAgentStuckScoring(params));

				final CadytsCarScoring scoringFunction = new CadytsCarScoring(plan, config, cContext);
				final double cadytsScoringWeight = beta*30.;
				scoringFunction.setWeightOfCadytsCorrection(cadytsScoringWeight) ;
				scoringFunctionAccumulator.addScoringFunction(scoringFunction );

				return scoringFunctionAccumulator;
			}
		}) ;
		
		controler.run();
		
		
		//scenario data  test
		Assert.assertNotNull("config is null" , controler.getConfig());
		Assert.assertEquals("Different number of links in network.", controler.getNetwork().getLinks().size() , 23 );
		Assert.assertEquals("Different number of nodes in network.", controler.getNetwork().getNodes().size() , 15 );
		
		//Assert.assertNotNull("Transit schedule is null.", controler.getScenario().getTransitSchedule());
		//Assert.assertEquals("Num. of trLines is wrong.", 2, controler.getScenario().getTransitSchedule().getTransitLines().size() );
		//Assert.assertEquals("Num of facilities in schedule is wrong.", controler.getScenario().getTransitSchedule().getFacilities().size() , 5);
		//no car counterpart
		
		Assert.assertNotNull("Population is null.", controler.getScenario().getPopulation());
		
		//Assert.assertEquals("Num. of persons in population is wrong.", controler.getPopulation().getPersons().size(), 4);
		Assert.assertEquals("Num. of persons in population is wrong.", controler.getPopulation().getPersons().size(), 5);
		//Assert.assertEquals("Scale factor is wrong.", controler.getScenario().getConfig().ptCounts().getCountsScaleFactor(), 1.0, MatsimTestUtils.EPSILON);
		Assert.assertEquals("Scale factor is wrong.", controler.getScenario().getConfig().counts().getCountsScaleFactor(), 1.0, MatsimTestUtils.EPSILON);
		
		//Assert.assertEquals("Distance filter is wrong.", controler.getScenario().getConfig().ptCounts().getDistanceFilter() , 30000.0, MatsimTestUtils.EPSILON);
		//Assert.assertEquals("DistanceFilterCenterNode is wrong.", controler.getScenario().getConfig().ptCounts().getDistanceFilterCenterNode(), "7");
		//not used
		
		//counts
		//Assert.assertEquals("Occupancy count file is wrong.", controler.getScenario().getConfig().ptCounts().getOccupancyCountsFileName(), inputDir + "counts/counts_occupancy.xml");
		Assert.assertEquals("Count file is wrong.", controler.getScenario().getConfig().counts().getCountsFileName(), inputDir + "counts5.xml");
				
		//controler.getScenario().getConfig().ptCounts().getOccupancyCountsFileName(), inputDir + "counts/counts_occupancy.xml");
		//no car counterpart
		
		Counts occupCounts = new Counts();
		//new MatsimCountsReader(occupCounts).readFile(controler.getScenario().getConfig().ptCounts().getOccupancyCountsFileName());
		new MatsimCountsReader(occupCounts).readFile(controler.getScenario().getConfig().counts().getCountsFileName());
		
		//Count count =  occupCounts.getCount(new IdImpl("stop1"));
		Count count =  occupCounts.getCount(new IdImpl(19));
		//Assert.assertEquals("Occupancy counts description is wrong", occupCounts.getDescription(), "counts values for equil net");
		Assert.assertEquals("Occupancy counts description is wrong", occupCounts.getDescription(), "counts values for equil net");
		//Assert.assertEquals("CsId is wrong.", count.getCsId() , "stop1");
		Assert.assertEquals("CsId is wrong.", count.getCsId() , "link_19");
		//Assert.assertEquals("Volume of hour 4 is wrong", count.getVolume(7).getValue(), 4.0 , MatsimTestUtils.EPSILON);
		Assert.assertEquals("Volume of hour 6 is wrong", count.getVolume(7).getValue(), 5.0 , MatsimTestUtils.EPSILON);
		//Assert.assertEquals("Max count volume is wrong.", count.getMaxVolume().getValue(), 4.0 , MatsimTestUtils.EPSILON);
		Assert.assertEquals("Max count volume is wrong.", count.getMaxVolume().getValue(), 5.0 , MatsimTestUtils.EPSILON);

		// test resulting simulation volumes
		//{
			// String outCounts = outputDir + "ITERS/it." + lastIteration + "/" + lastIteration + ".simCountCompareOccupancy.txt";
			String outCounts = outputDir + "ITERS/it." + lastIteration + "/" + lastIteration + ".countscompare.txt";
			// CountsReader reader = new CountsReader(outCounts);
			CountsReaderCar reader = new CountsReaderCar(outCounts);
			double[] simValues;
			double[] realValues;

			// Id stopId1 = new IdImpl("stop1");
			Id locId11 = new IdImpl(11);
			// simValues = reader.getSimulatedValues(stopId1);
			simValues = reader.getSimulatedValues(locId11);
			// realValues= reader.getRealValues(stopId1);
			realValues= reader.getRealValues(locId11);
			// Assert.assertEquals("Volume of hour 6 is wrong", 4.0, simValues[6], MatsimTestUtils.EPSILON);
			Assert.assertEquals("Volume of hour 6 is wrong", 0.0, simValues[6], MatsimTestUtils.EPSILON);
			// Assert.assertEquals("Volume of hour 6 is wrong", 4.0, realValues[6], MatsimTestUtils.EPSILON);
			Assert.assertEquals("Volume of hour 6 is wrong", 0.0, realValues[6], MatsimTestUtils.EPSILON);

			// Id stopId2 = new IdImpl("stop2");
			Id locId12 = new IdImpl("12");
			// simValues = reader.getSimulatedValues(stopId2);
			simValues = reader.getSimulatedValues(locId12);
			// realValues= reader.getRealValues(stopId2);
			realValues= reader.getRealValues(locId12);
			Assert.assertEquals("Volume of hour 6 is wrong", 0.0, simValues[6], MatsimTestUtils.EPSILON);
			Assert.assertEquals("Volume of hour 6 is wrong", 0.0, realValues[6] , MatsimTestUtils.EPSILON);

			// Id stopId6 = new IdImpl("stop6");
			Id locId19 = new IdImpl("19");
			// simValues = reader.getSimulatedValues(stopId6);
			simValues = reader.getSimulatedValues(locId19);
			// realValues= reader.getRealValues(stopId6);
			realValues= reader.getRealValues(locId19);
			// Assert.assertEquals("Volume of hour 6 is wrong", 0.0, simValues[6], MatsimTestUtils.EPSILON);
			Assert.assertEquals("Volume of hour 6 is wrong", 5.0, simValues[6], MatsimTestUtils.EPSILON);
			// Assert.assertEquals("Volume of hour 6 is wrong", 2.0, realValues[6], MatsimTestUtils.EPSILON);
			Assert.assertEquals("Volume of hour 6 is wrong", 5.0, realValues[6], MatsimTestUtils.EPSILON);

			// Id stopId10 = new IdImpl("stop10");
			Id locId21 = new IdImpl("21");
			// simValues = reader.getSimulatedValues(stopId10);
			simValues = reader.getSimulatedValues(locId21);
			// realValues= reader.getRealValues(stopId10);
			realValues= reader.getRealValues(locId21);
			// Assert.assertEquals("Volume of hour 6 is wrong", 4.0, simValues[6], MatsimTestUtils.EPSILON);
			Assert.assertEquals("Volume of hour 6 is wrong", 5.0, simValues[6], MatsimTestUtils.EPSILON);
			// Assert.assertEquals("Volume of hour 6 is wrong", 5.0, realValues[6], MatsimTestUtils.EPSILON);
			Assert.assertEquals("Volume of hour 6 is wrong", 5.0, realValues[6], MatsimTestUtils.EPSILON);

			// test calibration statistics
			String testCalibStatPath = outputDir + "calibration-stats.txt";
			CalibrationStatReader calibrationStatReader = new CalibrationStatReader();
			new TabularFileParser().parse(testCalibStatPath, calibrationStatReader);

			CalibrationStatReader.StatisticsData outStatData= calibrationStatReader.getCalStatMap().get(lastIteration);
			// Assert.assertEquals("different Count_ll", "-0.046875", outStatData.getCount_ll() );
			// Assert.assertEquals("different Count_ll_pred_err",  "0.01836234363152515" , outStatData.getCount_ll_pred_err() );
//			Assert.assertEquals("different Link_lambda_avg", "-2.2604922388914356E-10", outStatData.getLink_lambda_avg() );
			Assert.assertEquals("different Link_lambda_avg", "3.2261421242498865E-5", outStatData.getLink_lambda_avg() );
//			Assert.assertEquals("different Link_lambda_max", "0.0" , outStatData.getLink_lambda_max() );
//			Assert.assertEquals("different Link_lambda_min", "-7.233575164452593E-9", outStatData.getLink_lambda_min() );
//			Assert.assertEquals("different Link_lambda_stddev", "1.261054219517188E-9", outStatData.getLink_lambda_stddev());
//			Assert.assertEquals("different P2p_ll", "--" , outStatData.getP2p_ll());
//			Assert.assertEquals("different Plan_lambda_avg", "-7.233575164452594E-9", outStatData.getPlan_lambda_avg() );
//			Assert.assertEquals("different Plan_lambda_max", "-7.233575164452593E-9" , outStatData.getPlan_lambda_max() );
//			Assert.assertEquals("different Plan_lambda_min", "-7.233575164452593E-9" , outStatData.getPlan_lambda_min() );
//			Assert.assertEquals("different Plan_lambda_stddev", "0.0" , outStatData.getPlan_lambda_stddev());
			// Assert.assertEquals("different Total_ll", "-0.046875", outStatData.getTotal_ll() );
			Assert.assertEquals("different Total_ll", "0.0", outStatData.getTotal_ll() );
			
			//test link offsets
			// final TransitSchedule schedule = controler.getScenario().getTransitSchedule();
			final Network network = controler.getScenario().getNetwork();
			String linkOffsetFile = outputDir + "ITERS/it." + lastIteration + "/" + lastIteration + ".linkCostOffsets.xml";
			// CadytsPtLinkCostOffsetsXMLFileIO offsetReader = new CadytsPtLinkCostOffsetsXMLFileIO (schedule);
			CadytsLinkCostOffsetsXMLFileIO offsetReader = new CadytsLinkCostOffsetsXMLFileIO(network);
			// DynamicData<TransitStopFacility> stopOffsets = offsetReader.read(linkOffsetFile);
			DynamicData<Link> linkOffsets = offsetReader.read(linkOffsetFile);
			
			// TransitStopFacility stop2 = schedule.getFacilities().get(stopId2);
			Link link11 = network.getLinks().get(new IdImpl("11"));
			// TransitStopFacility stop10 = schedule.getFacilities().get(stopId10);
			Link link19 = network.getLinks().get(new IdImpl("19"));
			
			//find first offset value different from null to compare. Useful to test with different time bin sizes
			int binIndex=-1;
			boolean isZero;
			do {
				binIndex++;
				// isZero = (Math.abs(stopOffsets.getBinValue(stop2 , binIndex) - 0.0) < MatsimTestUtils.EPSILON);
				isZero = (Math.abs(linkOffsets.getBinValue(link19 , binIndex) - 0.0) < MatsimTestUtils.EPSILON);
			}while (isZero && binIndex<86400);

			Assert.assertEquals("Wrong bin index for first link offset", 6, binIndex);
			
			// Assert.assertEquals("Wrong link offset of stop 10", 0.03515757824042241, stopOffsets.getBinValue(stop10 , binIndex), MatsimTestUtils.EPSILON);
			Assert.assertEquals("Wrong link offset of link 11", 0.0, linkOffsets.getBinValue(link11 , binIndex), MatsimTestUtils.EPSILON);
			// Assert.assertEquals("Wrong link offset of stop 2", -0.011353248321030008, stopOffsets.getBinValue(stop2 , binIndex), MatsimTestUtils.EPSILON);
			Assert.assertEquals("Wrong link offset of link 19", 0.0014707121641471912, linkOffsets.getBinValue(link19 , binIndex), MatsimTestUtils.EPSILON);
		//}
	}
	
	
	//--------------------------------------------------------------



	private static Config createTestConfig(String inputDir, String outputDir) {
		Config config = ConfigUtils.createConfig() ;
		// ---
		config.global().setRandomSeed(4711) ;
		// ---
		config.network().setInputFile(inputDir + "network.xml") ;
		// ---
		// config.plans().setInputFile(inputDir + "4plans.xml") ;
		config.plans().setInputFile(inputDir + "plans5.xml") ;
		// ---
		// config.scenario().setUseTransit(true) ;
		// config.scenario().setUseVehicles(true);
		// ---
		config.controler().setFirstIteration(1) ;
		config.controler().setLastIteration(10) ;
		config.controler().setOutputDirectory(outputDir) ;
		config.controler().setWriteEventsInterval(1) ;
		config.controler().setMobsim(MobsimType.qsim.toString()) ;
		// ---
		QSimConfigGroup qsimConfigGroup = new QSimConfigGroup() ;
		config.addQSimConfigGroup(qsimConfigGroup) ;
		
		// config.getQSimConfigGroup().setFlowCapFactor(0.02) ;
		config.getQSimConfigGroup().setFlowCapFactor(1.) ;
		// config.getQSimConfigGroup().setStorageCapFactor(0.06) ;
		config.getQSimConfigGroup().setStorageCapFactor(1.) ;
		config.getQSimConfigGroup().setStuckTime(10.) ;
		config.getQSimConfigGroup().setRemoveStuckVehicles(false) ; // ??
		// ---
//		config.transit().setTransitScheduleFile(inputDir + "transitSchedule1bus.xml") ;
//		config.transit().setVehiclesFile(inputDir + "vehicles.xml") ;
		Set<String> modes = new HashSet<String>() ;
		// modes.add("pt") ;
		modes.add("car");
		// config.transit().setTransitModes(modes) ;
		// ---
		{
			ActivityParams params = new ActivityParams("h") ;
			config.planCalcScore().addActivityParams(params ) ;
			params.setTypicalDuration(12*60*60.) ;
		}{
			ActivityParams params = new ActivityParams("w") ;
			config.planCalcScore().addActivityParams(params ) ;
			params.setTypicalDuration(8*60*60.) ;
		}
		// ---		
		
//		Module cadytsCarConfig = config.createModule(CadytsCarConfigGroup.GROUP_NAME ) ;
		
			
//		cadytsCarConfig.addParam(CadytsCarConfigGroup.START_TIME, "04:00:00") ;
//		cadytsCarConfig.addParam(CadytsCarConfigGroup.END_TIME, "20:00:00" ) ;
//		cadytsCarConfig.addParam(CadytsCarConfigGroup.REGRESSION_INERTIA, "0.95") ;
//		cadytsCarConfig.addParam(CadytsCarConfigGroup.USE_BRUTE_FORCE, "true") ;
//		cadytsCarConfig.addParam(CadytsCarConfigGroup.MIN_FLOW_STDDEV, "8") ;
//		cadytsCarConfig.addParam(CadytsCarConfigGroup.PREPARATORY_ITERATIONS, "1") ;
//		// cadytsCarConfig.addParam(CadytsCarConfigGroup.TIME_BIN_SIZE, "3600") ;
//		// cadytsCarConfig.addParam(CadytsCarConfigGroup.CALIBRATED_LINES, "M44,M43") ;
//		
//		CadytsCarConfigGroup ccc = new CadytsCarConfigGroup() ;
//		config.addModule(CadytsCarConfigGroup.GROUP_NAME, ccc) ;
		
		
		// ---
//		config.ptCounts().setOccupancyCountsFileName(inputDir + "counts/counts_occupancy.xml") ;
//		config.ptCounts().setBoardCountsFileName(inputDir + "counts/counts_boarding.xml") ;
//		config.ptCounts().setAlightCountsFileName(inputDir + "counts/counts_alighting.xml") ;
//		config.ptCounts().setDistanceFilter(30000.) ; // why?
//		config.ptCounts().setDistanceFilterCenterNode("7") ; // why?
//		config.ptCounts().setOutputFormat("txt");
//		config.ptCounts().setCountsScaleFactor(1.) ;
		
		//config.counts().setCountsFileName(inputDir + "counts/counts-5_-0.5.xml");
		config.counts().setCountsFileName(inputDir + "counts5.xml");
		// ---
		return config;
	}

	
	private static class DummyMobsim implements Mobsim {
		public DummyMobsim() {
		}
		@Override
		public void run() {
		}
	}

	private static class DummyMobsimFactory implements MobsimFactory {
		@Override
		public Mobsim createMobsim(final Scenario sc, final EventsManager eventsManager) {
			return new DummyMobsim();
		}
	}

}
