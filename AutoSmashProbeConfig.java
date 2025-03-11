{\rtf1\ansi\ansicpg1252\cocoartf2761
\cocoatextscaling0\cocoaplatform0{\fonttbl\f0\fnil\fcharset0 HelveticaNeue;}
{\colortbl;\red255\green255\blue255;\red236\green244\blue251;\red17\green21\blue26;}
{\*\expandedcolortbl;;\cssrgb\c94118\c96471\c98824;\cssrgb\c8235\c10588\c13725;}
\margl1440\margr1440\vieww11520\viewh8400\viewkind0
\deftab720
\pard\pardeftab720\partightenfactor0

\f0\fs32 \cf2 \cb3 \expnd0\expndtw0\kerning0
\outl0\strokewidth0 \strokec2 package s3.index.probe.config;\
\
import s3.configulator.Default;\
import s3.configulator.DynamicallyConfigurable;\
import s3.moria.probe.config.ProbeConfiguration;\
import s3.servertoolkit.configulator.DynamicState;\
\
import java.util.Collection;\
import java.util.Map;\
\
public interface AutoSmashProbeConfig extends ProbeConfiguration, DynamicallyConfigurable, HardwareHealthCheckConfig \{\
\
    @Override\
    @Default("AutoSmashProbe")\
    String getRecordId();\
\
    /**\
     * How long do we wait after a scan?\
     */\
    @Default("1 hour")\
    long getScanIntervalMillis();\
\
    /**\
     * How long do we wait to create anomalies?\
     */\
    @Default("3 hours")\
    @DynamicState\
    long getFailureTimeMillis();\
    void setFailureTimeMillis(long timeMillis);\
\
    /**\
     * List of whitelisted services.\
     */\
    @Default("()")\
    Collection<String> getWhitelistedServices();\
    void setWhitelistedServices(Collection<String> whitelistedServices);\
\
    /**\
     * How many anomalies on Index Node we are generating?\
     *\
     * Note that we cannot be too aggressive with this number. We need to take Moria's\
     * capacity into consideration. Each index node host has about anywhere from a few\
     * hundred to a few thousand bricks on it. Thus smashing too many index nodes can\
     * result in too many MigrateBrickWorkflows, which exceeds Moria's capacity or takes\
     * resources to run other workflows.\
     */\
    @Default("3")\
    @DynamicState\
    long getMaximumNumberOfIndexNodeAnomalies();\
    void setMaximumNumberOfIndexNodeAnomalies(long maximumNumberOfIndexNodeAnomalies);\
\
    /**\
     * How many anomalies per service we are generating?\
     *\
     * Note that this will not affect the maximum number of anomalies generated for\
     * index-node.\
     */\
    @Default("1")\
    @DynamicState\
    long getMaximumNumberOfAnomaliesPerService();\
    void setMaximumNumberOfAnomaliesPerService(long maximumNumberOfAnomaliesPerService);\
\
    /**\
     * The threshold per service\
     */\
    /*\
     * Although the value of the map should be a Double, BrazilConfig doesn't support it as of now.\
     * It supports if it's a single value but not for Map/List: https://sage.amazon.com/posts/99551\
     */\
    @Default("\{\}")\
    Map<String, String> getAvailabilityThresholdPerService();\
    void setAvailabilityThresholdPerService(Map<String, String> thresholdPerService);\
\
    /**\
     * If the app is not in this list, the availability threshold will be calculated\
     * based on the entire fleet\
     * If it is in the list, it will be calculated per AZ\
     */\
    @Default("()")\
    Collection<String> getCheckAvailabilityThresholdPerAz();\
    void setCheckAvailabilityThresholdPerAz(Collection<String> apps);\
\
    /**\
     * How many parallel requests might be send from the client.\
     */\
    @Default("64")\
    @DynamicState\
    int getHardwareHealthCheckMaxRequests();\
    void setHardwareHealthCheckMaxRequests(int maxRequests);\
\
    /**\
     * How long do we wait for checking if a host is healthy or not.\
     */\
    @Default("15 seconds")\
    @DynamicState\
    long getHardwareHealthCheckTimeoutMillis();\
    void setHardwareHealthCheckTimeoutMillis(long timeMillis);\
\}}