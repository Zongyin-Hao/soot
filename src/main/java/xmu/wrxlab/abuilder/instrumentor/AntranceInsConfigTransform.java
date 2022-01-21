package xmu.wrxlab.abuilder.instrumentor;

import java.util.Iterator;

import soot.Body;
import soot.SootClass;
import soot.SootMethod;
import soot.Unit;
import soot.jimple.AssignStmt;
import soot.jimple.IntConstant;
import soot.jimple.Stmt;
import soot.jimple.StringConstant;
import soot.util.Chain;
import xmu.wrxlab.abuilder.ABuilderServerConfig;

public class AntranceInsConfigTransform {
    /**
     * Antrance类
     */
    private final SootClass antranceIns;
    /**
     * 用户配置的项目名
     */
    private String projectId;
    /**
     * Instrumentor传入
     */
    private int stmtTableSize;

    public AntranceInsConfigTransform(SootClass antranceIns, String projectId, int stmtTableSize) {
        this.antranceIns = antranceIns;
        this.projectId = projectId;
        this.stmtTableSize = stmtTableSize;
    }

    // 将gradle配置以修改字节码的形式传给Callback
    public void start() {
        SootMethod clinit = antranceIns.getMethodByName("<clinit>");
        if (clinit.hasActiveBody()) {
            Body body = clinit.getActiveBody();
            if (!body.getUnits().isEmpty()) {
                Chain<Unit> units = body.getUnits();
                Iterator<Unit> stmtIt = units.snapshotIterator();
                while (stmtIt.hasNext()) {
                    Stmt stmt = (Stmt) stmtIt.next();
                    if (stmt instanceof AssignStmt) {
                        AssignStmt assignStmt = (AssignStmt) stmt;
                        String sig = assignStmt.getLeftOp().toString();
                        switch (sig) {
                            case "<AntranceIns: java.lang.String projectId>":
                                assignStmt.setRightOp(StringConstant.v(projectId));
                                break;
                            case "<AntranceIns: int stmtTableSize>":
                                assignStmt.setRightOp(IntConstant.v(stmtTableSize));
                                break;
                        }
                    } // end of if stmt instanceof AssignStmt
                } // end of while stmtIt.hasNext
            } // end of !body.getUnits.isEmpty
        } // end of clinit.hasActiveBody
    } // end of start
}