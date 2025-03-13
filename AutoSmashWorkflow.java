package s3.moria.swf.host;\
\
import s3.moria.swf.host.repurpose.host.RepurposeHostWorkflow;\
\
public class AutoSmashWorkflow extends RepurposeHostWorkflow \{\
    @Override\
    protected String getPreconditionActivity() \{\
        return "preConditionAutoSmash";\
    \}\
\
    @Override\
    protected  String getSmashRequesterId() \{\
        return "samwise-autosmash-%s";\
    \}\
\}\
}
