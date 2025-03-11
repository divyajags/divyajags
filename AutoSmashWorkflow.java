{\rtf1\ansi\ansicpg1252\cocoartf2761
\cocoatextscaling0\cocoaplatform0{\fonttbl\f0\fnil\fcharset0 HelveticaNeue;}
{\colortbl;\red255\green255\blue255;\red236\green244\blue251;\red17\green21\blue26;}
{\*\expandedcolortbl;;\cssrgb\c94118\c96471\c98824;\cssrgb\c8235\c10588\c13725;}
\margl1440\margr1440\vieww29740\viewh17460\viewkind0
\deftab720
\pard\pardeftab720\partightenfactor0

\f0\fs32 \cf2 \cb3 \expnd0\expndtw0\kerning0
\outl0\strokewidth0 \strokec2 package s3.moria.swf.host;\
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