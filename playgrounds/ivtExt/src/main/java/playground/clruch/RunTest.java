package playground.clruch;

import org.matsim.api.core.v01.Scenario;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.controler.Controler;
import org.matsim.core.scenario.ScenarioUtils;

public class RunTest {
    static public void main(String[] args) {
        Config config = ConfigUtils.loadConfig("C:\\Users\\Claudio\\Desktop\\sioux-2016\\sioux-2016\\config.xml");
        Scenario scenario = ScenarioUtils.loadScenario(config);

        Controler controller = new Controler(scenario);
        controller.run();
    }
}
