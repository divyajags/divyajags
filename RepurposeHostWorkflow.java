{\rtf1\ansi\ansicpg1252\cocoartf2761
\cocoatextscaling0\cocoaplatform0{\fonttbl\f0\fnil\fcharset0 HelveticaNeue;}
{\colortbl;\red255\green255\blue255;\red236\green244\blue251;\red17\green21\blue26;}
{\*\expandedcolortbl;;\cssrgb\c94118\c96471\c98824;\cssrgb\c8235\c10588\c13725;}
\margl1440\margr1440\vieww11520\viewh8400\viewkind0
\deftab720
\pard\pardeftab720\partightenfactor0

\f0\fs32 \cf2 \cb3 \expnd0\expndtw0\kerning0
\outl0\strokewidth0 \strokec2 package s3.moria.swf.host.repurpose.host;\
\
import com.amazonaws.services.simpleworkflow.flow.core.Promise;\
import s3.moria.swf.ChildWorkflows;\
import s3.moria.swf.Data;\
import s3.moria.swf.MoriaWorkflow;\
import s3.moria.swf.WorkflowParameters;\
import s3.moria.swf.activities.Activities;\
import s3.moria.swf.host.HostActivitiesCommon;\
\
public abstract class RepurposeHostWorkflow implements MoriaWorkflow \{\
    @Override\
    public Promise<Data> runWorkflow(WorkflowParameters parameters,\
                                     ChildWorkflows childWorkflows,\
                                     Activities activities) \{\
\
        final Promise<Data> hostEndpoint = parameters.getOrThrow("hostEndpoint");\
        final Promise<Data> dfddAppName = parameters.getOrThrow("dfddAppName");\
\
        //Check pre-conditions\
        final Promise<Data> waitForPreConditionCheck = activities.call(\
                HostActivitiesCommon.HOST_NAMESPACE,\
                getPreconditionActivity(),\
                hostEndpoint\
        );\
\
        return activities.call(\
                waitForPreConditionCheck,\
                HostActivitiesCommon.HOST_NAMESPACE,\
                "repurposeHost",\
                hostEndpoint,\
                dfddAppName,\
                Promise.asPromise(Data.of(String.format(getSmashRequesterId(), activities.taskId())))\
        );\
    \}\
\
    protected abstract String getPreconditionActivity();\
    protected abstract String getSmashRequesterId();\
\}}