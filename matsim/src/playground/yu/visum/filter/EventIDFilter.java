package playground.yu.visum.filter;

import org.matsim.api.basic.v01.Id;
import org.matsim.api.basic.v01.events.BasicEvent;
import org.matsim.core.basic.v01.IdImpl;
import org.matsim.core.events.PersonEvent;

public class EventIDFilter extends EventFilterA {
	private static final Id criterion = new IdImpl(38);

	@Override
	public boolean judge(BasicEvent event) {
		if (event instanceof PersonEvent) {
			return (((PersonEvent) event).getPersonId().equals(criterion));
		}
		return false;
	}
}