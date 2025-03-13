package s3.index.probe.impl;\
\
import com.amazon.util.time.TimeSource;\
import com.google.common.base.MoreObjects;\
import org.assertj.core.util.VisibleForTesting;\
import s3.anomalystore.common.Anomaly;\
import s3.anomalystore.common.AnomalyType;\
import s3.anomalystore.common.EntityType;\
import s3.applicationregistry.ApplicationStatus;\
import s3.commons.log.S3Logger;\
import s3.commons.net.EndpointAddress;\
import s3.index.hardware.client.CheckHealthResponse;\
import s3.index.hardware.client.HardwareHealthCheckClient;\
import s3.index.hardware.client.HostState;\
import s3.index.probe.common.EntityCreator;\
import s3.index.probe.config.BmHostAnomalyProbeConfig;\
import s3.moria.common.Probe;\
import s3.moria.common.client.HostStatusClient;\
import s3.moria.common.helpers.MetricsHelper;\
import s3.querylogbuilder.EventData;\
\
import javax.annotation.concurrent.GuardedBy;\
import javax.annotation.concurrent.ThreadSafe;\
import java.util.Collection;\
import java.util.HashMap;\
import java.util.HashSet;\
import java.util.Map;\
import java.util.Optional;\
import java.util.Set;\
import java.util.concurrent.atomic.AtomicInteger;\
import java.util.stream.Collectors;\
\
/**\
 * This probe runs and detects host anomalies for BMs\
 *\
 * During each run, it gets the hardware health of all InC hosts, maps it to a state, and tracks the duration a BM\
 * spends in a state.\
 *\
 * It reports anomalies including\
 *\
 * 1. \{@link AnomalyType#HOSTFAILED\} if the endpoint stays in \{@link HostState#HEALTHY\},\
 *      \{@link HostState#UNEXPECTED_RESPONSE\}, or \{@link HostState#INTERNAL_ERROR\} longer certain time limit.\
 * 2. \{@link AnomalyType#HOSTUNPINGABLE\} if the query is not able to be delivered to OHA endpoint\
 * 3. \{@link AnomalyType#HOSTHARDWAREFAILURE\} if OHA finds any hardware issues with the host\
 * 4. \{@link AnomalyType#HOSTFLAPPING\} if the endpoint keeps switching state between InC and OK for several times\
 *\
 * @author yunhaoc\
 */\
