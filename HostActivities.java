{\rtf1\ansi\ansicpg1252\cocoartf2761
\cocoatextscaling0\cocoaplatform0{\fonttbl\f0\fswiss\fcharset0 Helvetica;}
{\colortbl;\red255\green255\blue255;}
{\*\expandedcolortbl;;}
\margl1440\margr1440\vieww11520\viewh8400\viewkind0
\pard\tx720\tx1440\tx2160\tx2880\tx3600\tx4320\tx5040\tx5760\tx6480\tx7200\tx7920\tx8640\pardirnatural\partightenfactor0

\f0\fs24 \cf0 package s3.moria.activities.hostmanagement;\
\
import org.apache.commons.lang3.StringUtils;\
import s3.commons.log.S3Logger;\
import s3.smash.index.client.S3SmashIndexClient;\
import s3.smash.index.client.protocol.S3SmashWorkflow;\
import s3.workflow.common.activities.ActivitySession;\
import s3.workflow.common.activities.annotations.Activity;\
import s3.workflow.common.activities.annotations.Arg;\
\
public class HostActivities \{\
    protected static final S3Logger log = new S3Logger();\
\
    private final S3SmashIndexClient smashClient;\
    protected static final String BRICK_MANAGER_DFDD_APP_NAME = "brick-manager";\
    protected static final String INDEX_NODE_DFDD_APP_NAME = "index-node";\
    protected static final String KFC_DFDD_APP_NAME = "spkm";\
    protected static final String SENTINEL_DFDD_APP_NAME = "index-node-sentinel";\
    private static final String REPURPOSE_INIIATED_FORMAT = "repurposeHost.app.initiated.%s";\
    private static final String REPURPOSE_SUCCESS_FORMAT = "repurposeHost.app.success.%s";\
    private static final String REPURPOSE_FAILURE_FORMAT = "repurposeHost.app.failure.%s";\
\
    public HostActivities(final S3SmashIndexClient smashClient) \{\
        this.smashClient = smashClient;\
    \}\
\
    private S3SmashWorkflow findSmashWorkflowForApp(final String dfddAppName) \{\
        switch (dfddAppName.toLowerCase()) \{\
            case BRICK_MANAGER_DFDD_APP_NAME:\
            case INDEX_NODE_DFDD_APP_NAME:\
                return S3SmashWorkflow.REPURPOSE_BM;\
            case KFC_DFDD_APP_NAME:\
                return S3SmashWorkflow.REPURPOSE_KFC;\
            case SENTINEL_DFDD_APP_NAME:\
                return S3SmashWorkflow.REPURPOSE_SENTINEL;\
            default:\
                return S3SmashWorkflow.REPURPOSE_HOST;\
        \}\
    \}\
\
    /**\
     * This activity will create a S3SmashIndex workflow to gracefully repurpose a host.\
     */\
    @Activity(help = "Repurpose a host.")\
    public void repurposeHost(\
            ActivitySession session,\
            @Arg(name = "hostEndpoint", help = "endpoint of the host") final String endpoint,\
            @Arg(name = "dfddAppName", help = "App for which we have to start a SMASH workflow") final String dfddAppName,\
            @Arg(name = "smashRequesterId", help = "Requester ID to be sent to SMASH workflow") final String smashRequesterId\
    ) \{\
        if (StringUtils.isBlank(dfddAppName) || StringUtils.isBlank(endpoint)) \{\
            log.warn(\
                    "repurposeHost",\
                    "Invalid dfdd app name (OR) endpoint provided",\
                    "endpoint", StringUtils.isBlank(endpoint) ? "null/blank" : endpoint,\
                    "dfdd app name", StringUtils.isBlank(dfddAppName) ? "null/blank" : dfddAppName\
            );\
            throw new RuntimeException("Cannot repurpose workflow without valid dfdd app name and/or endpoint");\
        \}\
\
        log.info(\
                "repurposeHost",\
                "will try to repurpose a host ",\
                "endpoint", endpoint,\
                "dfdd app name", dfddAppName\
        );\
        session.getMetrics().incCounter(String.format(REPURPOSE_INIIATED_FORMAT, dfddAppName.toLowerCase()), 1);\
\
        final S3SmashWorkflow repurposeWorkflow = findSmashWorkflowForApp(dfddAppName);\
\
        log.info("repurposeHost", "Smash workflow found",\
                "App", dfddAppName,\
                "Endpoint", endpoint,\
                "Workflow", repurposeWorkflow.getName());\
\
        final HostDeactivationManager hostDeactivationManager = new HostDeactivationManager(\
                this.smashClient,\
                null,\
                repurposeWorkflow\
        );\
\
        final boolean repurposeRequestSucceeded =\
                hostDeactivationManager.repurposeHost(endpoint, smashRequesterId);\
\
        if (!repurposeRequestSucceeded) \{\
            // Will retry the activity if the re-purpose request failed to initiate.\
            session.getMetrics().incCounter("repurposeHost.initiate.failed", 1);\
            session.getMetrics().incCounter(String.format(REPURPOSE_FAILURE_FORMAT, dfddAppName.toLowerCase()), 1);\
            log.warn(\
                    "repurposeHost",\
                    "Failed to initiate repurpose workflow",\
                    "endpoint", endpoint,\
                    "dfdd app name", dfddAppName\
            );\
            throw new RuntimeException("Failed to initiate repurpose workflow for host: " + endpoint + "; app: " + dfddAppName);\
        \}\
        session.getMetrics().incCounter(String.format(REPURPOSE_SUCCESS_FORMAT, dfddAppName.toLowerCase()), 1);\
    \}\
\}}