{\rtf1\ansi\ansicpg1252\cocoartf2761
\cocoatextscaling0\cocoaplatform0{\fonttbl\f0\fswiss\fcharset0 Helvetica;}
{\colortbl;\red255\green255\blue255;}
{\*\expandedcolortbl;;}
\margl1440\margr1440\vieww11520\viewh8400\viewkind0
\pard\tx720\tx1440\tx2160\tx2880\tx3600\tx4320\tx5040\tx5760\tx6480\tx7200\tx7920\tx8640\pardirnatural\partightenfactor0

\f0\fs24 \cf0 package s3.index.probe.config;\
\
import s3.configulator.Default;\
import s3.moria.probe.config.ProbeConfiguration;\
import s3.servertoolkit.configulator.DynamicState;\
\
/**\
 * The configurations for \{@link s3.index.probe.impl.BmHostAnomalyProbe\}\
 *\
 * @author yunhaoc\
 */\
public interface BmHostAnomalyProbeConfig extends ProbeConfiguration, HardwareHealthCheckConfig \{\
\
    /**\
     * How long do we wait before we fire an anomaly for a host reported with bad hardware?\
     * by default we report immediately\
     */\
    @Default("0")\
    @DynamicState\
    long getBadHardwareTimeLimit();\
    void setBadHardwareTimeLimit(long newTimeLimit);\
\
    /**\
     * How long do we wait before we fire an anomaly for an unpingable InC host?\
     */\
    @Default("10 minutes")\
    @DynamicState\
    long getUnPingableTimeLimit();\
    void setUnPingableTimeLimit(long newTimeLimit);\
\
    /**\
     * How long do we wait before we consider a pingable InC host as failed?\
     */\
    @Default("40 minutes")\
    @DynamicState\
    long getPingableFailTime();\
    void setPingableFailTime(long newFailTime);\
\
    /**\
     * How long do we wait before we consider an unknown InC host as failed?\
     */\
    @Default("20 minutes")\
    @DynamicState\
    long getUnknownFailTime();\
    void setUnknownFailTime(long newFailTime);\
\
    /**\
     * How long do we wait before we are confident to say a host that once flapped is not flapping any more?\
     */\
    @Default("1 hour")\
    @DynamicState\
    long getFlappingHostExpiryTime();\
    void setFlappingHostExpiryTime(long newExpiryTime);\
\
    /**\
     * How many times the host has switched states before we consider it flapping and fire and an anomaly?\
     */\
    @Default("4")\
    @DynamicState\
    int getFlapCountLimit();\
    void setFlapCountLimit(int newCount);\
\
    /**\
     * The maximum number of anomalies per anomaly type we are allowed to have\
     * in the anomaly store. Set it at 500 since we wanna track as many\
     * failed hosts as possible for visibility.\
     */\
    @Default("500")\
    @DynamicState\
    int getMaximumAnomaliesPerAnomalyType();\
    void setMaximumAnomaliesPerAnomalyType(int maximumAnomalies);\
\}}