package xmu.wrxlab.abuilder;

import org.apache.commons.io.FileUtils;
import soot.PackManager;
import soot.Transform;
import xmu.wrxlab.abuilder.instrumentor.Instrumentor;

import java.io.File;
import java.io.IOException;

public class ABuilderMain {
    public static void main(String[] args) {
        // 解析参数
        String database = args[0];
        String projectId = args[1];
        String inputPath = args[2];
        String outputPath = args[3];
        String sootId = args[4];
        boolean first = sootId.equals("0");

        // 1. 将antrance ins拷贝到inputPath下
        System.out.println("[antrance builder server] copy antrance ins to " + inputPath);
        for (String antranceIns : Instrumentor.antranceInses) {
            File kernelIns = new File(database+"/kernel", antranceIns + ".class");
            try {
                FileUtils.copyFile(kernelIns, new File(inputPath, antranceIns + ".class"));
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        // 2. 启动soot进行插桩, 注意soot源码很多地方用了System.exit(), 遇到这种情况要重启服务
        System.out.println("==================================================");
        System.out.println("[antrance builder server] Start");
        System.out.println("[database] " + database);
        System.out.println("[projectId] " + projectId);
        System.out.println("[inputPath] " + inputPath);
        System.out.println("[outputPath] " + outputPath);
        System.out.println("[sootId] " + sootId);
        System.out.println("==================================================");
        long startTime = System.currentTimeMillis();

        soot.G.reset();
        String[] sootArgs = {
                "-allow-phantom-refs",
                "-no-bodies-for-excluded", // 重要优化项, 防止为依赖加载方法体
                "-process-dir", inputPath,
                "-d", outputPath,
                "-w",
                "-keep-line-number"
        };
        // 将自定义pack插入wjtp, 开启静态分析+字节码修改
        Instrumentor hzyInstrumentor = new Instrumentor(database, projectId, first);
        PackManager.v().getPack("wjtp").add(new Transform("wjtp.myTrans", hzyInstrumentor));
        soot.Main.main(sootArgs);

        System.out.println("[antrance builder server] time = " + (System.currentTimeMillis() - startTime));
        System.out.println("==================================================");
        System.out.println("[antrance builder server] End");
        System.out.println("==================================================");
    }
}
