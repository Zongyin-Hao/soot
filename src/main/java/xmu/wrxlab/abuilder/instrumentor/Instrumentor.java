package xmu.wrxlab.abuilder.instrumentor;

import java.io.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

import fj.P;
import soot.Scene;
import soot.SceneTransformer;
import soot.SootClass;
import xmu.wrxlab.abuilder.ABuilderServerConfig;

/**
 * 运行4个transform: cfg, antrance ins, antrance ins config, debug
 */
public class Instrumentor extends SceneTransformer {
    /**
     * 数据库路径
     */
    private String database;
    /**
     * 用户配置的项目名
     */
    private String projectId;
    /**
     * 重要, gradle任务有时会把classes分成多个文件, 调用多次soot, 这里判断是不是第一个,
     * 防止cfg, idToSig, debugjimple错误的初始化
     */
    private boolean first;
    /**
     * 编译时统计, log id->log sig, 用于给每个桩一个id, 运行时只需要向AntranceIns.stmtTable对应位置写1即可, 提高效率.
     * stmtTableSize为logIdSig大小;
     * sig格式: methodSig@jid@sid@type@value"(jid:语句在函数中的字节码id, sid:语句在文件中的源码行,
     * type目前只考虑了branch(br), value 0表示false/default分支, >=1表示true/各个case分支),
     * 特别地, 对于每个函数的入口插桩, 只记录methodSig.
     * 重要: gradle插件可能把classes分成多个文件, 多次调用soot, 不记录上次状态的话logIdSig会发生严重错误,
     * 因此将logIdSig保存在logIdSig.txt, 每次重新读取加载, 只在first时初始化
     */
    private Map<Integer, String> logIdSig;
    /**
     * 重要, 桩类名, 0号一定要为AntranceIns类
     */
    public static final String[] antranceInses = {
            "AntranceIns", "UnCaughtExceptionHandler", "UnCaughtExceptionHandler$1"
    };
    /**
     * 过滤后的应用类
     */
    private final ArrayList<SootClass> myClasses;
    /**
     * Antrance类
     */
    private SootClass antranceIns;

    public Instrumentor(String database, String projectId, boolean first) {
        this.database = database;
        this.projectId = projectId;
        this.first = first;
        // first不为true时从database下的logIdSig.txt中加载logIdSig
        logIdSig = new HashMap<>();
        if (!first) {
            File file = new File(database+"/"+projectId, "logIdSig.txt");
            BufferedReader in = null;
            try {
                in = new BufferedReader(new InputStreamReader(new FileInputStream(file)));
                String line = null;
                while ((line = in.readLine()) != null) {
                    String[] sp = line.split(" ");
                    Integer logId = Integer.parseInt(sp[0]);
                    StringBuilder sb = new StringBuilder(sp[1]);
                    for (int i = 2; i < sp.length; i++) {
                        sb.append(sp[i]);
                    }
                    String sig = sb.toString();
                    logIdSig.put(logId, sig);
                }
            } catch (IOException e) {
                e.printStackTrace();
                throw new RuntimeException("read logIdSig.txt error");
            } finally {
                if (in != null) {
                    try {
                        in.close();
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
            }
        }
        myClasses = new ArrayList<>();
        antranceIns = null;
    }

    /**
     * 过滤应用类得到myClasses, 寻找AntranceIns
     */
    private void initialize() {
        for (SootClass myClass : Scene.v().getApplicationClasses()) {
            if (myClass.getShortName().equals(antranceInses[0])) {
                antranceIns = myClass;
                continue;
            }
            // antrance ins不要加载到myClasses中
            boolean skip = false;
            for (int i = 1; i < antranceInses.length; i++) {
                if (myClass.getShortName().equals(antranceInses[i])) {
                    skip = true;
                    break;
                }
            }
            if (skip) {
                continue;
            }
            myClasses.add(myClass);
        }
    }

    protected void internalTransform(String phase, Map options) {
        initialize();
        // 构造程序的控制流图, 注意要在字节码修改前构造
        (new CFGTransform(myClasses, database, projectId, first)).start();
        // 插桩, 插桩过程中统计stmtTableSize, 更新AntranceBuilderConfig,
        // 从而在AntranceInsConfigTransform时可以正确配置stmtTableSize
        (new AntranceInsTransform(myClasses, antranceIns, database, projectId, logIdSig)).start();
        // 修改静态面值, 配置AntranceIns
        (new AntranceInsConfigTransform(antranceIns, projectId, logIdSig.size())).start();
        // debug jimple
        (new DebugJimpleTransform(myClasses, antranceIns, database, projectId, first)).start();
    }

} // end of class