@ThreadSafe\
public class BmHostAnomalyProbe implements Probe \{\
\
    @VisibleForTesting\
    static final String HOST_STATE_CHANGED = "host.state.changed";\
\
    private static final S3Logger log = new S3Logger();\
\
    @GuardedBy("incHostTrackers")\
    private volatile Map<EndpointAddress, IncommunicadoStateTracker> incHostTrackers = new HashMap<>();\
\
    @GuardedBy("flappingHostTrackers")\
    private volatile Map<EndpointAddress, FlappingStateTracker> flappingHostTrackers = new HashMap<>();\
\
    private final BmHostAnomalyProbeConfig config;\
    private final HostStatusClient hostStatusClient;\
    private final HardwareHealthCheckClient hardwareHealthCheckClient;\
    private final EntityType entityType;\
\
    public BmHostAnomalyProbe(BmHostAnomalyProbeConfig config,\
                              HostStatusClient hostStatusClient,\
                              HardwareHealthCheckClient hardwareHealthCheckClient,\
                              EntityType entityType) \{\
        this.config = config;\
        this.hostStatusClient = hostStatusClient;\
        this.hardwareHealthCheckClient = hardwareHealthCheckClient;\
        this.entityType = entityType;\
    \}\
\
    @Override\
    public Set<Anomaly> findAnomalies(EventData eventData) \{\
\
        Collection<EndpointAddress> prevIncEndpoints = incHostTrackers.keySet();\
        eventData.setCounter("prev.inc.endpoints", prevIncEndpoints.size());\
        log.debug("findAnomalies", "Dumping previous InC endpoints", prevIncEndpoints);\
\
        Collection<EndpointAddress> newIncEndpoints = hostStatusClient.nonOkDudes(eventData).stream().map(EndpointAddress::valueOf).collect(Collectors.toSet());\
        eventData.setCounter("new.inc.endpoints", newIncEndpoints.size());\
        log.debug("findAnomalies", "Dumping new InC endpoints", newIncEndpoints);\
\
        Map<EndpointAddress, HostState> hostToStateMap = hardwareHealthCheckClient\
            .batchCheckHealth(newIncEndpoints, eventData)\
            .stream()\
            .collect(Collectors.toMap(CheckHealthResponse::getHostEndpoint, CheckHealthResponse::getHostState));\
\
        Set<Anomaly> anomalies = new HashSet<>();\
\
        updateIncHostTrackers(hostToStateMap, eventData);\
        anomalies.addAll(reportIncommunicadoHostAnomalies());\
\
        updateFlappingHostTrackers(prevIncEndpoints, newIncEndpoints);\
        anomalies.addAll(reportFlappingHostAnomalies());\
\
        return anomalies;\
    \}\
\
    /**\
     * Based on the new set of InC endpoints and their corresponding states, update the incHostTrackers to reflect the changes\
     */\
    private void updateIncHostTrackers(Map<EndpointAddress, HostState> hostToStateMap, EventData eventData) \{\
        Map<EndpointAddress, IncommunicadoStateTracker> newIncHostTrackers = new HashMap<>();\
\
        MetricsHelper.emitZeroDataPoint(eventData, HOST_STATE_CHANGED);\
        hostToStateMap.forEach((endpoint, newHostState) -> \{\
\
            newIncHostTrackers.putIfAbsent(endpoint, new IncommunicadoStateTracker(newHostState, TimeSource.now()));\
\
            incHostTrackers.computeIfPresent(endpoint, (host, prevTracker) -> \{\
                if (newHostState != prevTracker.getHostHardwareState()) \{\
                    log.debug("reportIncommucadoHostAnomalies", "InC Host changes host state",\
                        "Endpoint", endpoint,\
                        "Previous state", prevTracker.getHostHardwareState(),\
                        "New state", newHostState);\
                    eventData.incCounter(HOST_STATE_CHANGED);\
                \}\
                // carry over the reported time for the previous state\
                newIncHostTrackers.get(endpoint).setReportedTime(prevTracker.getReportedTime());\
                return prevTracker;\
            \});\
        \});\
\
        this.incHostTrackers = newIncHostTrackers;\
    \}\
\
    private Set<Anomaly> reportIncommunicadoHostAnomalies() \{\
\
        Set<Anomaly> anomalies = new HashSet<>();\
        incHostTrackers.entrySet().stream().filter(entry -> this.entityType.equals(EntityCreator.getEntityType(entry.getKey())))\
        .forEach(hostTracker -> \{\
\
            switch (hostTracker.getValue().getHostHardwareState()) \{\
                // The host's hardware is "healthy" but the app is InC which means the service is down and its not a\
                // problem with the host.\
                case HEALTHY:\
                    Optional<Anomaly> pingableFailedAnomaly = maybeReportIncAnomaly(hostTracker.getValue(), hostTracker.getKey(), AnomalyType.HOSTFAILED, config.getPingableFailTime());\
                    pingableFailedAnomaly.ifPresent(anomalies::add);\
                    break;\
\
                case UNEXPECTED_RESPONSE:\
                case UNKNOWN_HOST:\
                case INTERNAL_ERROR:\
                    Optional<Anomaly> unknownFailedAnomaly = maybeReportIncAnomaly(hostTracker.getValue(), hostTracker.getKey(), AnomalyType.HOSTFAILED, config.getUnknownFailTime());\
                    unknownFailedAnomaly.ifPresent(anomalies::add);\
                    break;\
\
                case TIMEOUT:\
                    Optional<Anomaly> unPingableAnomaly = maybeReportIncAnomaly(hostTracker.getValue(), hostTracker.getKey(), AnomalyType.HOSTUNPINGABLE, config.getUnPingableTimeLimit());\
                    unPingableAnomaly.ifPresent(anomalies::add);\
                    break;\
\
                case NOT_HEALTHY:\
                    Optional<Anomaly> badHardwareAnomaly = maybeReportIncAnomaly(hostTracker.getValue(), hostTracker.getKey(), AnomalyType.HOSTHARDWAREFAILURE, config.getBadHardwareTimeLimit());\
                    badHardwareAnomaly.ifPresent(anomalies::add);\
                    break;\
\
                default:\
                    throw new IllegalArgumentException("Unrecognized host state: " + hostTracker.getValue().getHostHardwareState());\
            \}\
        \});\
\
        return anomalies;\
    \}\
\
    /**\
     * Return a corresponding anomaly for the InC endpoint if the endpoint has been in the anomalous state that exceeds the time limit\
     */\
    private Optional<Anomaly> maybeReportIncAnomaly(IncommunicadoStateTracker tracker, EndpointAddress endpoint, AnomalyType anomalyType, long timeLimit) \{\
        if (tracker.getTimeSinceReported() >= timeLimit) \{\
            return Optional.of(\
                    new Anomaly(\
                            EntityCreator.getHostEntity(endpoint),\
                            anomalyType,\
                            tracker.getTimeSinceReported(),\
                            config.getAnomalyDuration()\
                    )\
            );\
        \} else \{\
            return Optional.empty();\
        \}\
    \}\
\
    /**\
     * Compare the previously Incommunicado hosts and new Incommunicado hosts, update the flappingHostTrackers\
     */\
    private void updateFlappingHostTrackers(Collection<EndpointAddress> prevIncEndpoints, Collection<EndpointAddress> newIncEndpoints) \{\
        Set<EndpointAddress> endpointsSwitchedToOk = new HashSet<>(prevIncEndpoints);\
        endpointsSwitchedToOk.removeAll(newIncEndpoints);\
\
        Set<EndpointAddress> endpointsSwitchedToInC = new HashSet<>(newIncEndpoints);\
        endpointsSwitchedToInC.removeAll(prevIncEndpoints);\
\
        synchronized (flappingHostTrackers) \{\
            // remove expired entries that haven't flapped for a while\
            flappingHostTrackers.entrySet().removeIf(entry -> \{\
                log.debug("updateFlappingHostTrackers", "Expiring a flapping endpoint entry", "Endpoint", entry.getKey(), "Flapping State", entry.getValue());\
                return entry.getValue().getTimeSinceLastModified() >= config.getFlappingHostExpiryTime();\
            \});\
\
            endpointsSwitchedToInC.forEach(endpoint -> \{\
                log.debug("updateFlappingHostTrackers", "An OK endpoint switched to Incommunicado", "Endpoint", endpoint);\
                flappingHostTrackers.putIfAbsent(endpoint, new FlappingStateTracker(ApplicationStatus.INCOMMUNICADO));\
                flappingHostTrackers.get(endpoint).maybeSwitchState(ApplicationStatus.INCOMMUNICADO);\
            \});\
\
            endpointsSwitchedToOk.forEach(endpoint -> \{\
                log.debug("updateFlappingHostTrackers", "An InC endpoint switched back to OK", "Endpoint", endpoint);\
                flappingHostTrackers.putIfAbsent(endpoint, new FlappingStateTracker(ApplicationStatus.OK));\
                flappingHostTrackers.get(endpoint).maybeSwitchState(ApplicationStatus.OK);\
            \});\
        \}\
    \}\
\
    /**\
     * Report hosts that have been flapped too many times\
     */\
    private Set<Anomaly> reportFlappingHostAnomalies() \{\
\
        return flappingHostTrackers.entrySet().stream()\
                .filter(entry -> entry.getValue().getFlapCount() >= config.getFlapCountLimit())\
                .filter(entry -> this.entityType.equals(EntityCreator.getEntityType(entry.getKey())))\
                .map(entry ->\
                        new Anomaly(EntityCreator.getHostEntity(entry.getKey()),\
                                AnomalyType.HOSTFLAPPING,\
                                entry.getValue().getFlapCount(),\
                                config.getAnomalyDuration()\
                        )\
                ).collect(Collectors.toSet());\
    \}\
\
    /**\
     * A wrapper class that records the host state and the reported time for an InC host\
     */\
    private static final class IncommunicadoStateTracker \{\
        private volatile long reportedTime;\
        private volatile HostState hostHardwareState;\
\
        private IncommunicadoStateTracker(HostState hostHardwareState, long reportedTime) \{\
            this.hostHardwareState = hostHardwareState;\
            this.reportedTime = reportedTime;\
        \}\
\
        private long getReportedTime() \{\
            return this.reportedTime;\
        \}\
\
        private synchronized void setReportedTime(long newReportedTime) \{\
            this.reportedTime = newReportedTime;\
        \}\
\
        private long getTimeSinceReported() \{\
            return TimeSource.now() - this.reportedTime;\
        \}\
\
        private HostState getHostHardwareState() \{\
            return this.hostHardwareState;\
        \}\
\
        private synchronized void setHostHardwareState(HostState newState) \{\
            this.hostHardwareState = newState;\
        \}\
    \}\
\
    /**\
     * A tracker for the flapping state, it keeps\
     *\
     * 1. A timestamp when the host switches state for the first time\
     * 2. A timestamp when the host switches state most recently\
     * 3. A counter that shows how many times the host has switched the state\
     */\
    private static final class FlappingStateTracker \{\
\
        private final AtomicInteger counter;\
        private final long firstSeenTime;\
\
        private volatile long lastModifiedTime;\
        private volatile ApplicationStatus state;\
\
        private FlappingStateTracker(ApplicationStatus state) \{\
            this.state = state;\
            this.firstSeenTime = TimeSource.now();\
            this.lastModifiedTime = TimeSource.now();\
            this.counter = new AtomicInteger(0);\
        \}\
\
        private long getFirstSeenTime() \{\
            return this.firstSeenTime;\
        \}\
\
        private long getTimeSinceLastModified() \{\
            return TimeSource.now() - this.lastModifiedTime;\
        \}\
\
        private int getFlapCount() \{\
            return this.counter.get();\
        \}\
\
        private boolean isStateChanged(ApplicationStatus newState) \{\
            return state != newState;\
        \}\
\
        private synchronized void setState(ApplicationStatus newState) \{\
            this.state = newState;\
            this.lastModifiedTime = TimeSource.now();\
            counter.getAndIncrement();\
        \}\
\
        private synchronized void maybeSwitchState(ApplicationStatus newState) \{\
            if (isStateChanged(newState)) \{\
                setState(newState);\
            \}\
        \}\
\
        @Override\
        public String toString() \{\
            return MoreObjects.toStringHelper(this)\
                    .add("firstSeenTime", firstSeenTime)\
                    .add("lastModifiedTime", lastModifiedTime)\
                    .add("flapCount", counter.get())\
                    .add("finalState", state)\
                    .toString();\
        \}\
    \}\
\}}
