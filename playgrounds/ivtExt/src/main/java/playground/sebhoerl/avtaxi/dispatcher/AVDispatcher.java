package playground.sebhoerl.avtaxi.dispatcher;

import org.matsim.core.config.ConfigGroup;
import playground.sebhoerl.avtaxi.config.AVDispatcherConfig;
import playground.sebhoerl.avtaxi.data.AVVehicle;
import playground.sebhoerl.avtaxi.passenger.AVRequest;
import playground.sebhoerl.avtaxi.schedule.AVDriveTask;
import playground.sebhoerl.avtaxi.schedule.AVTask;

public interface AVDispatcher {
    // happens if new customer request registered
    void onRequestSubmitted(AVRequest request);
    // is called if AV has completed current task.
    // tasks: pickup, stay, drive, dropoff
    void onNextTaskStarted(AVTask task);
    // is called every time step, decide actions for next time step
    // 1 time step = 1 second
    void onNextTimestep(double now);
    // is called at beginning of simulation to add desired number of AVs
    void addVehicle(AVVehicle vehicle);
    interface AVDispatcherFactory {
        AVDispatcher createDispatcher(AVDispatcherConfig config);
    }
}
