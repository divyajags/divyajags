package s3.moria.swf.host.repurpose.host;\
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
